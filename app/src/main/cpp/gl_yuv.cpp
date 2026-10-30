#include "gl_yuv.h"

#include <android/log.h>

#include <cstring>

#include "include/core/SkColorSpace.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/gl/GrGLBackendSurface.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AMEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMEngine", __VA_ARGS__)

namespace {

const char* kVertex =
    "#version 300 es\n"
    "in vec2 p;\n"
    "out vec2 uv;\n"
    "void main() {\n"
    "  uv = p * 0.5 + 0.5;\n"
    "  gl_Position = vec4(p, 0.0, 1.0);\n"
    "}\n";

const char* kFragment =
    "#version 300 es\n"
    "#extension GL_EXT_YUV_target : require\n"
    "precision highp float;\n"
    "uniform __samplerExternal2DY2YEXT frame;\n"
    "uniform mat3 m;\n"
    "uniform vec3 o;\n"
    "in vec2 uv;\n"
    "out vec4 color;\n"
    "void main() { color = vec4(clamp(m * (texture(frame, uv).xyz - o), 0.0, 1.0), 1.0); }\n";

GLuint Compile(GLenum kind, const char* source) {
    GLuint shader = glCreateShader(kind);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("YUV shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool IsRgb(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
        case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
        case AHARDWAREBUFFER_FORMAT_BLOB:
            return true;
        default:
            return false;
    }
}

}

bool GlYuv::setUp() {
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    if (!extensions || !strstr(extensions, "GL_EXT_YUV_target")) {
        LOGI("OpenGL video frames: the driver converts YUV (no GL_EXT_YUV_target)");
        return false;
    }
    fClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    fCreateImage = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    fDestroyImage = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    fTargetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if (!fClientBuffer || !fCreateImage || !fDestroyImage || !fTargetTexture) return false;
    GLuint vertex = Compile(GL_VERTEX_SHADER, kVertex);
    GLuint fragment = Compile(GL_FRAGMENT_SHADER, kFragment);
    if (!vertex || !fragment) return false;
    fProgram = glCreateProgram();
    glAttachShader(fProgram, vertex);
    glAttachShader(fProgram, fragment);
    glBindAttribLocation(fProgram, 0, "p");
    glLinkProgram(fProgram);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(fProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("YUV program did not link");
        return false;
    }
    fMatrix = glGetUniformLocation(fProgram, "m");
    fOffset = glGetUniformLocation(fProgram, "o");
    glGenTextures(1, &fExternal);
    glGenFramebuffers(1, &fFramebuffer);
    LOGI("OpenGL video frames: converted with the stream's own colour matrix");
    return true;
}

sk_sp<SkImage> GlYuv::convert(GrDirectContext* context, AHardwareBuffer* buffer, int colorStandard, int colorRange, SkAlphaType alpha) {
    if (fState == 0) fState = this->setUp() ? 1 : -1;
    if (fState < 0) return nullptr;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    if (IsRgb(desc.format)) return nullptr;
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = fCreateImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, fClientBuffer(buffer), attributes);
    if (image == EGL_NO_IMAGE_KHR) return nullptr;
    const int width = (int)desc.width;
    const int height = (int)desc.height;
    GLuint target = 0;
    glGenTextures(1, &target);
    glBindTexture(GL_TEXTURE_2D, target);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    glBindFramebuffer(GL_FRAMEBUFFER, fFramebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
    bool drawn = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (drawn) {
        const float kr = colorStandard == 1 ? 0.2126f : colorStandard == 6 ? 0.2627f : 0.299f;
        const float kb = colorStandard == 1 ? 0.0722f : colorStandard == 6 ? 0.0593f : 0.114f;
        const float kg = 1 - kr - kb;
        const bool limited = colorRange != 1;
        const float ys = limited ? 255.f / 219 : 1.f;
        const float cs = limited ? 255.f / 224 : 1.f;
        const float matrix[9] = {ys, ys, ys,
                                 0, -2 * kb * (1 - kb) / kg * cs, 2 * (1 - kb) * cs,
                                 2 * (1 - kr) * cs, -2 * kr * (1 - kr) / kg * cs, 0};
        static const GLfloat quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glViewport(0, 0, width, height);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CULL_FACE);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glUseProgram(fProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, fExternal);
        fTargetTexture(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)image);
        glUniform1i(glGetUniformLocation(fProgram, "frame"), 0);
        glUniformMatrix3fv(fMatrix, 1, GL_FALSE, matrix);
        glUniform3f(fOffset, limited ? 16.f / 255 : 0.f, 128.f / 255, 128.f / 255);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
        glEnableVertexAttribArray(0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
    } else {
        LOGE("YUV frame buffer incomplete");
    }
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    fDestroyImage(display, image);
    context->resetContext();
    if (!drawn) {
        glDeleteTextures(1, &target);
        return nullptr;
    }
    GrGLTextureInfo info;
    info.fTarget = GL_TEXTURE_2D;
    info.fID = target;
    info.fFormat = GL_RGBA8;
    auto frame = SkImages::AdoptTextureFrom(context, GrBackendTextures::MakeGL(width, height, skgpu::Mipmapped::kNo, info),
                                             kTopLeft_GrSurfaceOrigin, kRGBA_8888_SkColorType, alpha, SkColorSpace::MakeSRGB());
    if (!frame) glDeleteTextures(1, &target);
    return frame;
}
