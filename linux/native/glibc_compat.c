#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/random.h>

#if defined(__aarch64__)
#define AM_OLD_LIBM "GLIBC_2.17"
#else
#define AM_OLD_LIBM "GLIBC_2.2.5"
#endif

#define AM_HIDDEN __attribute__((visibility("hidden")))

double am_old_fmod(double, double);
float am_old_fmodf(float, float);
__asm__(".symver am_old_fmod,fmod@" AM_OLD_LIBM);
__asm__(".symver am_old_fmodf,fmodf@" AM_OLD_LIBM);

AM_HIDDEN double __wrap_fmod(double x, double y) { return am_old_fmod(x, y); }
AM_HIDDEN float __wrap_fmodf(float x, float y) { return am_old_fmodf(x, y); }
AM_HIDDEN unsigned int arc4random(void) {
    unsigned int value = 0;
    while (getrandom(&value, sizeof value, 0) != sizeof value) {}
    return value;
}

AM_HIDDEN long __isoc23_strtol(const char* s, char** end, int base) { return strtol(s, end, base); }
AM_HIDDEN long long __isoc23_strtoll(const char* s, char** end, int base) { return strtoll(s, end, base); }
AM_HIDDEN unsigned long __isoc23_strtoul(const char* s, char** end, int base) { return strtoul(s, end, base); }
AM_HIDDEN unsigned long long __isoc23_strtoull(const char* s, char** end, int base) { return strtoull(s, end, base); }

AM_HIDDEN int __isoc23_sscanf(const char* s, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int n = vsscanf(s, format, args);
    va_end(args);
    return n;
}

AM_HIDDEN int __isoc23_fscanf(FILE* file, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int n = vfscanf(file, format, args);
    va_end(args);
    return n;
}
