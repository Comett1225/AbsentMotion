#include "audio_dsp.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
bool trackAllocations = false;
std::size_t allocationCount = 0;
}

void* operator new(std::size_t size) {
    if (trackAllocations) ++allocationCount;
    if (void* pointer = std::malloc(size == 0 ? 1 : size)) return pointer;
    throw std::bad_alloc();
}

void* operator new[](std::size_t size) {
    return ::operator new(size);
}

void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete[](void* pointer) noexcept { std::free(pointer); }
void operator delete(void* pointer, std::size_t) noexcept { std::free(pointer); }
void operator delete[](void* pointer, std::size_t) noexcept { std::free(pointer); }

namespace {

constexpr double pi = 3.14159265358979323846;

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

double decibels(double amplitude) {
    return 20.0 * std::log10(std::max(amplitude, 1.0e-30));
}

double gain(double db) {
    return std::pow(10.0, db / 20.0);
}

struct Random {
    std::uint32_t state = 0x873a2b91u;
    float next() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return static_cast<float>(static_cast<double>(state) / 2147483647.5 - 1.0);
    }
};

template <typename Effect>
void runBlocks(Effect& effect, std::vector<float>& data, int block) {
    const int frames = static_cast<int>(data.size() / 2);
    for (int offset = 0; offset < frames; offset += block)
        effect.process(data.data() + 2 * offset, std::min(block, frames - offset));
}

enum class Shape { LowShelf, Peak, HighShelf, LowPass, HighPass };

std::complex<double> response(Shape shape, double rate, double frequency, double db, double q, double testHz) {
    frequency = std::clamp(frequency, 20.0, std::min(20000.0, 0.45 * rate));
    const double w = 2.0 * pi * frequency / rate;
    const double c = std::cos(w), a = std::sin(w) / (2.0 * q);
    const double A = std::pow(10.0, db / 40.0), beta = 2.0 * std::sqrt(A) * a;
    double b0 = 0.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;
    if (shape == Shape::Peak) {
        b0 = 1.0 + a * A; b1 = -2.0 * c; b2 = 1.0 - a * A;
        a0 = 1.0 + a / A; a1 = -2.0 * c; a2 = 1.0 - a / A;
    } else if (shape == Shape::LowShelf) {
        b0 = A * (A + 1.0 - (A - 1.0) * c + beta);
        b1 = 2.0 * A * (A - 1.0 - (A + 1.0) * c);
        b2 = A * (A + 1.0 - (A - 1.0) * c - beta);
        a0 = A + 1.0 + (A - 1.0) * c + beta;
        a1 = -2.0 * (A - 1.0 + (A + 1.0) * c);
        a2 = A + 1.0 + (A - 1.0) * c - beta;
    } else if (shape == Shape::HighShelf) {
        b0 = A * (A + 1.0 + (A - 1.0) * c + beta);
        b1 = -2.0 * A * (A - 1.0 + (A + 1.0) * c);
        b2 = A * (A + 1.0 + (A - 1.0) * c - beta);
        a0 = A + 1.0 - (A - 1.0) * c + beta;
        a1 = 2.0 * (A - 1.0 - (A + 1.0) * c);
        a2 = A + 1.0 - (A - 1.0) * c - beta;
    } else {
        const bool low = shape == Shape::LowPass;
        b0 = b2 = (1.0 + (low ? -c : c)) / 2.0;
        b1 = low ? 1.0 - c : -(1.0 + c);
        a0 = 1.0 + a; a1 = -2.0 * c; a2 = 1.0 - a;
    }
    const auto z = std::polar(1.0, -2.0 * pi * testHz / rate);
    return (b0 + b1 * z + b2 * z * z) / (a0 + a1 * z + a2 * z * z);
}

template <typename Effect>
double sineMagnitude(Effect& effect, int rate, int frequency) {
    effect.prepare(rate, 4096);
    std::vector<float> data(static_cast<std::size_t>(4 * rate));
    for (int i = 0; i < 2 * rate; ++i) {
        const float value = static_cast<float>(0.1 * std::sin(2.0 * pi * frequency * i / rate));
        data[static_cast<std::size_t>(2 * i)] = data[static_cast<std::size_t>(2 * i + 1)] = value;
    }
    runBlocks(effect, data, 257);
    double sine = 0.0, cosine = 0.0;
    for (int i = rate; i < 2 * rate; ++i) {
        const double angle = 2.0 * pi * frequency * i / rate;
        sine += data[static_cast<std::size_t>(2 * i)] * std::sin(angle);
        cosine += data[static_cast<std::size_t>(2 * i)] * std::cos(angle);
    }
    return 2.0 * std::hypot(sine, cosine) / (rate * 0.1);
}

void testResponses() {
    double worst = 0.0;
    for (int rate : {22050, 48000, 96000}) {
        dsp::Equalizer eq;
        eq.params.lowShelf = {150.0f, 7.0f, 0.70710678f};
        eq.params.peaks = {{{400.0f, -5.0f, 1.2f}, {1700.0f, 9.0f, 2.0f}, {5200.0f, -6.0f, 0.8f}}};
        eq.params.highShelf = {8000.0f, 4.0f, 0.70710678f};
        for (int hz : {40, 150, 400, 1700, 5200, 9000}) {
            auto expected = response(Shape::LowShelf, rate, eq.params.lowShelf.frequency, eq.params.lowShelf.gainDb, eq.params.lowShelf.q, hz);
            for (const auto& band : eq.params.peaks) expected *= response(Shape::Peak, rate, band.frequency, band.gainDb, band.q, hz);
            expected *= response(Shape::HighShelf, rate, eq.params.highShelf.frequency, eq.params.highShelf.gainDb, eq.params.highShelf.q, hz);
            const double error = std::abs(decibels(sineMagnitude(eq, rate, hz)) - decibels(std::abs(expected)));
            worst = std::max(worst, error);
            require(error <= 0.5, "EQ analytical response mismatch");
        }
        for (bool low : {false, true}) for (int slope : {12, 24}) {
            dsp::Filters filter;
            filter.params.lowPass = low;
            filter.params.highPass = !low;
            filter.params.lowPassHz = 2500.0f;
            filter.params.highPassHz = 500.0f;
            filter.params.resonance = 0.85f;
            filter.params.slope = slope;
            for (int hz : {40, 500, 2500, 9000}) {
                auto expected = response(low ? Shape::LowPass : Shape::HighPass, rate,
                                         low ? 2500.0 : 500.0, 0.0, filter.params.resonance, hz);
                if (slope == 24) expected *= expected;
                const double error = std::abs(decibels(sineMagnitude(filter, rate, hz)) - decibels(std::abs(expected)));
                worst = std::max(worst, error);
                require(error <= 0.5, "Filter analytical response mismatch");
            }
        }
    }
    std::printf("PASS EQ/filter sine responses, worst error %.6f dB\n", worst);
}

void testFlatSlope() {
    for (bool low : {true, false}) {
        auto measure = [low](bool flat, int slope, float resonance, int hz) {
            dsp::Filters filter;
            filter.params.lowPass = low;
            filter.params.highPass = !low;
            filter.params.lowPassHz = filter.params.highPassHz = 1000.0f;
            filter.params.resonance = resonance;
            filter.params.slope = slope;
            filter.params.flat = flat;
            return decibels(sineMagnitude(filter, 48000, hz));
        };
        const int pass = low ? 250 : 4000;
        require(std::abs(measure(true, 24, 0.70710678f, 1000) + 3.0103) < 0.2, "Flat 24 dB filter is not -3 dB at the cutoff");
        require(std::abs(measure(true, 24, 0.70710678f, pass)) < 0.1, "Flat 24 dB filter is not flat in the pass band");
        require(std::abs(measure(true, 24, 10.0f, 1000) - measure(true, 12, 10.0f, 1000)) < 1.0, "Flat 24 dB resonance peak differs from the 12 dB one");
        require(std::abs(measure(true, 12, 2.5f, 1000) - measure(false, 12, 2.5f, 1000)) < 1.0e-6, "12 dB filter changed with the flat setting");
        require(std::abs(measure(false, 24, 0.70710678f, 1000) + 6.0206) < 0.2, "Saved 24 dB filter no longer cuts 6 dB at the cutoff");
        require(std::abs(measure(false, 24, 10.0f, 1000) - 40.0) < 0.5, "Saved 24 dB filter no longer peaks at +40 dB with Q 10");
    }
    std::puts("PASS flat 24 dB filters keep the user Q on one stage, saved filters unchanged");
}

void testReverb() {
    dsp::Reverb reverb;
    reverb.params.wet = 1.0f;
    reverb.params.dry = 0.0f;
    reverb.params.roomSize = 0.45f;
    reverb.params.preDelayMs = 30.0f;
    reverb.prepare(48000, 4096);
    std::vector<float> data(2 * 48000 * 8, 0.0f);
    data[0] = data[1] = 1.0f;
    runBlocks(reverb, data, 4096);
    double early = 0.0, late = 0.0;
    for (std::size_t i = 0; i < data.size(); ++i) {
        require(std::isfinite(data[i]), "Reverb tail is not finite");
        if (i < 2 * 48000) early += static_cast<double>(data[i]) * data[i];
        if (i >= 2 * 48000 * 7) late += static_cast<double>(data[i]) * data[i];
        if (i < 2 * 1440) require(data[i] == 0.0f, "Reverb pre-delay was bypassed");
    }
    require(early > 1.0e-5 && late < early * 1.0e-4, "Reverb tail did not decay");
    reverb.reset();
    std::fill(data.begin(), data.end(), 0.0f);
    runBlocks(reverb, data, 997);
    require(std::all_of(data.begin(), data.end(), [](float v) { return v == 0.0f; }), "Reverb reset left a tail");
    std::printf("PASS Reverb finite decaying tail and reset\n");
}

void testDelay() {
    for (int rate : {8000, 22050, 48000, 96000, 192000}) for (float time : {1.0f, 13.75f, 2000.0f}) {
        dsp::Delay delay;
        delay.params.timeMs = time;
        delay.params.feedback = 0.0f;
        delay.params.mix = 1.0f;
        delay.params.lowCutHz = 0.0f;
        delay.prepare(rate, 4096);
        const double position = time * 0.001 * rate;
        const int first = static_cast<int>(std::floor(position));
        const double fraction = position - first;
        std::vector<float> data(static_cast<std::size_t>(2 * (first + 10)), 0.0f);
        data[0] = 1.0f;
        data[1] = -0.5f;
        runBlocks(delay, data, 127);
        for (int i = 0; i < first + 10; ++i) {
            const double expected = i == first ? 1.0 - fraction : (i == first + 1 ? fraction : 0.0);
            require(std::abs(data[static_cast<std::size_t>(2 * i)] - expected) < 1.0e-6, "Delay impulse at wrong sample");
            require(std::abs(data[static_cast<std::size_t>(2 * i + 1)] + 0.5 * expected) < 1.0e-6, "Delay stereo impulse mismatch");
        }
    }
    dsp::Delay ping;
    ping.params.timeMs = 10.0f;
    ping.params.feedback = 0.5f;
    ping.params.mix = 1.0f;
    ping.params.lowCutHz = 0.0f;
    ping.params.pingPong = true;
    ping.prepare(48000, 4096);
    std::vector<float> data(4000, 0.0f);
    data[0] = data[1] = 1.0f;
    runBlocks(ping, data, 13);
    require(data[960] == 1.0f && data[961] == 0.0f && data[1920] == 0.0f && data[1921] == 0.5f, "Ping-pong routing mismatch");
    std::printf("PASS Delay integer/fractional impulse timing and ping-pong\n");
}

void testDelayWrap() {
    dsp::detail::StereoBuffer buffer;
    buffer.prepare(38402);
    buffer.write(0.25, -0.75);
    buffer.position = 200;
    const double delay = std::nextafter(200.0, 300.0);
    require(buffer.read(delay, 0) == 0.25 && buffer.read(delay, 1) == -0.75, "Delay read past the end of its buffer");
    std::printf("PASS Delay read just behind the write position stays inside the buffer\n");
}

void testCompressor() {
    for (float ratio : {1.0f, 2.0f, 4.0f, 20.0f}) {
        dsp::Compressor compressor;
        compressor.params.thresholdDb = -18.0f;
        compressor.params.ratio = ratio;
        compressor.params.kneeDb = 0.0f;
        compressor.params.makeupDb = 3.0f;
        compressor.prepare(48000, 4096);
        std::vector<float> data(2 * 48000);
        for (std::size_t i = 0; i < data.size(); i += 2) { data[i] = 0.5f; data[i + 1] = 0.125f; }
        runBlocks(compressor, data, 191);
        const double expectedDb = -18.0 + (decibels(0.5) + 18.0) / ratio + 3.0;
        require(std::abs(decibels(data[data.size() - 2]) - expectedDb) < 0.5, "Compressor steady ratio mismatch");
        require(std::abs(data.back() * 4.0f - data[data.size() - 2]) < 1.0e-7, "Compressor is not stereo linked");
    }
    std::printf("PASS Compressor ratios and stereo-linked gain\n");
}

void testGate() {
    dsp::NoiseGate gate;
    gate.params.thresholdDb = -40.0f;
    gate.params.rangeDb = 80.0f;
    gate.params.releaseMs = 25.0f;
    gate.params.holdMs = 20.0f;
    gate.prepare(48000, 4096);
    std::vector<float> open(2 * 48000, 0.1f);
    runBlocks(gate, open, 61);
    require(std::abs(open.back() - 0.1f) < 1.0e-6, "Gate did not open");
    std::vector<float> quiet(2 * 48000, 0.001f);
    runBlocks(gate, quiet, 61);
    require(std::abs(decibels(quiet.back() / 0.001) + 80.0) < 0.1, "Gate did not close to range");
    require(quiet[0] > quiet.back() * 100.0f, "Gate hold/release did not retain initial gain");
    std::printf("PASS Gate open, hold, release and closed range\n");
}

void testLimiter() {
    Random random;
    for (int rate : {8000, 22050, 48000, 96000, 192000}) for (float ahead : {1.0f, 3.0f, 5.0f}) for (float ceiling : {-12.0f, -1.0f, 0.0f}) {
        dsp::Limiter limiter;
        limiter.params.lookaheadMs = ahead;
        limiter.params.ceilingDb = ceiling;
        limiter.prepare(rate, 4096);
        std::vector<float> data(static_cast<std::size_t>(2 * rate));
        for (auto& sample : data) sample = 16.0f * random.next();
        runBlocks(limiter, data, 251);
        double peak = 0.0;
        for (float sample : data) {
            require(std::isfinite(sample) && std::abs(sample) <= gain(ceiling), "Limiter exceeded ceiling");
            peak = std::max(peak, std::abs(static_cast<double>(sample)));
        }
        require(peak > gain(ceiling) * 0.5, "Limiter output unexpectedly silent");
        limiter.reset();
        std::vector<float> impulse(static_cast<std::size_t>(2 * (limiter.latencyFrames() + 10)), 0.0f);
        impulse[0] = 0.01f;
        runBlocks(limiter, impulse, 17);
        for (std::size_t i = 0; i < impulse.size() / 2; ++i)
            require(impulse[2 * i] == (i == static_cast<std::size_t>(limiter.latencyFrames()) ? 0.01f : 0.0f), "Limiter latency mismatch");
    }
    std::printf("PASS Limiter ceiling on loud random input and 1..5 ms latency\n");
}

void testLimiterSmoothAttack() {
    for (int rate : {44100, 48000}) {
        dsp::Limiter limiter;
        limiter.prepare(rate, 4096);
        const int frames = rate;
        const int spike = rate / 2 + 11;
        std::vector<float> data(static_cast<std::size_t>(2 * frames));
        for (int i = 0; i < frames; ++i) {
            const float value = i == spike ? 8.0f : static_cast<float>(0.3 * std::sin(2.0 * pi * 1000.0 * i / rate));
            data[static_cast<std::size_t>(2 * i)] = data[static_cast<std::size_t>(2 * i + 1)] = value;
        }
        runBlocks(limiter, data, 1024);
        const int latency = limiter.latencyFrames();
        const double natural = 0.3 * 2.0 * pi * 1000.0 / rate;
        double slope = 0.0;
        for (int i = latency + 1; i < frames; ++i) {
            if (std::abs(i - (spike + latency)) <= 1) continue;
            slope = std::max(slope, std::abs(static_cast<double>(data[static_cast<std::size_t>(2 * i)]) - data[static_cast<std::size_t>(2 * i - 2)]));
        }
        require(slope < natural + 0.005, "Limiter gain changes in a step instead of a ramp");
        require(std::abs(data[static_cast<std::size_t>(2 * (spike + latency))] - gain(-1.0)) < 1.0e-3, "Limiter did not bring the peak to the ceiling");
        const int late = frames - 1 - rate / 4000;
        const double expected = 0.3 * std::sin(2.0 * pi * 1000.0 * (late - latency) / rate);
        require(std::abs(data[static_cast<std::size_t>(2 * late)] - expected) < 0.01, "Limiter did not release");
    }
    std::printf("PASS Limiter ramps its gain across the lookahead and releases\n");
}

void customize(dsp::Equalizer& e) { e.params.peaks[1].gainDb = 12.0f; }
void customize(dsp::Filters& e) { e.params.highPass = true; e.params.slope = 24; }
void customize(dsp::Reverb& e) { e.params.preDelayMs = 18.0f; e.params.roomSize = 0.8f; }
void customize(dsp::Delay& e) { e.params.timeMs = 8.5f; e.params.feedback = 0.7f; }
void customize(dsp::Compressor& e) { e.params.thresholdDb = -25.0f; }
void customize(dsp::NoiseGate& e) { e.params.thresholdDb = -10.0f; }
void customize(dsp::Limiter& e) { e.params.ceilingDb = -6.0f; }
void customize(dsp::PitchShifter& e) { e.params.semitones = 5.0f; }

void automate(dsp::Equalizer& e) { e.params.lowShelf.gainDb = -12.0f; e.params.peaks[1].frequency = 4000.0f; }
void automate(dsp::Filters& e) { e.params.lowPassHz = 500.0f; e.params.highPass = false; e.params.slope = 12; }
void automate(dsp::Reverb& e) { e.params.preDelayMs = 5.0f; e.params.width = 0.2f; e.params.damping = 0.9f; }
void automate(dsp::Delay& e) { e.params.timeMs = 4.0f; e.params.pingPong = true; e.params.lowCutHz = 0.0f; }
void automate(dsp::Compressor& e) { e.params.ratio = 10.0f; e.params.attackMs = 2.0f; e.params.thresholdDb = -12.0f; }
void automate(dsp::NoiseGate& e) { e.params.thresholdDb = -3.0f; e.params.rangeDb = 35.0f; }
void automate(dsp::Limiter& e) { e.params.lookaheadMs = 5.0f; e.params.ceilingDb = -12.0f; }
void automate(dsp::PitchShifter& e) { e.params.semitones = -7.0f; }

template <typename Effect>
void testStreaming(const char* name) {
    static_assert(noexcept(std::declval<Effect&>().process(nullptr, 0)), "process must be noexcept");
    Effect single, large;
    customize(single);
    customize(large);
    single.prepare(48000, 4096);
    large.prepare(48000, 4096);
    Random random;
    std::vector<float> a(2 * 16384);
    for (auto& sample : a) sample = random.next();
    auto b = a;
    for (int section = 0; section < 4; ++section) {
        if (section == 1) { automate(single); automate(large); }
        for (int frame = 0; frame < 4096; ++frame) single.process(a.data() + 2 * (section * 4096 + frame), 1);
        large.process(b.data() + 2 * section * 4096, 4096);
    }
    double difference = 0.0;
    for (std::size_t i = 0; i < a.size(); ++i) difference = std::max(difference, std::abs(static_cast<double>(a[i]) - b[i]));
    require(difference <= 1.0e-5, std::string(name) + " block-size dependence");
    single.reset();
    std::vector<float> silence(2 * 4096, 0.0f);
    single.process(silence.data(), 4096);
    require(std::all_of(silence.begin(), silence.end(), [](float sample) { return sample == 0.0f; }), std::string(name) + " reset left state");
    allocationCount = 0;
    trackAllocations = true;
    single.process(a.data(), 4096);
    single.reset();
    trackAllocations = false;
    require(allocationCount == 0, std::string(name) + " allocated during processing/reset");
    for (int rate : {8000, 22050, 96000, 192000, 44100, 48000}) {
        single.prepare(rate, 4096);
        for (int signal = 0; signal < 4; ++signal) {
            for (auto& sample : silence) sample = signal == 0 ? 0.0f : signal == 1 ? 1.0f : signal == 2 ? random.next() : 1.0e-40f;
            single.process(silence.data(), 4096);
            for (float sample : silence) {
                require(std::isfinite(sample), std::string(name) + " produced NaN/Inf");
                require(std::fpclassify(sample) != FP_SUBNORMAL, std::string(name) + " produced denormal");
            }
        }
    }
    std::printf("PASS %-10s block invariance (%.2g), reset, allocation and finite checks\n", name, difference);
}

void testPitch() {
    for (int rate : {22050, 44100, 48000, 96000}) for (double semitones : {12.0, 7.0, 2.0, -3.0, -7.0, -12.0}) for (double hz : {220.0, 440.0}) {
        dsp::PitchShifter shifter;
        shifter.params.semitones = static_cast<float>(semitones);
        shifter.prepare(rate, 4096);
        std::vector<float> data(static_cast<std::size_t>(4 * rate));
        for (int i = 0; i < 2 * rate; ++i)
            data[static_cast<std::size_t>(2 * i)] = data[static_cast<std::size_t>(2 * i + 1)] = static_cast<float>(0.2 * std::sin(2.0 * pi * hz * i / rate));
        runBlocks(shifter, data, 1000);
        int crossings = 0;
        double energy = 0.0, peak = 0.0;
        for (int i = rate; i < 2 * rate; ++i) {
            const float a = data[static_cast<std::size_t>(2 * i - 2)], b = data[static_cast<std::size_t>(2 * i)];
            if (a < 0.0f && b >= 0.0f) ++crossings;
            energy += static_cast<double>(b) * b;
            peak = std::max(peak, std::abs(static_cast<double>(b)));
        }
        const double expected = hz * std::pow(2.0, semitones / 12.0);
        const double rms = std::sqrt(energy / rate);
        require(std::abs(crossings - expected) <= 0.02 * expected + 2.0, "Pitch shifted to the wrong frequency (" + std::to_string(rate) + " Hz, " + std::to_string(semitones) + " st, " + std::to_string(crossings) + " vs " + std::to_string(expected) + ")");
        require(rms > 0.2 * 0.7071 * 0.8 && rms < 0.2 * 0.7071 * 1.2 && peak < 0.2 * 1.25, "Pitch shifted level changed (" + std::to_string(rms) + ", " + std::to_string(peak) + ")");
    }
    dsp::PitchShifter noise;
    noise.params.semitones = 4.0f;
    noise.prepare(48000, 4096);
    Random random;
    std::vector<float> data(2 * 48000);
    for (auto& sample : data) sample = random.next();
    runBlocks(noise, data, 480);
    for (float sample : data) require(std::abs(sample) < 2.0f, "Pitch shifter exploded on noise");
    std::printf("PASS Pitch shifter frequency and level over rates, shifts and tones\n");
}

void testChain() {
    std::array<dsp::Equalizer, 17> members;
    dsp::Chain full;
    for (std::size_t i = 0; i < dsp::Chain::capacity; ++i) require(full.add(members[i]), "Chain capacity too small");
    require(!full.add(members[16]), "Chain exceeded capacity");
    full.clear();
    require(full.add(members[0]) && !full.add(members[0]), "Chain accepted duplicate state");
    dsp::Equalizer aEq, bEq;
    dsp::Delay aDelay, bDelay;
    dsp::Limiter aLimiter, bLimiter;
    customize(aEq); customize(bEq); customize(aDelay); customize(bDelay);
    dsp::Chain a, b;
    require(a.add(aEq, 0.7f) && a.add(aDelay, 0.4f) && a.add(aLimiter), "Chain add failed");
    require(b.add(bEq, 0.7f) && b.add(bDelay, 0.4f) && b.add(bLimiter), "Chain add failed");
    a.prepare(48000, 127);
    b.prepare(48000, 127);
    Random random;
    std::vector<float> x(2 * 8192);
    for (auto& sample : x) sample = random.next();
    auto y = x;
    for (int section = 0; section < 2; ++section) {
        if (section == 1) { a.at(0)->bypass = b.at(0)->bypass = true; a.at(1)->mix = b.at(1)->mix = 0.9f; }
        for (int i = 0; i < 4096; ++i) a.process(x.data() + 2 * (section * 4096 + i), 1);
        b.process(y.data() + 2 * section * 4096, 4096);
    }
    for (std::size_t i = 0; i < x.size(); ++i) require(std::abs(x[i] - y[i]) <= 1.0e-5, "Chain block-size dependence");
    allocationCount = 0;
    trackAllocations = true;
    b.process(x.data(), 4096);
    b.reset();
    trackAllocations = false;
    require(allocationCount == 0, "Chain process/reset allocated");
    require(a.size() == 3 && a.at(3) == nullptr && !a.add(aEq), "Chain bounds/lifecycle failure");
    dsp::Equalizer muted;
    muted.params.lowShelf.gainDb = 12.0f;
    dsp::Chain bypass;
    bypass.add(muted, 1.0f, true);
    bypass.prepare(48000, 4096);
    x.assign(2 * 2048, 0.25f);
    bypass.process(x.data(), 2048);
    require(std::all_of(x.begin(), x.end(), [](float v) { return v == 0.25f; }), "Chain bypass is not dry");
    bypass.at(0)->bypass = false;
    bypass.process(x.data(), 2048);
    require(std::abs(x[0] - 0.25f) < 0.003f && x.back() > 0.9f, "Chain bypass did not ramp");
    bypass.clear();
    require(bypass.size() == 0 && bypass.add(muted), "Chain clear failed");
    dsp::Compressor manualCompressor, chainCompressor;
    dsp::NoiseGate manualGate, chainGate;
    dsp::Chain ordered;
    ordered.add(chainGate, 0.3f);
    ordered.add(chainCompressor, 0.8f);
    ordered.prepare(48000, 4096);
    manualCompressor.prepare(48000, 4096);
    manualGate.prepare(48000, 4096);
    x.resize(2 * 4096);
    for (auto& sample : x) sample = random.next();
    y = x;
    auto dry = x;
    manualGate.process(y.data(), 4096);
    for (std::size_t i = 0; i < y.size(); ++i) y[i] = static_cast<float>(dry[i] + 0.3f * (static_cast<double>(y[i]) - dry[i]));
    dry = y;
    manualCompressor.process(y.data(), 4096);
    for (std::size_t i = 0; i < y.size(); ++i) y[i] = static_cast<float>(dry[i] + 0.8f * (static_cast<double>(y[i]) - dry[i]));
    ordered.process(x.data(), 4096);
    for (std::size_t i = 0; i < x.size(); ++i) require(std::abs(x[i] - y[i]) < 1.0e-6, "Chain order/mix mismatch");
    std::printf("PASS Chain order, mix, smoothed bypass, capacity and block invariance\n");
}

template <typename Effect>
void measureTiming(const char* name, Effect& effect) {
    effect.prepare(48000, 256);
    std::vector<float> source(2 * 48000);
    Random random;
    for (auto& sample : source) sample = 0.25f * random.next();
    auto data = source;
    runBlocks(effect, data, 256);
    double best = std::numeric_limits<double>::max();
    for (int repeat = 0; repeat < 5; ++repeat) {
        effect.reset();
        data = source;
        const auto start = std::chrono::steady_clock::now();
        runBlocks(effect, data, 256);
        const auto stop = std::chrono::steady_clock::now();
        best = std::min(best, std::chrono::duration<double, std::milli>(stop - start).count());
    }
    require(std::isfinite(data[1000]), "Timing run produced invalid output");
    std::printf("TIME %-10s %8.3f ms / 1 s stereo, %6.2f ns/frame\n", name, best, best * 1.0e6 / 48000.0);
}

template <typename Effect>
void timing(const char* name) {
    Effect effect;
    customize(effect);
    measureTiming(name, effect);
}

void timingChain() {
    dsp::Equalizer equalizer;
    dsp::Filters filters;
    dsp::Reverb reverb;
    dsp::Delay delay;
    dsp::Compressor compressor;
    dsp::NoiseGate gate;
    dsp::Limiter limiter;
    customize(equalizer); customize(filters); customize(reverb); customize(delay);
    customize(compressor); customize(gate); customize(limiter);
    dsp::Chain chain;
    chain.add(equalizer); chain.add(filters); chain.add(reverb); chain.add(delay);
    chain.add(compressor); chain.add(gate); chain.add(limiter);
    measureTiming("Chain (7)", chain);
}

}

int main() {
    try {
        testResponses();
        testFlatSlope();
        testReverb();
        testDelay();
        testDelayWrap();
        testCompressor();
        testGate();
        testLimiter();
        testLimiterSmoothAttack();
        testStreaming<dsp::Equalizer>("Equalizer");
        testStreaming<dsp::Filters>("Filters");
        testStreaming<dsp::Reverb>("Reverb");
        testStreaming<dsp::Delay>("Delay");
        testStreaming<dsp::Compressor>("Compressor");
        testStreaming<dsp::NoiseGate>("NoiseGate");
        testStreaming<dsp::Limiter>("Limiter");
        testStreaming<dsp::PitchShifter>("Pitch");
        testPitch();
        testChain();
        timing<dsp::Equalizer>("Equalizer");
        timing<dsp::Filters>("Filters");
        timing<dsp::Reverb>("Reverb");
        timing<dsp::Delay>("Delay");
        timing<dsp::Compressor>("Compressor");
        timing<dsp::NoiseGate>("NoiseGate");
        timing<dsp::Limiter>("Limiter");
        timing<dsp::PitchShifter>("Pitch");
        timingChain();
        std::puts("ALL TESTS PASSED");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL: %s\n", error.what());
        return 1;
    }
}
