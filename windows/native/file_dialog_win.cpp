#include <jni.h>
#include <windows.h>
#include <shobjidl.h>
#include <algorithm>
#include <string>
#include <vector>

static HWND centreOwner;
static HHOOK centreHook;

static void centre(HWND dialog, HWND owner) {
    RECT o, d;
    MONITORINFO info = {sizeof(info)};
    if (!GetWindowRect(owner, &o) || !GetWindowRect(dialog, &d) || !GetMonitorInfoW(MonitorFromWindow(owner, MONITOR_DEFAULTTONEAREST), &info)) return;
    int w = d.right - d.left, h = d.bottom - d.top;
    RECT a = info.rcWork;
    int x = std::max(a.left, std::min<LONG>(o.left + (o.right - o.left - w) / 2, a.right - w));
    int y = std::max(a.top, std::min<LONG>(o.top + (o.bottom - o.top - h) / 2, a.bottom - h));
    SetWindowPos(dialog, nullptr, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
}

static LRESULT CALLBACK onShow(int code, WPARAM w, LPARAM l) {
    CWPRETSTRUCT* m = reinterpret_cast<CWPRETSTRUCT*>(l);
    if (code == HC_ACTION && centreHook && m->message == WM_SHOWWINDOW && m->wParam && GetWindow(m->hwnd, GW_OWNER) == centreOwner) {
        UnhookWindowsHookEx(centreHook);
        centreHook = nullptr;
        centre(m->hwnd, centreOwner);
    }
    return CallNextHookEx(nullptr, code, w, l);
}

static std::wstring wide(JNIEnv* e, jstring s) {
    if (!s) return {};
    const jchar* chars = e->GetStringChars(s, nullptr);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), (size_t)e->GetStringLength(s));
    e->ReleaseStringChars(s, chars);
    return result;
}

static void add(std::vector<std::wstring>& out, IShellItem* item) {
    PWSTR path = nullptr;
    if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path)) && path) {
        out.emplace_back(path);
        CoTaskMemFree(path);
    }
}

static bool show(HWND owner, int mode, bool multiple, const std::wstring& title, const std::wstring& name, const std::vector<std::wstring>& filters, std::vector<std::wstring>& paths) {
    IFileDialog* dialog = nullptr;
    if (FAILED(CoCreateInstance(mode == 2 ? CLSID_FileSaveDialog : CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog)))) return false;
    FILEOPENDIALOGOPTIONS options = 0;
    dialog->GetOptions(&options);
    options |= FOS_FORCEFILESYSTEM;
    if (mode == 1) options |= FOS_PICKFOLDERS;
    if (mode == 0 && multiple) options |= FOS_ALLOWMULTISELECT;
    dialog->SetOptions(options);
    if (!title.empty()) dialog->SetTitle(title.c_str());
    std::vector<COMDLG_FILTERSPEC> specs;
    for (size_t i = 0; i + 1 < filters.size(); i += 2) specs.push_back({filters[i].c_str(), filters[i + 1].c_str()});
    if (mode != 1 && !specs.empty()) {
        dialog->SetFileTypes((UINT)specs.size(), specs.data());
        dialog->SetFileTypeIndex(1);
    }
    if (mode == 2 && !name.empty()) {
        dialog->SetFileName(name.c_str());
        size_t dot = name.find_last_of(L'.');
        if (dot != std::wstring::npos && dot + 1 < name.size()) dialog->SetDefaultExtension(name.c_str() + dot + 1);
    }
    centreOwner = owner;
    centreHook = owner ? SetWindowsHookExW(WH_CALLWNDPROCRET, onShow, nullptr, GetCurrentThreadId()) : nullptr;
    HRESULT shown = dialog->Show(owner);
    if (centreHook) UnhookWindowsHookEx(centreHook);
    centreHook = nullptr;
    if (SUCCEEDED(shown) && mode == 0 && multiple) {
        IFileOpenDialog* open = nullptr;
        IShellItemArray* items = nullptr;
        if (SUCCEEDED(dialog->QueryInterface(IID_PPV_ARGS(&open))) && SUCCEEDED(open->GetResults(&items))) {
            DWORD count = 0;
            items->GetCount(&count);
            for (DWORD i = 0; i < count; i++) {
                IShellItem* item = nullptr;
                if (SUCCEEDED(items->GetItemAt(i, &item))) {
                    add(paths, item);
                    item->Release();
                }
            }
        }
        if (items) items->Release();
        if (open) open->Release();
    } else if (SUCCEEDED(shown)) {
        IShellItem* item = nullptr;
        if (SUCCEEDED(dialog->GetResult(&item))) {
            add(paths, item);
            item->Release();
        }
    }
    dialog->Release();
    return SUCCEEDED(shown) || shown == HRESULT_FROM_WIN32(ERROR_CANCELLED);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_absentmotion_pc_engine_DesktopNative_pickFiles(JNIEnv* e, jobject, jlong owner, jint mode, jboolean multiple, jstring title, jstring name, jobjectArray filters) {
    std::vector<std::wstring> specs;
    jsize count = filters ? e->GetArrayLength(filters) : 0;
    for (jsize i = 0; i < count; i++) {
        jstring s = (jstring)e->GetObjectArrayElement(filters, i);
        specs.push_back(wide(e, s));
        e->DeleteLocalRef(s);
    }
    HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    if (FAILED(com)) return nullptr;
    HWND window = owner ? GetAncestor(reinterpret_cast<HWND>(static_cast<intptr_t>(owner)), GA_ROOT) : nullptr;
    std::vector<std::wstring> paths;
    bool ok = show(window, mode, multiple == JNI_TRUE, wide(e, title), wide(e, name), specs, paths);
    CoUninitialize();
    if (!ok) return nullptr;
    jobjectArray result = e->NewObjectArray((jsize)paths.size(), e->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < paths.size(); i++) {
        jstring path = e->NewString(reinterpret_cast<const jchar*>(paths[i].c_str()), (jsize)paths[i].size());
        e->SetObjectArrayElement(result, (jsize)i, path);
        e->DeleteLocalRef(path);
    }
    return result;
}
