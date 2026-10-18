#pragma once
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdarg>
#include <cstdio>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
inline int __android_log_print(int, const char* tag, const char* format, ...) {
    std::fprintf(stderr, "%s: ", tag);
    va_list args; va_start(args, format); int n = std::vfprintf(stderr, format, args); va_end(args);
    std::fputc('\n', stderr); return n;
}
#endif
