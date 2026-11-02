#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <media/NdkImageReader.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>

namespace {

struct Frames {
    AImageReader* reader = nullptr;
    std::mutex lock;
    std::condition_variable ready;
    std::deque<AImage*> images;
    bool closed = false;
};

void Arrived(void* context, AImageReader* reader) {
    auto* frames = static_cast<Frames*>(context);
    std::lock_guard<std::mutex> guard(frames->lock);
    while (true) {
        AImage* image = nullptr;
        if (AImageReader_acquireNextImage(reader, &image) != AMEDIA_OK || !image) break;
        if (frames->closed) AImage_delete(image);
        else frames->images.push_back(image);
    }
    frames->ready.notify_all();
}

Frames* Of(jlong handle) { return reinterpret_cast<Frames*>(handle); }
AImage* Image(jlong handle) { return reinterpret_cast<AImage*>(handle); }

void DeleteQueued(Frames* frames) {
    for (AImage* image : frames->images) AImage_delete(image);
    frames->images.clear();
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_app_absentmotion_media_NdkImages_open(JNIEnv*, jobject, jint width, jint height, jint count) {
    auto* frames = new Frames();
    if (AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_PRIVATE, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, count, &frames->reader) != AMEDIA_OK) {
        delete frames;
        return 0;
    }
    AImageReader_ImageListener listener{frames, Arrived};
    AImageReader_setImageListener(frames->reader, &listener);
    return reinterpret_cast<jlong>(frames);
}

JNIEXPORT jobject JNICALL Java_app_absentmotion_media_NdkImages_surface(JNIEnv* env, jobject, jlong handle) {
    ANativeWindow* window = nullptr;
    if (AImageReader_getWindow(Of(handle)->reader, &window) != AMEDIA_OK || !window) return nullptr;
    return ANativeWindow_toSurface(env, window);
}

JNIEXPORT jlong JNICALL Java_app_absentmotion_media_NdkImages_poll(JNIEnv*, jobject, jlong handle, jlong timeoutMs) {
    Frames* frames = Of(handle);
    std::unique_lock<std::mutex> guard(frames->lock);
    frames->ready.wait_for(guard, std::chrono::milliseconds(timeoutMs), [&] { return !frames->images.empty() || frames->closed; });
    if (frames->images.empty()) return 0;
    AImage* image = frames->images.front();
    frames->images.pop_front();
    return reinterpret_cast<jlong>(image);
}

JNIEXPORT void JNICALL Java_app_absentmotion_media_NdkImages_drain(JNIEnv*, jobject, jlong handle) {
    Frames* frames = Of(handle);
    std::lock_guard<std::mutex> guard(frames->lock);
    DeleteQueued(frames);
}

JNIEXPORT void JNICALL Java_app_absentmotion_media_NdkImages_close(JNIEnv*, jobject, jlong handle) {
    Frames* frames = Of(handle);
    {
        std::lock_guard<std::mutex> guard(frames->lock);
        frames->closed = true;
        frames->ready.notify_all();
    }
    AImageReader_setImageListener(frames->reader, nullptr);
    {
        std::lock_guard<std::mutex> guard(frames->lock);
        DeleteQueued(frames);
    }
    AImageReader_delete(frames->reader);
    delete frames;
}

JNIEXPORT jlong JNICALL Java_app_absentmotion_media_NdkImages_timestamp(JNIEnv*, jobject, jlong image) {
    int64_t ns = 0;
    AImage_getTimestamp(Image(image), &ns);
    return ns;
}

JNIEXPORT void JNICALL Java_app_absentmotion_media_NdkImages_crop(JNIEnv* env, jobject, jlong image, jintArray out) {
    AImageCropRect rect{};
    AImage_getCropRect(Image(image), &rect);
    const jint values[4] = {rect.left, rect.top, rect.right, rect.bottom};
    env->SetIntArrayRegion(out, 0, 4, values);
}

JNIEXPORT jobject JNICALL Java_app_absentmotion_media_NdkImages_buffer(JNIEnv* env, jobject, jlong image) {
    AHardwareBuffer* buffer = nullptr;
    if (AImage_getHardwareBuffer(Image(image), &buffer) != AMEDIA_OK || !buffer) return nullptr;
    return AHardwareBuffer_toHardwareBuffer(env, buffer);
}

JNIEXPORT void JNICALL Java_app_absentmotion_media_NdkImages_release(JNIEnv*, jobject, jlong image) {
    AImage_delete(Image(image));
}

}
