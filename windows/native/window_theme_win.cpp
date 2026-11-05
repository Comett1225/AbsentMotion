#include <jni.h>
#include <windows.h>
#include <dwmapi.h>

extern "C" JNIEXPORT jboolean JNICALL Java_app_absentmotion_desk_DeskWindow_nativeSystemDark(JNIEnv*, jclass) {
    DWORD light = 1;
    DWORD size = sizeof(light);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", L"AppsUseLightTheme", RRF_RT_REG_DWORD, nullptr, &light, &size) != ERROR_SUCCESS) return JNI_FALSE;
    return light == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_app_absentmotion_desk_DeskWindow_nativeDarkTitleBar(JNIEnv*, jclass, jlong handle, jboolean dark) {
    HWND window = GetAncestor(reinterpret_cast<HWND>(handle), GA_ROOT);
    if (!window) return;
    BOOL value = dark ? TRUE : FALSE;
    if (FAILED(DwmSetWindowAttribute(window, 20, &value, sizeof(value)))) DwmSetWindowAttribute(window, 19, &value, sizeof(value));
    SetWindowPos(window, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
}
