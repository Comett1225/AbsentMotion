#pragma once

#include <clocale>
#if defined(__APPLE__)
#include <xlocale.h>
#endif

namespace am {

inline void CNumbers() {
#if defined(_WIN32)
    thread_local const bool pinned = _configthreadlocale(_ENABLE_PER_THREAD_LOCALE) != -1 && setlocale(LC_NUMERIC, "C");
    (void)pinned;
#elif !defined(__ANDROID__)
    static const locale_t c = newlocale(LC_NUMERIC_MASK, "C", duplocale(LC_GLOBAL_LOCALE));
    thread_local const bool pinned = c && uselocale(c);
    (void)pinned;
#endif
}

}
