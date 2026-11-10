#pragma once

#include <windows.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>

namespace am {

enum class Look { Working, Done, Failed, Question };

struct Screen {
    std::wstring headline;
    std::wstring detail;
    std::wstring primary;
    std::wstring secondary;
    Look look = Look::Working;
    bool progress = false;
    bool closable = true;
};

class Shared {
public:
    void Show(const Screen& s) {
        std::lock_guard<std::mutex> l(m_);
        screen_ = s;
    }
    Screen Current() {
        std::lock_guard<std::mutex> l(m_);
        return screen_;
    }
    void Progress(uint64_t done, uint64_t total) {
        total_ = total;
        done_ = done;
    }
    uint64_t Done() const { return done_; }
    uint64_t Total() const { return total_; }
    double Fraction() const {
        uint64_t t = total_;
        return t ? (double)done_ / (double)t : 0.0;
    }
    void Choose(int c) {
        {
            std::lock_guard<std::mutex> l(m_);
            choice_ = c;
        }
        cv_.notify_all();
    }
    int WaitChoice() {
        std::unique_lock<std::mutex> l(m_);
        cv_.wait(l, [&] { return choice_ != 0; });
        int c = choice_;
        choice_ = 0;
        return c;
    }
    void RequestClose() {
        close_ = true;
        Choose(3);
    }
    bool CloseRequested() const { return close_; }
    void Quit() { quit_ = true; }
    bool Quitting() const { return quit_; }

    std::wstring title;
    std::wstring version;

private:
    std::mutex m_;
    std::condition_variable cv_;
    Screen screen_;
    int choice_ = 0;
    std::atomic<uint64_t> done_{0};
    std::atomic<uint64_t> total_{0};
    std::atomic<bool> close_{false};
    std::atomic<bool> quit_{false};
};

int RunWindow(HINSTANCE instance, Shared& shared);

}
