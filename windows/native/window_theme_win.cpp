#include <jni.h>
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>

static UINT menuMessage() {
    static UINT message = RegisterWindowMessageW(L"AbsentMotionSystemMenu");
    return message;
}

static void showSystemMenu(HWND window, int x, int y) {
    HMENU menu = GetSystemMenu(window, FALSE);
    if (!menu) return;
    bool zoomed = IsZoomed(window), iconic = IsIconic(window);
    LONG style = GetWindowLongW(window, GWL_STYLE);
    auto state = [menu](UINT item, bool on) {
        MENUITEMINFOW info = {sizeof(info)};
        info.fMask = MIIM_STATE;
        info.fState = on ? MFS_ENABLED : MFS_GRAYED;
        SetMenuItemInfoW(menu, item, FALSE, &info);
    };
    state(SC_RESTORE, zoomed || iconic);
    state(SC_MOVE, !zoomed && !iconic);
    state(SC_SIZE, !zoomed && !iconic && (style & WS_THICKFRAME));
    state(SC_MINIMIZE, !iconic && (style & WS_MINIMIZEBOX));
    state(SC_MAXIMIZE, !zoomed && (style & WS_MAXIMIZEBOX));
    state(SC_CLOSE, true);
    SetMenuDefaultItem(menu, SC_CLOSE, FALSE);
    UINT flags = TPM_RETURNCMD | TPM_RIGHTBUTTON | (GetSystemMetrics(SM_MENUDROPALIGNMENT) ? TPM_RIGHTALIGN : TPM_LEFTALIGN);
    int command = (int)TrackPopupMenu(menu, flags, x, y, 0, window, nullptr);
    if (command) PostMessageW(window, WM_SYSCOMMAND, (WPARAM)command, 0);
}

static LRESULT CALLBACK onMessage(int code, WPARAM removed, LPARAM data) {
    MSG* msg = reinterpret_cast<MSG*>(data);
    if (code == HC_ACTION && removed == PM_REMOVE && msg->message == menuMessage()) {
        msg->message = WM_NULL;
        showSystemMenu(msg->hwnd, GET_X_LPARAM(msg->lParam), GET_Y_LPARAM(msg->lParam));
    }
    return CallNextHookEx(nullptr, code, removed, data);
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_absentmotion_pc_engine_DesktopNative_systemMenu(JNIEnv*, jobject, jlong handle) {
    static HHOOK hook = nullptr;
    static DWORD hooked = 0;
    HWND window = GetAncestor(reinterpret_cast<HWND>(static_cast<intptr_t>(handle)), GA_ROOT);
    if (!window) return JNI_FALSE;
    DWORD thread = GetWindowThreadProcessId(window, nullptr);
    if (hooked != thread) {
        if (hook) UnhookWindowsHookEx(hook);
        hook = SetWindowsHookExW(WH_GETMESSAGE, onMessage, nullptr, thread);
        hooked = hook ? thread : 0;
    }
    if (!hook) return JNI_FALSE;
    POINT at;
    GetCursorPos(&at);
    return PostMessageW(window, menuMessage(), 0, MAKELPARAM((short)at.x, (short)at.y)) ? JNI_TRUE : JNI_FALSE;
}

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
