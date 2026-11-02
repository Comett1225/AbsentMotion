#pragma once

#include <jni.h>
#include <cstdint>
#include <string>
#include "include/core/SkImage.h"
#include "include/core/SkImageInfo.h"

class GrDirectContext;
struct AVBufferRef;
struct AVFrame;

GrDirectContext* DesktopContext(jlong desktop);
std::string DesktopString(JNIEnv* env, jstring s);
void DesktopInstall(jlong desktop, const std::string& uri, sk_sp<SkImage> image);
void DesktopInstallLayer(jlong desktop, const std::string& uri, int64_t layer, sk_sp<SkImage> image);

AVBufferRef* VideoHwDevice();
sk_sp<SkImage> VideoHwImage(GrDirectContext* context, const AVFrame* frame, SkYUVColorSpace space);
