#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct ANativeWindow;
struct AHardwareBuffer;

namespace am {

enum class Backend : int { kGraphite = 0, kGanesh = 1 };

struct SnapGuide {
    bool vertical;
    float at;
    bool wide;
};

struct PreviewOptions {
    bool pixels = false;
    int pixelDivisor = 1;
    float gridSpacing = 0;
    int solo = 0;
    std::vector<int64_t> ids;
    std::vector<SnapGuide> guides;
    float refPx = 0;
};

struct Target;

struct InterpolationParams {
    int mode, divisor, levels, iterations, warps;
    float alpha;
};

class Engine {
public:
    static std::unique_ptr<Engine> Make(Backend backend, std::string* error);
    virtual ~Engine() = default;

    virtual std::string info() const = 0;
    virtual bool failed() const = 0;

    virtual Target* attach(ANativeWindow* window, bool forEncoder) = 0;
    virtual void detach(Target* target) = 0;

    virtual std::string timing(Target* target) = 0;

    virtual bool setScene(const std::string& xml, std::string* error) = 0;

    virtual std::string loadEffect(const std::string& xml) = 0;

    virtual int maxPictureSize() const = 0;
    virtual bool setPicture(const std::string& uri, int64_t layerId, AHardwareBuffer* buffer,
                            const void* pixels, int width, int height, size_t rowBytes, bool opaque) = 0;
    virtual bool setVideoFrame(const std::string& uri, int64_t layerId, int64_t pts, AHardwareBuffer* buffer) = 0;
    virtual bool interpolateVideo(const std::string& uri, int64_t layerId, int64_t a, int64_t b, float t,
                                  const InterpolationParams& params) = 0;

    virtual int renderScene(Target* target, double timeMs, float scale, float dx, float dy,
                            uint32_t background, int64_t selectedId, int handles, const PreviewOptions& view) = 0;

    virtual void setCameraView(bool on) = 0;

    virtual int renderFrame(Target* target, double timeMs, uint32_t background, int64_t presentTimeNs) = 0;

    virtual bool layerQuad(int64_t layerId, double timeMs, float xy[8]) = 0;

    virtual bool layerBounds(int64_t layerId, double timeMs, float ltrb[4]) = 0;

    virtual int64_t hitTest(float x, float y, double timeMs) = 0;

    virtual bool still(double timeMs, int width, int height, std::vector<uint8_t>* rgba) = 0;

    virtual bool shapeIcon(const std::string& xml, int size, uint32_t color, std::vector<uint8_t>* rgba) = 0;

    virtual bool layerOutline(int64_t layerId, double timeMs, std::string* svg) = 0;
};

}
