#include "ui.h"

#include <d2d1.h>
#include <dwmapi.h>
#include <dwrite.h>
#include <shobjidl.h>
#include <windowsx.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cwchar>

namespace am {
namespace {

constexpr float kWidth = 560.f;
constexpr float kHeight = 340.f;
constexpr float kPad = 40.f;
constexpr UINT_PTR kTimer = 1;
constexpr wchar_t kClass[] = L"AbsentMotionSetupWindow";

D2D1_COLOR_F Color(uint32_t rgb, float a = 1.f) {
    return D2D1::ColorF((float)((rgb >> 16) & 255) / 255.f, (float)((rgb >> 8) & 255) / 255.f, (float)(rgb & 255) / 255.f, a);
}

template <class T>
void Drop(T*& p) {
    if (p) {
        p->Release();
        p = nullptr;
    }
}

bool Inside(const D2D1_RECT_F& r, float x, float y) { return x >= r.left && x < r.right && y >= r.top && y < r.bottom; }

std::wstring Family(IDWriteFactory* f) {
    IDWriteFontCollection* c = nullptr;
    std::wstring name = L"Segoe UI";
    if (SUCCEEDED(f->GetSystemFontCollection(&c))) {
        UINT32 i = 0;
        BOOL found = FALSE;
        if (SUCCEEDED(c->FindFamilyName(L"Segoe UI Variable Display", &i, &found)) && found) name = L"Segoe UI Variable Display";
        c->Release();
    }
    return name;
}

class Window {
public:
    explicit Window(Shared& s) : s_(s) {}
    ~Window() {
        Drop(taskbar_);
        Drop(glow_);
        Drop(fill_);
        Drop(sheenBrush_);
        Drop(brush_);
        Drop(target_);
        Drop(arcs_);
        Drop(round_);
        Drop(titleFont_);
        Drop(versionFont_);
        Drop(headlineFont_);
        Drop(detailFont_);
        Drop(buttonFont_);
        Drop(percentFont_);
        Drop(write_);
        Drop(factory_);
    }

    int Run(HINSTANCE instance) {
        if (!Resources()) return 1;
        WNDCLASSEXW wc{sizeof wc};
        wc.style = CS_HREDRAW | CS_VREDRAW;
        wc.lpfnWndProc = Proc;
        wc.hInstance = instance;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
        wc.hIconSm = (HICON)LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0);
        wc.lpszClassName = kClass;
        RegisterClassExW(&wc);
        taskbarCreated_ = RegisterWindowMessageW(L"TaskbarButtonCreated");
        CreateWindowExW(WS_EX_APPWINDOW, kClass, s_.title.c_str(), WS_POPUP | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_THICKFRAME, CW_USEDEFAULT,
                        CW_USEDEFAULT, 560, 340, nullptr, nullptr, instance, this);
        if (!hwnd_) return 1;
        BOOL dark = TRUE;
        DwmSetWindowAttribute(hwnd_, 20, &dark, sizeof dark);
        int corner = 2;
        DwmSetWindowAttribute(hwnd_, 33, &corner, sizeof corner);
        COLORREF border = RGB(0x2B, 0x2E, 0x36);
        DwmSetWindowAttribute(hwnd_, 34, &border, sizeof border);
        dpi_ = GetDpiForWindow(hwnd_);
        SetWindowPos(hwnd_, nullptr, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        Place();
        last_ = std::chrono::steady_clock::now();
        screen_ = s_.Current();
        ShowWindow(hwnd_, SW_SHOW);
        SetForegroundWindow(hwnd_);
        SetTimer(hwnd_, kTimer, 15, nullptr);
        MSG msg;
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        return 0;
    }

private:
    static LRESULT CALLBACK Proc(HWND h, UINT m, WPARAM w, LPARAM l) {
        Window* self = reinterpret_cast<Window*>(GetWindowLongPtrW(h, GWLP_USERDATA));
        if (m == WM_NCCREATE) {
            self = static_cast<Window*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);
            SetWindowLongPtrW(h, GWLP_USERDATA, (LONG_PTR)self);
            self->hwnd_ = h;
        }
        return self ? self->Handle(m, w, l) : DefWindowProcW(h, m, w, l);
    }

    LRESULT Handle(UINT m, WPARAM w, LPARAM l) {
        switch (m) {
        case WM_NCCALCSIZE:
            return 0;
        case WM_NCACTIVATE:
            return DefWindowProcW(hwnd_, m, w, -1);
        case WM_NCHITTEST: {
            POINT p{GET_X_LPARAM(l), GET_Y_LPARAM(l)};
            ScreenToClient(hwnd_, &p);
            RECT rc;
            GetClientRect(hwnd_, &rc);
            if (p.x < 0 || p.y < 0 || p.x >= rc.right || p.y >= rc.bottom) return HTNOWHERE;
            return Hit(Dip(p.x), Dip(p.y)) ? HTCLIENT : HTCAPTION;
        }
        case WM_SIZE:
            if (target_) target_->Resize(D2D1::SizeU(LOWORD(l), HIWORD(l)));
            return 0;
        case WM_DPICHANGED: {
            dpi_ = HIWORD(w);
            const RECT* r = reinterpret_cast<const RECT*>(l);
            SetWindowPos(hwnd_, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
            if (target_) target_->SetDpi((float)dpi_, (float)dpi_);
            return 0;
        }
        case WM_TIMER:
            Tick();
            return 0;
        case WM_PAINT: {
            PAINTSTRUCT ps;
            BeginPaint(hwnd_, &ps);
            Paint();
            EndPaint(hwnd_, &ps);
            return 0;
        }
        case WM_ERASEBKGND:
            return 1;
        case WM_MOUSEMOVE: {
            hover_ = Hit(Dip(GET_X_LPARAM(l)), Dip(GET_Y_LPARAM(l)));
            TRACKMOUSEEVENT t{sizeof t, TME_LEAVE, hwnd_, 0};
            TrackMouseEvent(&t);
            SetCursor(LoadCursorW(nullptr, hover_ > 1 ? IDC_HAND : IDC_ARROW));
            return 0;
        }
        case WM_SETCURSOR:
            if (LOWORD(l) == HTCLIENT) {
                SetCursor(LoadCursorW(nullptr, hover_ > 1 ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_MOUSELEAVE:
            hover_ = 0;
            return 0;
        case WM_LBUTTONDOWN:
            pressed_ = Hit(Dip(GET_X_LPARAM(l)), Dip(GET_Y_LPARAM(l)));
            if (pressed_) SetCapture(hwnd_);
            return 0;
        case WM_LBUTTONUP: {
            int hit = Hit(Dip(GET_X_LPARAM(l)), Dip(GET_Y_LPARAM(l)));
            ReleaseCapture();
            if (hit && hit == pressed_) Press(hit);
            pressed_ = 0;
            return 0;
        }
        case WM_KEYDOWN:
            if (w == VK_RETURN && !screen_.primary.empty()) Press(2);
            if (w == VK_ESCAPE) Press(1);
            return 0;
        case WM_CLOSE:
            Press(1);
            return 0;
        case WM_DESTROY:
            KillTimer(hwnd_, kTimer);
            PostQuitMessage(0);
            return 0;
        default:
            if (m == taskbarCreated_ && !taskbar_) CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&taskbar_));
            break;
        }
        return DefWindowProcW(hwnd_, m, w, l);
    }

    float Dip(int px) const { return (float)px * 96.f / (float)dpi_; }

    void Place() {
        POINT p;
        GetCursorPos(&p);
        MONITORINFO mi{sizeof mi};
        GetMonitorInfoW(MonitorFromPoint(p, MONITOR_DEFAULTTOPRIMARY), &mi);
        int w = MulDiv((int)kWidth, (int)dpi_, 96), h = MulDiv((int)kHeight, (int)dpi_, 96);
        const RECT& r = mi.rcWork;
        SetWindowPos(hwnd_, nullptr, r.left + (r.right - r.left - w) / 2, r.top + (r.bottom - r.top - h) / 2, w, h, SWP_NOZORDER | SWP_NOACTIVATE);
    }

    int Hit(float x, float y) const {
        if (screen_.closable && Inside(close_, x, y)) return 1;
        if (!screen_.primary.empty() && Inside(primary_, x, y)) return 2;
        if (!screen_.secondary.empty() && Inside(secondary_, x, y)) return 3;
        return 0;
    }

    void Press(int id) {
        if (id == 1 && screen_.closable) s_.RequestClose();
        if (id == 2 && !screen_.primary.empty()) s_.Choose(1);
        if (id == 3 && !screen_.secondary.empty()) s_.Choose(2);
    }

    bool Resources() {
        if (FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, &factory_))) return false;
        if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), reinterpret_cast<IUnknown**>(&write_)))) return false;
        std::wstring family = Family(write_);
        auto font = [&](IDWriteTextFormat** out, float size, DWRITE_FONT_WEIGHT weight, DWRITE_TEXT_ALIGNMENT align) {
            write_->CreateTextFormat(family.c_str(), nullptr, weight, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, size, L"en-us", out);
            if (*out) (*out)->SetTextAlignment(align);
        };
        font(&titleFont_, 26.f, DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_TEXT_ALIGNMENT_LEADING);
        font(&versionFont_, 13.5f, DWRITE_FONT_WEIGHT_NORMAL, DWRITE_TEXT_ALIGNMENT_LEADING);
        font(&headlineFont_, 20.f, DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_TEXT_ALIGNMENT_LEADING);
        font(&detailFont_, 14.f, DWRITE_FONT_WEIGHT_NORMAL, DWRITE_TEXT_ALIGNMENT_LEADING);
        font(&buttonFont_, 14.f, DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_TEXT_ALIGNMENT_CENTER);
        font(&percentFont_, 20.f, DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_TEXT_ALIGNMENT_TRAILING);
        if (buttonFont_) buttonFont_->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER);
        D2D1_STROKE_STYLE_PROPERTIES sp = D2D1::StrokeStyleProperties(D2D1_CAP_STYLE_ROUND, D2D1_CAP_STYLE_ROUND, D2D1_CAP_STYLE_ROUND, D2D1_LINE_JOIN_ROUND);
        factory_->CreateStrokeStyle(sp, nullptr, 0, &round_);
        if (SUCCEEDED(factory_->CreatePathGeometry(&arcs_))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(arcs_->Open(&sink))) {
                auto arc = [&](float x1, float y1, float x2, float y2) {
                    sink->BeginFigure(D2D1::Point2F(x1, y1), D2D1_FIGURE_BEGIN_HOLLOW);
                    sink->AddArc(D2D1::ArcSegment(D2D1::Point2F(x2, y2), D2D1::SizeF(24.f, 24.f), 0.f, D2D1_SWEEP_DIRECTION_CLOCKWISE, D2D1_ARC_SIZE_SMALL));
                    sink->EndFigure(D2D1_FIGURE_END_OPEN);
                };
                arc(31.447f, 45.792f, 74.785f, 42.f);
                arc(44.238f, 75.925f, 30.131f, 56.509f);
                arc(64.521f, 75.571f, 54.838f, 77.985f);
                sink->Close();
                sink->Release();
            }
        }
        close_ = D2D1::RectF(kWidth - 46.f, 10.f, kWidth - 12.f, 44.f);
        primary_ = D2D1::RectF(kWidth - kPad - 132.f, kHeight - 70.f, kWidth - kPad, kHeight - 32.f);
        secondary_ = D2D1::RectF(primary_.left - 124.f, primary_.top, primary_.left - 12.f, primary_.bottom);
        return titleFont_ && headlineFont_ && detailFont_;
    }

    bool Target() {
        RECT rc;
        GetClientRect(hwnd_, &rc);
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_DEFAULT,
                                                                           D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), (float)dpi_, (float)dpi_);
        if (FAILED(factory_->CreateHwndRenderTarget(props, D2D1::HwndRenderTargetProperties(hwnd_, D2D1::SizeU((UINT32)rc.right, (UINT32)rc.bottom)), &target_)))
            return false;
        target_->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        target_->CreateSolidColorBrush(Color(0xFFFFFF), &brush_);
        auto stops = [&](std::initializer_list<D2D1_GRADIENT_STOP> list) {
            ID2D1GradientStopCollection* c = nullptr;
            target_->CreateGradientStopCollection(list.begin(), (UINT32)list.size(), &c);
            return c;
        };
        ID2D1GradientStopCollection* g = stops({{0.f, Color(0xFF5A36, 0.26f)}, {1.f, Color(0xFF5A36, 0.f)}});
        if (g) {
            target_->CreateRadialGradientBrush(D2D1::RadialGradientBrushProperties(D2D1::Point2F(90.f, 50.f), D2D1::Point2F(0, 0), 360.f, 260.f), g, &glow_);
            g->Release();
        }
        g = stops({{0.f, Color(0xFF9452)}, {1.f, Color(0xFF5A36)}});
        if (g) {
            target_->CreateLinearGradientBrush(D2D1::LinearGradientBrushProperties(D2D1::Point2F(kPad, 0), D2D1::Point2F(kWidth - kPad, 0)), g, &fill_);
            g->Release();
        }
        g = stops({{0.f, Color(0xFFFFFF, 0.f)}, {0.5f, Color(0xFFFFFF, 0.42f)}, {1.f, Color(0xFFFFFF, 0.f)}});
        if (g) {
            target_->CreateLinearGradientBrush(D2D1::LinearGradientBrushProperties(D2D1::Point2F(0, 0), D2D1::Point2F(90.f, 0)), g, &sheenBrush_);
            g->Release();
        }
        return brush_ && glow_ && fill_ && sheenBrush_;
    }

    void Tick() {
        auto now = std::chrono::steady_clock::now();
        double dt = std::min(0.1, std::chrono::duration<double>(now - last_).count());
        last_ = now;
        screen_ = s_.Current();
        bool working = screen_.look == Look::Working;
        if (working) {
            spin_ = std::fmod(spin_ + dt * 210.0, 360.0);
        } else {
            double goal = spin_ > 180.0 ? 360.0 : 0.0;
            spin_ += (goal - spin_) * std::min(1.0, dt * 7.0);
        }
        double want = screen_.look == Look::Done ? 1.0 : s_.Fraction();
        if (want + 0.25 < shown_) shown_ = want;
        shown_ += (want - shown_) * std::min(1.0, dt * 7.0);
        sheen_ = std::fmod(sheen_ + dt * 0.55, 1.5);
        if (taskbar_) {
            int state = screen_.look == Look::Failed ? 2 : (screen_.progress && working ? 1 : 0);
            int value = (int)(shown_ * 1000.0);
            if (state != barState_ || (state == 1 && value != barValue_)) {
                taskbar_->SetProgressState(hwnd_, state == 2 ? TBPF_ERROR : (state == 1 ? TBPF_NORMAL : TBPF_NOPROGRESS));
                if (state == 1) taskbar_->SetProgressValue(hwnd_, (ULONGLONG)value, 1000);
                barState_ = state;
                barValue_ = value;
            }
        }
        if (s_.Quitting()) {
            DestroyWindow(hwnd_);
            return;
        }
        InvalidateRect(hwnd_, nullptr, FALSE);
    }

    void Text(const std::wstring& t, IDWriteTextFormat* f, const D2D1_RECT_F& r, uint32_t rgb, float a = 1.f) {
        if (t.empty() || !f) return;
        brush_->SetColor(Color(rgb, a));
        target_->DrawTextW(t.c_str(), (UINT32)t.size(), f, r, brush_, D2D1_DRAW_TEXT_OPTIONS_CLIP);
    }

    void Logo(float x, float y, float size) {
        float k = size / 108.f;
        brush_->SetColor(Color(0xFF5A36));
        target_->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y, x + size, y + size), 26.f * k, 26.f * k), brush_);
        D2D1::Matrix3x2F base = D2D1::Matrix3x2F::Scale(k, k) * D2D1::Matrix3x2F::Translation(x, y);
        target_->SetTransform(D2D1::Matrix3x2F::Rotation((float)spin_, D2D1::Point2F(54.f, 54.f)) * base);
        brush_->SetColor(Color(0xFFFFFF));
        if (arcs_) target_->DrawGeometry(arcs_, brush_, 7.f, round_);
        target_->FillEllipse(D2D1::Ellipse(D2D1::Point2F(72.912f, 68.776f), 3.5f, 3.5f), brush_);
        target_->SetTransform(base);
        target_->FillEllipse(D2D1::Ellipse(D2D1::Point2F(54.f, 54.f), 7.f, 7.f), brush_);
        target_->SetTransform(D2D1::Matrix3x2F::Identity());
    }

    void Button(const D2D1_RECT_F& r, const std::wstring& label, bool primary, int id) {
        bool hot = hover_ == id;
        bool down = pressed_ == id && hot;
        uint32_t fill = primary ? (down ? 0xE64A2A : (hot ? 0xFF6D4C : 0xFF5A36)) : (down ? 0x23262D : (hot ? 0x343842 : 0x2A2D35));
        brush_->SetColor(Color(fill));
        target_->FillRoundedRectangle(D2D1::RoundedRect(r, 8.f, 8.f), brush_);
        Text(label, buttonFont_, r, primary ? 0xFFFFFF : 0xE6E8EC);
    }

    void Paint() {
        if (!target_ && !Target()) return;
        target_->BeginDraw();
        target_->SetTransform(D2D1::Matrix3x2F::Identity());
        target_->Clear(Color(0x121419));
        if (glow_) target_->FillRectangle(D2D1::RectF(0, 0, kWidth, kHeight), glow_);
        if (screen_.closable) {
            if (hover_ == 1) {
                brush_->SetColor(Color(0xFFFFFF, 0.08f));
                target_->FillRoundedRectangle(D2D1::RoundedRect(close_, 6.f, 6.f), brush_);
            }
            brush_->SetColor(Color(hover_ == 1 ? 0xFFFFFF : 0x8B919C));
            float cx = (close_.left + close_.right) / 2.f, cy = (close_.top + close_.bottom) / 2.f, d = 5.5f;
            target_->DrawLine(D2D1::Point2F(cx - d, cy - d), D2D1::Point2F(cx + d, cy + d), brush_, 1.4f, round_);
            target_->DrawLine(D2D1::Point2F(cx + d, cy - d), D2D1::Point2F(cx - d, cy + d), brush_, 1.4f, round_);
        }
        Logo(kPad, 40.f, 60.f);
        Text(L"AbsentMotion", titleFont_, D2D1::RectF(kPad + 78.f, 40.f, kWidth - 60.f, 76.f), 0xF4F4F5);
        Text(s_.version, versionFont_, D2D1::RectF(kPad + 79.f, 76.f, kWidth - 60.f, 100.f), 0x8B919C);
        uint32_t headColor = screen_.look == Look::Failed ? 0xFF7B7B : 0xFFFFFF;
        bool bar = screen_.progress || screen_.look == Look::Done;
        float right = screen_.progress ? kWidth - kPad - 70.f : kWidth - kPad;
        Text(screen_.headline, headlineFont_, D2D1::RectF(kPad, 150.f, right, 182.f), headColor);
        std::wstring detail = screen_.detail;
        if (detail.empty() && screen_.progress && s_.Total() > 0) {
            wchar_t b[96];
            swprintf_s(b, L"%.0f MB of %.0f MB", (double)s_.Done() / 1048576.0, (double)s_.Total() / 1048576.0);
            detail = b;
        }
        Text(detail, detailFont_, D2D1::RectF(kPad, 184.f, kWidth - kPad, 236.f), 0xA3A9B4);
        if (screen_.progress) {
            wchar_t pct[16];
            swprintf_s(pct, L"%d%%", (int)std::lround(std::clamp(shown_, 0.0, 1.0) * 100.0));
            Text(pct, percentFont_, D2D1::RectF(kWidth - kPad - 90.f, 150.f, kWidth - kPad, 182.f), 0xFFFFFF, 0.92f);
        }
        if (bar) {
            D2D1_RECT_F track = D2D1::RectF(kPad, 242.f, kWidth - kPad, 250.f);
            brush_->SetColor(Color(0x262931));
            target_->FillRoundedRectangle(D2D1::RoundedRect(track, 4.f, 4.f), brush_);
            float w = (float)std::clamp(shown_, 0.0, 1.0) * (track.right - track.left);
            if (w > 1.f) {
                D2D1_RECT_F fill = D2D1::RectF(track.left, track.top, track.left + std::max(w, 8.f), track.bottom);
                target_->FillRoundedRectangle(D2D1::RoundedRect(fill, 4.f, 4.f), fill_);
                if (screen_.look == Look::Working) {
                    float x = track.left + (float)(sheen_ - 0.25) * (track.right - track.left);
                    sheenBrush_->SetStartPoint(D2D1::Point2F(x - 45.f, 0));
                    sheenBrush_->SetEndPoint(D2D1::Point2F(x + 45.f, 0));
                    target_->PushAxisAlignedClip(fill, D2D1_ANTIALIAS_MODE_PER_PRIMITIVE);
                    target_->FillRectangle(fill, sheenBrush_);
                    target_->PopAxisAlignedClip();
                }
            }
        }
        bool buttons = !screen_.primary.empty();
        if (buttons) Button(primary_, screen_.primary, true, 2);
        if (!screen_.secondary.empty()) Button(secondary_, screen_.secondary, false, 3);
        if (target_->EndDraw() == D2DERR_RECREATE_TARGET) {
            Drop(glow_);
            Drop(fill_);
            Drop(sheenBrush_);
            Drop(brush_);
            Drop(target_);
        }
    }

    Shared& s_;
    HWND hwnd_ = nullptr;
    UINT dpi_ = 96;
    UINT taskbarCreated_ = 0;
    ID2D1Factory* factory_ = nullptr;
    IDWriteFactory* write_ = nullptr;
    ID2D1HwndRenderTarget* target_ = nullptr;
    ID2D1SolidColorBrush* brush_ = nullptr;
    ID2D1RadialGradientBrush* glow_ = nullptr;
    ID2D1LinearGradientBrush* fill_ = nullptr;
    ID2D1LinearGradientBrush* sheenBrush_ = nullptr;
    ID2D1PathGeometry* arcs_ = nullptr;
    ID2D1StrokeStyle* round_ = nullptr;
    IDWriteTextFormat* titleFont_ = nullptr;
    IDWriteTextFormat* versionFont_ = nullptr;
    IDWriteTextFormat* headlineFont_ = nullptr;
    IDWriteTextFormat* detailFont_ = nullptr;
    IDWriteTextFormat* buttonFont_ = nullptr;
    IDWriteTextFormat* percentFont_ = nullptr;
    ITaskbarList3* taskbar_ = nullptr;
    std::chrono::steady_clock::time_point last_{};
    double spin_ = 0, sheen_ = 0, shown_ = 0;
    int hover_ = 0, pressed_ = 0, barState_ = -1, barValue_ = -1;
    D2D1_RECT_F close_{}, primary_{}, secondary_{};
    Screen screen_;
};

}

int RunWindow(HINSTANCE instance, Shared& shared) {
    Window w(shared);
    return w.Run(instance);
}

}
