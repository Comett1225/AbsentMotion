#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>

#include "include/core/SkImage.h"

class GrDirectContext;

class GlYuv {
public:
    sk_sp<SkImage> convert(GrDirectContext* context, AHardwareBuffer* buffer, int colorStandard, int colorRange, SkAlphaType alpha);

private:
    bool setUp();
    int fState = 0;
    GLuint fProgram = 0;
    GLuint fExternal = 0;
    GLuint fFramebuffer = 0;
    GLint fMatrix = -1;
    GLint fOffset = -1;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC fClientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC fCreateImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC fDestroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC fTargetTexture = nullptr;
};
