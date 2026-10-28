#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <utility>
#include <vector>

namespace {

int allocs = 0;
int failOn = 0;
std::map<void *, std::pair<char *, size_t>> blocks;

}

void *test_malloc(size_t bytes) {
    if (++allocs == failOn) return nullptr;
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t data = (bytes + page - 1) / page * page, total = data + page;
    char *base = static_cast<char *>(mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0));
    if (base == MAP_FAILED) return nullptr;
    mprotect(base + data, page, PROT_NONE);
    void *block = base + data - bytes;
    std::memset(block, 0xA5, bytes);
    blocks[block] = {base, total};
    return block;
}

void test_free(void *block) {
    if (!block) return;
    auto at = blocks.find(block);
    munmap(at->second.first, at->second.second);
    blocks.erase(at);
}

namespace std {
using ::test_free;
using ::test_malloc;
}

#define malloc test_malloc
#define free test_free
#include "cutout.cpp"
#undef free
#undef malloc

namespace {

int failures = 0;

void expect(bool ok, const char *what) {
    if (ok) return;
    std::printf("FAIL %s\n", what);
    failures++;
}

struct Picture {
    static constexpr int Width = 96, Height = 64, LowWidth = 48, LowHeight = 32;
    std::vector<uint8_t> rgba = std::vector<uint8_t>(static_cast<size_t>(Width) * Height * 4);
    std::vector<float> alpha = std::vector<float>(static_cast<size_t>(LowWidth) * LowHeight, 0.f);

    Picture() {
        for (size_t i = 0; i < rgba.size(); i += 4) {
            rgba[i] = 200;
            rgba[i + 1] = 120;
            rgba[i + 2] = 40;
            rgba[i + 3] = 255;
        }
        for (int y = 0; y < LowHeight; y++)
            for (int x = 0; x < LowWidth; x++)
                if ((x - 12) * (x - 12) + (y - 16) * (y - 16) <= 64) alpha[static_cast<size_t>(y) * LowWidth + x] = 1.f;
    }

    int refine() {
        std::atomic<bool> cancel{false};
        return cutout::refine(rgba.data(), static_cast<size_t>(Width) * 4, Width, Height, alpha.data(), LowWidth, LowHeight, cancel);
    }
};

}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    {
        Picture p;
        failOn = 0;
        expect(p.refine() == 0, "a soft edge that ends well before the right border refines");
        expect(p.rgba[(static_cast<size_t>(32) * Picture::Width + 24) * 4 + 3] == 255, "the subject stays opaque");
        expect(p.rgba[(static_cast<size_t>(32) * Picture::Width + 80) * 4 + 3] == 0, "the background goes clear");
    }
    for (int n = 1; n <= 4; n++) {
        allocs = 0;
        failOn = n;
        cutout::Weights weights(100, 50);
        expect(allocs >= n, "the weight table made every allocation this run planned to fail");
        expect(!weights.ok(), "a failed allocation makes the resize weights report failure");
    }
    for (int n = 1;; n++) {
        Picture p;
        allocs = 0;
        failOn = n;
        const int code = p.refine();
        const bool fired = allocs >= n;
        failOn = 0;
        if (!fired) {
            expect(code == 0, "refine succeeds when nothing fails");
            break;
        }
        if (code != -2) {
            std::printf("FAIL refine reports allocation %d failing (code %d)\n", n, code);
            failures++;
        }
    }
    {
        constexpr int size = 32;
        const cutout::Letterbox box = cutout::letterbox(Picture::Width, Picture::Height, size);
        for (int n = 1;; n++) {
            Picture p;
            std::vector<float> planes(3 * size * size);
            allocs = 0;
            failOn = n;
            const bool good = cutout::netInput(p.rgba.data(), static_cast<size_t>(Picture::Width) * 4, Picture::Width, Picture::Height, size, box, planes.data());
            const bool fired = allocs >= n;
            failOn = 0;
            if (!fired) {
                expect(good, "the model input is made when nothing fails");
                expect(std::fabs(planes[16 * size + 16] - 200.f / 255.f) < 1e-3f, "the model input carries the picture");
                break;
            }
            if (good) {
                std::printf("FAIL netInput hides allocation %d failing\n", n);
                failures++;
            }
        }
    }
    if (failures) return 1;
    std::printf("cutout alloc: all checks passed\n");
    return 0;
}
