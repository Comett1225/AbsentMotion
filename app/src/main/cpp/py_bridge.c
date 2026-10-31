#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct PyInitConfig PyInitConfig;

static JavaVM* vm;
static jclass engine;
static jmethodID callback;
static pthread_key_t attached;
static int started;

static int (*run_string)(const char*);
static int (*gil_ensure)(void);
static void (*gil_release)(int);

static void detach(void* unused) {
    (void)unused;
    (*vm)->DetachCurrentThread(vm);
}

static JNIEnv* env_here(void) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(attached, env);
    return env;
}

char* am_call(const char* request) {
    JNIEnv* env = env_here();
    if (!env || !request) return NULL;
    jsize n = (jsize)strlen(request);
    jbyteArray in = (*env)->NewByteArray(env, n);
    if (!in) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, in, 0, n, (const jbyte*)request);
    jbyteArray out = (jbyteArray)(*env)->CallStaticObjectMethod(env, engine, callback, in);
    (*env)->DeleteLocalRef(env, in);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    if (!out) return NULL;
    jsize m = (*env)->GetArrayLength(env, out);
    char* text = malloc((size_t)m + 1);
    if (text) {
        (*env)->GetByteArrayRegion(env, out, 0, m, (jbyte*)text);
        text[m] = 0;
    }
    (*env)->DeleteLocalRef(env, out);
    return text;
}

void am_free(char* text) { free(text); }

static jstring fail(JNIEnv* env, const char* what, const char* detail) {
    char message[512];
    snprintf(message, sizeof message, "%s: %s", what, detail ? detail : "unknown");
    __android_log_print(ANDROID_LOG_WARN, "AMPython", "%s", message);
    return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jstring JNICALL Java_app_absentmotion_tools_PyEngine_nativeStart(JNIEnv* env, jclass cls, jobjectArray libs, jstring home) {
    if (started) return NULL;
    if (!vm) {
        (*env)->GetJavaVM(env, &vm);
        engine = (jclass)(*env)->NewGlobalRef(env, cls);
        callback = (*env)->GetStaticMethodID(env, cls, "call", "([B)[B");
        if (!callback) return fail(env, "callback", "missing");
        pthread_key_create(&attached, detach);
    }
    void* python = NULL;
    jsize count = (*env)->GetArrayLength(env, libs);
    for (jsize i = 0; i < count; i++) {
        jstring path = (jstring)(*env)->GetObjectArrayElement(env, libs, i);
        const char* text = (*env)->GetStringUTFChars(env, path, NULL);
        python = dlopen(text, RTLD_NOW | RTLD_GLOBAL);
        (*env)->ReleaseStringUTFChars(env, path, text);
        (*env)->DeleteLocalRef(env, path);
        if (!python) return fail(env, "load", dlerror());
    }
    PyInitConfig* (*create)(void) = dlsym(python, "PyInitConfig_Create");
    int (*set_str)(PyInitConfig*, const char*, const char*) = dlsym(python, "PyInitConfig_SetStr");
    int (*set_int)(PyInitConfig*, const char*, long long) = dlsym(python, "PyInitConfig_SetInt");
    int (*get_error)(PyInitConfig*, const char**) = dlsym(python, "PyInitConfig_GetError");
    void (*destroy)(PyInitConfig*) = dlsym(python, "PyInitConfig_Free");
    int (*initialize)(PyInitConfig*) = dlsym(python, "Py_InitializeFromInitConfig");
    void* (*save_thread)(void) = dlsym(python, "PyEval_SaveThread");
    run_string = dlsym(python, "PyRun_SimpleString");
    gil_ensure = dlsym(python, "PyGILState_Ensure");
    gil_release = dlsym(python, "PyGILState_Release");
    if (!create || !set_str || !set_int || !get_error || !destroy || !initialize || !save_thread || !run_string || !gil_ensure || !gil_release)
        return fail(env, "symbols", dlerror());
    PyInitConfig* config = create();
    if (!config) return fail(env, "config", "no memory");
    const char* home_text = (*env)->GetStringUTFChars(env, home, NULL);
    int bad = set_str(config, "home", home_text) < 0
        || set_int(config, "install_signal_handlers", 0) < 0
        || set_int(config, "user_site_directory", 0) < 0
        || set_int(config, "use_environment", 0) < 0
        || set_int(config, "parse_argv", 0) < 0
        || initialize(config) < 0;
    (*env)->ReleaseStringUTFChars(env, home, home_text);
    if (bad) {
        const char* error = NULL;
        get_error(config, &error);
        jstring message = fail(env, "start", error);
        destroy(config);
        return message;
    }
    destroy(config);
    save_thread();
    started = 1;
    return NULL;
}

JNIEXPORT jint JNICALL Java_app_absentmotion_tools_PyEngine_nativeRun(JNIEnv* env, jclass cls, jstring code) {
    (void)cls;
    if (!started) return -2;
    const char* text = (*env)->GetStringUTFChars(env, code, NULL);
    if (!text) return -3;
    int state = gil_ensure();
    int result = run_string(text);
    gil_release(state);
    (*env)->ReleaseStringUTFChars(env, code, text);
    return result;
}
