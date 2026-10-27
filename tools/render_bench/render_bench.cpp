#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "effects.h"
#include "scene.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"

namespace {

std::string ReadFile(const char* path) {
    std::ifstream in(path, std::ios::binary);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

sk_sp<SkImage> FilePicture() {
    const char* path = std::getenv("RENDER_BENCH_PICTURE");
    const char* width = std::getenv("RENDER_BENCH_PICTURE_WIDTH");
    const char* height = std::getenv("RENDER_BENCH_PICTURE_HEIGHT");
    if (!path || !width || !height) return nullptr;
    const int w = std::atoi(width), h = std::atoi(height);
    const std::string bytes = ReadFile(path);
    if (w <= 0 || h <= 0 || bytes.size() != (size_t)w * h * 4) return nullptr;
    return SkImages::RasterFromData(SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB()),
                                    SkData::MakeWithCopy(bytes.data(), bytes.size()), (size_t)w * 4);
}

sk_sp<SkImage> SyntheticPicture() {
    if (sk_sp<SkImage> given = FilePicture()) return given;
    auto surface = SkSurfaces::Raster(SkImageInfo::MakeN32Premul(512, 384));
    SkCanvas* c = surface->getCanvas();
    SkPaint band;
    for (int y = 0; y < 384; y += 8) {
        band.setColor(SkColorSetARGB(255, 32 + y / 2, 64 + (y * 3) % 160, 192 - y / 3));
        c->drawRect(SkRect::MakeXYWH(0, (float)y, 512, 8), band);
    }
    SkPaint dot;
    dot.setAntiAlias(true);
    for (int i = 0; i < 24; i++) {
        dot.setColor(SkColorSetARGB(255, (i * 53) & 255, (i * 97) & 255, (i * 31) & 255));
        c->drawCircle(30.0f + (i % 6) * 90, 40.0f + (i / 6) * 95, 12.0f + (i % 5) * 6, dot);
    }
    return surface->makeImageSnapshot();
}

}

int main(int argc, char** argv) {
    if (argc < 7) {
        std::fprintf(stderr, "usage: render_bench effects.txt scene.xml scale runs outdir|- frameMs...\n");
        return 2;
    }
    am::EffectRuntime runtime;
    {
        std::ifstream lines(argv[1]);
        std::string line;
        while (std::getline(lines, line)) {
            if (line.empty()) continue;
            const std::string status = runtime.load(line);
            if (status != "ok") std::fprintf(stderr, "effect: %s\n", status.substr(0, status.find('\n')).c_str());
        }
    }
    am::Scene scene;
    std::string error;
    if (!am::ParseScene(ReadFile(argv[2]), &scene, &error)) {
        std::fprintf(stderr, "scene: %s\n", error.c_str());
        return 1;
    }
    const float scale = std::strtof(argv[3], nullptr);
    const int runs = std::atoi(argv[4]);
    const std::string out = argv[5];
    std::vector<double> frames;
    for (int i = 6; i < argc; i++) frames.push_back(std::strtod(argv[i], nullptr));
    const int width = (int)(scene.width * scale + 0.5f), height = (int)(scene.height * scale + 0.5f);
    const SkImageInfo info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    sk_sp<SkSurface> surface = SkSurfaces::Raster(info);
    const sk_sp<SkImage> picture = SyntheticPicture();
    const am::MediaPictures pictures = [&](const std::string&, int64_t) { return picture; };
    am::LayerEffects fx;
    fx.active = [&](const am::Layer& l) { return runtime.active(l); };
    fx.lifted = [&](const am::Layer& l) { return runtime.lifted(l); };
    fx.behind = [&](const am::Layer& l) { return runtime.behind(l); };
    fx.draw = [&](SkCanvas* canvas, const am::Scene& s, const am::Layer& l, double time, const SkRect& area, const SkMatrix& placed,
                  const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below) {
        return runtime.draw(canvas, s, l, time, area, placed, source, below);
    };
#ifdef RENDER_BENCH_STEADY
    fx.steady = [&](const am::Layer& l, float from, float to) { return runtime.steady(l, from, to); };
#endif
    long breaks = 0;
    const std::function<void()> each = [&] { breaks++; };
    am::SetCameraView(true);
    am::SetFastShutterBlur(false);
    std::vector<uint8_t> pixels((size_t)width * height * 4);
    for (int run = 0; run <= runs; run++) {
        std::printf("{\"run\":%d,\"ms\":[", run);
        breaks = 0;
        for (size_t f = 0; f < frames.size(); f++) {
            SkCanvas* canvas = surface->getCanvas();
            const auto start = std::chrono::steady_clock::now();
            canvas->save();
            canvas->clear(SK_ColorTRANSPARENT);
            canvas->scale((float)width / scene.width, (float)height / scene.height);
            am::SetLayerBreak(&each);
            am::DrawScene(canvas, scene, frames[f], pictures, &fx);
            am::SetLayerBreak(nullptr);
            canvas->restore();
            surface->readPixels(info, pixels.data(), (size_t)width * 4, 0, 0);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            std::printf("%s%.3f", f ? "," : "", ms);
            if (run == runs && out != "-") {
                std::ofstream o(out + "/frame" + std::to_string(f) + ".rgba", std::ios::binary);
                o.write((const char*)pixels.data(), (std::streamsize)pixels.size());
            }
        }
        std::printf("],\"breaks\":%ld,\"width\":%d,\"height\":%d}\n", breaks, width, height);
        std::fflush(stdout);
    }
    return 0;
}
