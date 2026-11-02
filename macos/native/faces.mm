#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <vector>
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <Vision/Vision.h>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_absentmotion_pc_engine_DesktopNative_detectFaces(JNIEnv *env, jobject, jintArray pixels, jint width, jint height) {
    if (!pixels || width < 1 || height < 1 || int64_t(width) * height > env->GetArrayLength(pixels)) return nullptr;
    std::vector<jint> argb(static_cast<size_t>(width) * height);
    env->GetIntArrayRegion(pixels, 0, static_cast<jsize>(argb.size()), argb.data());
    if (env->ExceptionCheck()) return nullptr;
    CGColorSpaceRef color = CGColorSpaceCreateDeviceRGB();
    if (!color) return nullptr;
    CGContextRef context = CGBitmapContextCreate(argb.data(), width, height, 8, static_cast<size_t>(width) * 4, color,
        kCGBitmapByteOrder32Little | kCGImageAlphaNoneSkipFirst);
    CGColorSpaceRelease(color);
    if (!context) return nullptr;
    CGImageRef image = CGBitmapContextCreateImage(context);
    CGContextRelease(context);
    if (!image) return nullptr;
    std::vector<jfloat> result;
    bool ok = false;
    @autoreleasepool {
        VNDetectFaceRectanglesRequest *request = [[VNDetectFaceRectanglesRequest alloc] init];
        VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:image options:@{}];
        NSError *error = nil;
        if ([handler performRequests:@[request] error:&error]) {
            ok = true;
            for (VNFaceObservation *face in request.results) {
                if (face.confidence < 0.3f) continue;
                CGRect box = face.boundingBox;
                result.push_back(static_cast<jfloat>((box.origin.x + box.size.width * 0.5) * width));
                result.push_back(static_cast<jfloat>((1.0 - box.origin.y - box.size.height * 0.5) * height));
                result.push_back(static_cast<jfloat>(std::max(box.size.width * width, box.size.height * height) * 1.25));
                if (result.size() >= 12) break;
            }
        }
    }
    CGImageRelease(image);
    if (!ok) return nullptr;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (out && !result.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(result.size()), result.data());
    return out;
}
