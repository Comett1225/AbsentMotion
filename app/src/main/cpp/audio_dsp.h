#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace dsp {

namespace detail {

struct Ramp {
    double value = 0.0;
    double target = 0.0;
    double step = 0.0;
    int remaining = 0;
    void reset(double next) noexcept;
    void set(double next, int samples) noexcept;
    double next() noexcept;
};

enum class Shape { LowShelf, Peak, HighShelf, LowPass, HighPass };

struct Biquad {
    std::array<Ramp, 5> coefficients;
    std::array<std::array<double, 4>, 2> history{};
    std::array<double, 5> key{};
    bool configured = false;
    void set(Shape shape, double sampleRate, double frequency, double gain, double q, int samples) noexcept;
    void clear() noexcept;
    void tick() noexcept;
    double run(double input, int channel) noexcept;
};

struct StereoBuffer {
    std::vector<std::array<double, 2>> data;
    std::size_t position = 0;
    void prepare(std::size_t frames);
    void clear() noexcept;
    void write(double left, double right) noexcept;
    double read(double delay, int channel) const noexcept;
    void advance() noexcept;
};

struct Comb {
    std::vector<double> data;
    std::size_t position = 0;
    double low = 0.0;
    void clear() noexcept;
    double run(double input, double feedback, double damping) noexcept;
};

struct Allpass {
    std::vector<double> data;
    std::size_t position = 0;
    void clear() noexcept;
    double run(double input) noexcept;
};

}

struct Equalizer {
    struct Band {
        float frequency = 1000.0f;
        float gainDb = 0.0f;
        float q = 0.70710678f;
    };
    struct Params {
        Band lowShelf{120.0f, 0.0f, 0.70710678f};
        std::array<Band, 3> peaks{{{400.0f, 0.0f, 1.0f}, {1500.0f, 0.0f, 1.0f}, {5000.0f, 0.0f, 1.0f}}};
        Band highShelf{10000.0f, 0.0f, 0.70710678f};
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    std::array<detail::Biquad, 5> filters_;
    void configure(int samples) noexcept;
};

struct Filters {
    struct Params {
        bool lowPass = true;
        bool highPass = false;
        float lowPassHz = 18000.0f;
        float highPassHz = 20.0f;
        float resonance = 0.70710678f;
        int slope = 12;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    std::array<detail::Biquad, 2> low_;
    std::array<detail::Biquad, 2> high_;
    detail::Ramp lowMix_, highMix_, secondMix_;
    void configure(int samples) noexcept;
};

struct Reverb {
    struct Params {
        float roomSize = 0.5f;
        float damping = 0.5f;
        float wet = 0.3f;
        float dry = 1.0f;
        float width = 1.0f;
        float preDelayMs = 0.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    bool prepared_ = false;
    std::array<std::array<detail::Comb, 8>, 2> combs_;
    std::array<std::array<detail::Allpass, 4>, 2> allpasses_;
    detail::StereoBuffer preDelay_;
    std::array<detail::Ramp, 6> controls_;
    void configure(int samples) noexcept;
};

struct Delay {
    struct Params {
        float timeMs = 250.0f;
        float feedback = 0.35f;
        float mix = 0.3f;
        bool pingPong = false;
        float lowCutHz = 80.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    bool prepared_ = false;
    detail::StereoBuffer buffer_;
    std::array<double, 2> low_{};
    std::array<detail::Ramp, 6> controls_;
    double cutoffKey_ = -1.0;
    void configure(int samples) noexcept;
};

struct Compressor {
    struct Params {
        float thresholdDb = -18.0f;
        float ratio = 4.0f;
        float attackMs = 10.0f;
        float releaseMs = 100.0f;
        float kneeDb = 6.0f;
        float makeupDb = 0.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    double envelope_ = 0.0;
    std::array<detail::Ramp, 6> controls_;
    std::array<double, 2> timeKeys_{{-1.0, -1.0}};
    void configure(int samples) noexcept;
};

struct NoiseGate {
    struct Params {
        float thresholdDb = -50.0f;
        float attackMs = 5.0f;
        float holdMs = 50.0f;
        float releaseMs = 100.0f;
        float rangeDb = 80.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    double gain_ = 0.0;
    int holdRemaining_ = 0;
    std::array<detail::Ramp, 5> controls_;
    std::array<double, 2> timeKeys_{{-1.0, -1.0}};
    void configure(int samples) noexcept;
};

struct PitchShifter {
    struct Params {
        float semitones = 0.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    struct Head {
        double position = 0.0;
        int phase = 0;
    };
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    bool prepared_ = false;
    int grain_ = 0;
    int search_ = 0;
    int window_ = 0;
    std::size_t mask_ = 0;
    std::int64_t written_ = 0;
    std::vector<std::array<float, 2>> ring_;
    std::vector<float> mono_;
    std::vector<float> shape_;
    std::array<Head, 2> heads_;
    detail::Ramp ratio_;
    void configure(int samples) noexcept;
    void restart(Head& head, const Head& other) noexcept;
    double read(double position, std::size_t channel) const noexcept;
};

struct Limiter {
    struct Params {
        float lookaheadMs = 3.0f;
        float ceilingDb = -1.0f;
        float releaseMs = 100.0f;
    } params;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;
    int latencyFrames() const noexcept;

private:
    double sampleRate_ = 48000.0;
    int rampSamples_ = 720;
    bool prepared_ = false;
    double gain_ = 1.0;
    int holdRemaining_ = 0;
    detail::StereoBuffer buffer_;
    std::vector<double> gains_;
    std::size_t gainPosition_ = 0;
    double gainSum_ = 0.0;
    int boxWidth_ = 0;
    int sinceExact_ = 0;
    std::array<std::array<double, 4>, 2> history_{};
    std::array<detail::Ramp, 3> controls_;
    double releaseKey_ = -1.0;
    void configure(int samples) noexcept;
};

struct Chain {
    static constexpr std::size_t capacity = 16;
    struct Slot {
        float mix = 1.0f;
        bool bypass = false;

    private:
        friend struct Chain;
        void* effect = nullptr;
        void (*prepareEffect)(void*, double, int) = nullptr;
        void (*resetEffect)(void*) = nullptr;
        void (*processEffect)(void*, float*, int) = nullptr;
        detail::Ramp amount;
    };

    template <typename Effect>
    bool add(Effect& effect, float mix = 1.0f, bool bypass = false) noexcept {
        if (count_ == capacity || prepared_) return false;
        for (std::size_t i = 0; i < count_; ++i) if (slots_[i].effect == &effect) return false;
        Slot& slot = slots_[count_++];
        slot.effect = &effect;
        slot.mix = mix;
        slot.bypass = bypass;
        slot.prepareEffect = [](void* object, double rate, int block) { static_cast<Effect*>(object)->prepare(rate, block); };
        slot.resetEffect = [](void* object) { static_cast<Effect*>(object)->reset(); };
        slot.processEffect = [](void* object, float* data, int frames) { static_cast<Effect*>(object)->process(data, frames); };
        return true;
    }

    Slot* at(std::size_t index) noexcept;
    std::size_t size() const noexcept;
    void clear() noexcept;
    void prepare(double sampleRate, int maxBlock);
    void reset() noexcept;
    void process(float* data, int frames) noexcept;

private:
    std::array<Slot, capacity> slots_{};
    std::size_t count_ = 0;
    int maxBlock_ = 4096;
    int rampSamples_ = 720;
    bool prepared_ = false;
    std::vector<float> dry_;
};

}
