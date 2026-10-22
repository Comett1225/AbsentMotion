#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMExport", __VA_ARGS__)

namespace {

const char* kVertex =
    "attribute vec2 p;\n"
    "varying vec2 uv;\n"
    "void main() { uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5); gl_Position = vec4(p, 0.0, 1.0); }\n";

const char* kFragment =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "uniform samplerExternalOES frame;\n"
    "varying vec2 uv;\n"
    "void main() { gl_FragColor = vec4(texture2D(frame, uv).rgb, 1.0); }\n";

struct Copier {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    GLuint program = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetTexture = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;
    struct Imported {
        AHardwareBuffer* buffer;
        EGLImageKHR image;
    };
    std::vector<Imported> images;

    EGLImageKHR imageOf(AHardwareBuffer* buffer) {
        for (const Imported& i : images) {
            if (i.buffer == buffer) return i.image;
        }
        const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR image = createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer(buffer), attributes);
        if (image == EGL_NO_IMAGE_KHR) return image;
        AHardwareBuffer_acquire(buffer);
        images.push_back({buffer, image});
        return image;
    }

    bool current() { return eglMakeCurrent(display, surface, surface, context) == EGL_TRUE; }
    void release() { eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); }

    ~Copier() {
        for (const Imported& i : images) {
            destroyImage(display, i.image);
            AHardwareBuffer_release(i.buffer);
        }
        if (display != EGL_NO_DISPLAY) {
            if (program && current()) {
                glDeleteProgram(program);
                glDeleteTextures(1, &texture);
            }
            release();
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        }
        if (window) ANativeWindow_release(window);
    }
};

GLuint compile(GLenum kind, const char* source) {
    GLuint shader = glCreateShader(kind);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("encoder copy shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool setUp(Copier* c) {
    c->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (c->display == EGL_NO_DISPLAY || !eglInitialize(c->display, nullptr, nullptr)) return false;
    c->clientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    c->createImage = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    c->destroyImage = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    c->targetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    c->presentationTime = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
    if (!c->clientBuffer || !c->createImage || !c->destroyImage || !c->targetTexture || !c->presentationTime) return false;
    const EGLint configAttributes[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                                       EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                       EGL_RECORDABLE_ANDROID, EGL_TRUE, EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(c->display, configAttributes, &config, 1, &count) || count < 1) return false;
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    c->context = eglCreateContext(c->display, config, EGL_NO_CONTEXT, contextAttributes);
    if (c->context == EGL_NO_CONTEXT) return false;
    c->surface = eglCreateWindowSurface(c->display, config, c->window, nullptr);
    if (c->surface == EGL_NO_SURFACE || !c->current()) return false;
    c->width = ANativeWindow_getWidth(c->window);
    c->height = ANativeWindow_getHeight(c->window);
    GLuint vertex = compile(GL_VERTEX_SHADER, kVertex);
    GLuint fragment = compile(GL_FRAGMENT_SHADER, kFragment);
    if (!vertex || !fragment) return false;
    c->program = glCreateProgram();
    glAttachShader(c->program, vertex);
    glAttachShader(c->program, fragment);
    glBindAttribLocation(c->program, 0, "p");
    glLinkProgram(c->program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(c->program, GL_LINK_STATUS, &linked);
    if (!linked) return false;
    glGenTextures(1, &c->texture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, c->texture);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    c->release();
    return true;
}

bool copy(Copier* c, AHardwareBuffer* buffer, int64_t presentNs) {
    if (!buffer || !c->current()) return false;
    EGLImageKHR image = c->imageOf(buffer);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("encoder copy: eglCreateImageKHR failed (0x%x)", eglGetError());
        c->release();
        return false;
    }
    static const GLfloat quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    glViewport(0, 0, c->width, c->height);
    glUseProgram(c->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, c->texture);
    c->targetTexture(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)image);
    glUniform1i(glGetUniformLocation(c->program, "frame"), 0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    c->presentationTime(c->display, c->surface, presentNs);
    bool ok = eglSwapBuffers(c->display, c->surface) == EGL_TRUE;
    if (!ok) LOGE("encoder copy: eglSwapBuffers failed (0x%x)", eglGetError());
    glFinish();
    c->release();
    return ok;
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_app_absentmotion_export_EncoderCopy_nativeOpen(JNIEnv* env, jclass, jobject surface) {
    auto* c = new Copier();
    c->window = ANativeWindow_fromSurface(env, surface);
    if (!c->window || !setUp(c)) {
        LOGE("encoder copy: EGL set-up failed (0x%x)", eglGetError());
        delete c;
        return 0;
    }
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jboolean JNICALL Java_app_absentmotion_export_EncoderCopy_nativeCopy(JNIEnv* env, jclass, jlong handle, jobject buffer, jlong presentNs) {
    return copy(reinterpret_cast<Copier*>(handle), AHardwareBuffer_fromHardwareBuffer(env, buffer), presentNs);
}

JNIEXPORT void JNICALL Java_app_absentmotion_export_EncoderCopy_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Copier*>(handle);
}

}
