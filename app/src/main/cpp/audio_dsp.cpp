#include "audio_dsp.h"

#include <algorithm>
#include <cmath>

namespace dsp {
namespace {

constexpr double pi = 3.14159265358979323846;

double bound(double value, double low, double high, double fallback) noexcept {
    return std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}

double rate(double sampleRate) noexcept {
    return bound(sampleRate, 8000.0, 192000.0, 48000.0);
}

double clean(double value) noexcept {
    return std::isfinite(value) && std::abs(value) >= 1.0e-24 ? value : 0.0;
}

float output(double value) noexcept {
    return static_cast<float>(std::clamp(clean(value), -1.0e12, 1.0e12));
}

double dbGain(double db) noexcept {
    return std::pow(10.0, db * 0.05);
}

double timePole(double milliseconds, double sampleRate) noexcept {
    return std::exp(-1.0 / (milliseconds * 0.001 * sampleRate));
}

int rampLength(double sampleRate) noexcept {
    return static_cast<int>(std::round(sampleRate * 0.015));
}

void timeControl(detail::Ramp& control, double& key, double milliseconds, double sampleRate, int samples) noexcept {
    if (milliseconds != key || samples == 0) {
        key = milliseconds;
        control.set(timePole(milliseconds, sampleRate), samples);
    }
}

}

namespace detail {

void Ramp::reset(double nextValue) noexcept {
    value = target = nextValue;
    step = 0.0;
    remaining = 0;
}

void Ramp::set(double nextValue, int samples) noexcept {
    if (samples <= 0) {
        reset(nextValue);
    } else if (nextValue != target) {
        target = nextValue;
        remaining = samples;
        step = (target - value) / samples;
    }
}

double Ramp::next() noexcept {
    if (remaining > 0) {
        value += step;
        if (--remaining == 0) value = target;
    }
    return value;
}

void Biquad::set(Shape shape, double sampleRate, double frequency, double gain, double q, int samples) noexcept {
    frequency = bound(frequency, 20.0, std::min(20000.0, sampleRate * 0.45), 1000.0);
    gain = bound(gain, -24.0, 24.0, 0.0);
    q = bound(q, 0.1, 20.0, 0.70710678);
    const std::array<double, 5> nextKey{{static_cast<double>(shape), sampleRate, frequency, gain, q}};
    if (configured && key == nextKey && samples != 0) return;
    key = nextKey;
    const double angle = 2.0 * pi * frequency / sampleRate;
    const double cosine = std::cos(angle);
    const double alpha = std::sin(angle) / (2.0 * q);
    const double amplitude = std::pow(10.0, gain / 40.0);
    const double beta = 2.0 * std::sqrt(amplitude) * alpha;
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;
    switch (shape) {
    case Shape::Peak:
        b0 = 1.0 + alpha * amplitude;
        b1 = -2.0 * cosine;
        b2 = 1.0 - alpha * amplitude;
        a0 = 1.0 + alpha / amplitude;
        a1 = b1;
        a2 = 1.0 - alpha / amplitude;
        break;
    case Shape::LowShelf:
        b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + beta);
        b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine);
        b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta);
        a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + beta;
        a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine);
        a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - beta;
        break;
    case Shape::HighShelf:
        b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta);
        b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine);
        b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta);
        a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta;
        a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine);
        a2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - beta;
        break;
    case Shape::LowPass:
        b0 = b2 = (1.0 - cosine) * 0.5;
        b1 = 1.0 - cosine;
        a0 = 1.0 + alpha;
        a1 = -2.0 * cosine;
        a2 = 1.0 - alpha;
        break;
    case Shape::HighPass:
        b0 = b2 = (1.0 + cosine) * 0.5;
        b1 = -(1.0 + cosine);
        a0 = 1.0 + alpha;
        a1 = -2.0 * cosine;
        a2 = 1.0 - alpha;
        break;
    }
    const std::array<double, 5> values{{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0}};
    for (std::size_t i = 0; i < values.size(); ++i) coefficients[i].set(values[i], configured ? samples : 0);
    configured = true;
}

void Biquad::clear() noexcept {
    history = {};
}

void Biquad::tick() noexcept {
    for (auto& coefficient : coefficients) coefficient.next();
}

double Biquad::run(double input, int channel) noexcept {
    auto& h = history[static_cast<std::size_t>(channel)];
    const double result = clean(coefficients[0].value * input + coefficients[1].value * h[0] +
                                coefficients[2].value * h[1] - coefficients[3].value * h[2] - coefficients[4].value * h[3]);
    h[1] = h[0];
    h[0] = clean(input);
    h[3] = h[2];
    h[2] = result;
    return result;
}

void StereoBuffer::prepare(std::size_t frames) {
    data.assign(std::max<std::size_t>(frames, 2), {});
    position = 0;
}

void StereoBuffer::clear() noexcept {
    std::fill(data.begin(), data.end(), std::array<double, 2>{});
    position = 0;
}

void StereoBuffer::write(double left, double right) noexcept {
    data[position] = {{clean(left), clean(right)}};
}

double StereoBuffer::read(double delay, int channel) const noexcept {
    double index = static_cast<double>(position) - delay;
    if (index < 0.0) index += static_cast<double>(data.size());
    const auto first = static_cast<std::size_t>(index);
    const auto second = first + 1 == data.size() ? 0 : first + 1;
    const double fraction = index - static_cast<double>(first);
    return data[first][static_cast<std::size_t>(channel)] * (1.0 - fraction) +
           data[second][static_cast<std::size_t>(channel)] * fraction;
}

void StereoBuffer::advance() noexcept {
    if (++position == data.size()) position = 0;
}

void Comb::clear() noexcept {
    std::fill(data.begin(), data.end(), 0.0);
    position = 0;
    low = 0.0;
}

double Comb::run(double input, double feedback, double damping) noexcept {
    const double delayed = data[position];
    low = clean(delayed * (1.0 - damping) + low * damping);
    data[position] = clean(input + low * feedback);
    if (++position == data.size()) position = 0;
    return delayed;
}

void Allpass::clear() noexcept {
    std::fill(data.begin(), data.end(), 0.0);
    position = 0;
}

double Allpass::run(double input) noexcept {
    const double delayed = data[position];
    const double result = clean(delayed - input);
    data[position] = clean(input + delayed * 0.5);
    if (++position == data.size()) position = 0;
    return result;
}

}

void Equalizer::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    reset();
}

void Equalizer::configure(int samples) noexcept {
    filters_[0].set(detail::Shape::LowShelf, sampleRate_, bound(params.lowShelf.frequency, 20.0, 20000.0, 120.0), params.lowShelf.gainDb, params.lowShelf.q, samples);
    constexpr std::array<double, 3> defaultFrequencies{{400.0, 1500.0, 5000.0}};
    for (std::size_t i = 0; i < 3; ++i) {
        const auto& band = params.peaks[i];
        filters_[i + 1].set(detail::Shape::Peak, sampleRate_, bound(band.frequency, 20.0, 20000.0, defaultFrequencies[i]),
                            band.gainDb, bound(band.q, 0.1, 10.0, 1.0), samples);
    }
    filters_[4].set(detail::Shape::HighShelf, sampleRate_, bound(params.highShelf.frequency, 20.0, 20000.0, 10000.0), params.highShelf.gainDb, params.highShelf.q, samples);
}

void Equalizer::reset() noexcept {
    for (auto& filter : filters_) filter.clear();
    configure(0);
}

void Equalizer::process(float* data, int frames) noexcept {
    if (!data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& filter : filters_) filter.tick();
        for (int channel = 0; channel < 2; ++channel) {
            double value = clean(data[2 * frame + channel]);
            for (auto& filter : filters_) value = filter.run(value, channel);
            data[2 * frame + channel] = output(value);
        }
    }
}

void Filters::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    reset();
}

void Filters::configure(int samples) noexcept {
    const double resonance = bound(params.resonance, 0.1, 10.0, 0.70710678);
    const bool flat = params.flat && params.slope >= 24;
    const std::array<double, 2> q{{flat ? 0.54119610 : resonance, flat ? resonance * 1.84775907 : resonance}};
    const double lowHz = bound(params.lowPassHz, 20.0, 20000.0, 18000.0), highHz = bound(params.highPassHz, 20.0, 20000.0, 20.0);
    for (std::size_t i = 0; i < 2; ++i) {
        low_[i].set(detail::Shape::LowPass, sampleRate_, lowHz, 0.0, q[i], samples);
        high_[i].set(detail::Shape::HighPass, sampleRate_, highHz, 0.0, q[i], samples);
    }
    lowMix_.set(params.lowPass ? 1.0 : 0.0, samples);
    highMix_.set(params.highPass ? 1.0 : 0.0, samples);
    secondMix_.set(params.slope >= 24 ? 1.0 : 0.0, samples);
}

void Filters::reset() noexcept {
    for (auto& filter : low_) filter.clear();
    for (auto& filter : high_) filter.clear();
    configure(0);
}

void Filters::process(float* data, int frames) noexcept {
    if (!data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& filter : low_) filter.tick();
        for (auto& filter : high_) filter.tick();
        const double lowMix = lowMix_.next(), highMix = highMix_.next(), second = secondMix_.next();
        for (int channel = 0; channel < 2; ++channel) {
            double value = clean(data[2 * frame + channel]);
            const double high1 = high_[0].run(value, channel);
            const double high2 = high_[1].run(high1, channel);
            value += highMix * (high1 + second * (high2 - high1) - value);
            const double low1 = low_[0].run(value, channel);
            const double low2 = low_[1].run(low1, channel);
            value += lowMix * (low1 + second * (low2 - low1) - value);
            data[2 * frame + channel] = output(value);
        }
    }
}

void Reverb::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    constexpr std::array<int, 8> combLengths{{1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617}};
    constexpr std::array<int, 4> allpassLengths{{556, 441, 341, 225}};
    for (std::size_t channel = 0; channel < 2; ++channel) {
        for (std::size_t i = 0; i < combLengths.size(); ++i)
            combs_[channel][i].data.assign(static_cast<std::size_t>(std::round((combLengths[i] + 23.0 * channel) * sampleRate_ / 44100.0)), 0.0);
        for (std::size_t i = 0; i < allpassLengths.size(); ++i)
            allpasses_[channel][i].data.assign(static_cast<std::size_t>(std::round((allpassLengths[i] + 23.0 * channel) * sampleRate_ / 44100.0)), 0.0);
    }
    preDelay_.prepare(static_cast<std::size_t>(std::ceil(sampleRate_ * 0.2)) + 2);
    prepared_ = true;
    reset();
}

void Reverb::configure(int samples) noexcept {
    controls_[0].set(0.7 + 0.28 * bound(params.roomSize, 0.0, 1.0, 0.5), samples);
    controls_[1].set(0.4 * bound(params.damping, 0.0, 1.0, 0.5), samples);
    controls_[2].set(bound(params.wet, 0.0, 1.0, 0.3), samples);
    controls_[3].set(bound(params.dry, 0.0, 1.0, 1.0), samples);
    controls_[4].set(bound(params.width, 0.0, 1.0, 1.0), samples);
    controls_[5].set(bound(params.preDelayMs, 0.0, 200.0, 0.0) * sampleRate_ * 0.001, samples);
}

void Reverb::reset() noexcept {
    for (auto& channel : combs_) for (auto& comb : channel) comb.clear();
    for (auto& channel : allpasses_) for (auto& allpass : channel) allpass.clear();
    preDelay_.clear();
    configure(0);
}

void Reverb::process(float* data, int frames) noexcept {
    if (!prepared_ || !data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& control : controls_) control.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        preDelay_.write((left + right) * 0.015, 0.0);
        const double input = preDelay_.read(controls_[5].value, 0);
        preDelay_.advance();
        std::array<double, 2> wet{};
        for (std::size_t channel = 0; channel < 2; ++channel) {
            for (auto& comb : combs_[channel]) wet[channel] += comb.run(input, controls_[0].value, controls_[1].value);
            for (auto& allpass : allpasses_[channel]) wet[channel] = allpass.run(wet[channel]);
        }
        const double same = controls_[2].value * (0.5 + 0.5 * controls_[4].value);
        const double cross = controls_[2].value * (0.5 - 0.5 * controls_[4].value);
        data[2 * frame] = output(left * controls_[3].value + same * wet[0] + cross * wet[1]);
        data[2 * frame + 1] = output(right * controls_[3].value + same * wet[1] + cross * wet[0]);
    }
}

void Delay::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    buffer_.prepare(static_cast<std::size_t>(std::ceil(sampleRate_ * 2.0)) + 2);
    prepared_ = true;
    reset();
}

void Delay::configure(int samples) noexcept {
    controls_[0].set(bound(params.timeMs, 1.0, 2000.0, 250.0) * sampleRate_ * 0.001, samples);
    controls_[1].set(bound(params.feedback, 0.0, 0.95, 0.35), samples);
    controls_[2].set(bound(params.mix, 0.0, 1.0, 0.3), samples);
    controls_[3].set(params.pingPong ? 1.0 : 0.0, samples);
    const double cutoff = bound(params.lowCutHz, 0.0, 2000.0, 80.0);
    controls_[5].set(cutoff > 0.0 ? 1.0 : 0.0, samples);
    if (cutoff != cutoffKey_ || samples == 0) {
        cutoffKey_ = cutoff;
        controls_[4].set(1.0 - std::exp(-2.0 * pi * cutoff / sampleRate_), samples);
    }
}

void Delay::reset() noexcept {
    buffer_.clear();
    low_ = {};
    configure(0);
}

void Delay::process(float* data, int frames) noexcept {
    if (!prepared_ || !data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& control : controls_) control.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        const double delayedL = buffer_.read(controls_[0].value, 0);
        const double delayedR = buffer_.read(controls_[0].value, 1);
        low_[0] = clean(low_[0] + controls_[4].value * (delayedL - low_[0]));
        low_[1] = clean(low_[1] + controls_[4].value * (delayedR - low_[1]));
        const double feedbackL = delayedL - controls_[5].value * low_[0], feedbackR = delayedR - controls_[5].value * low_[1];
        const double ping = controls_[3].value, feedback = controls_[1].value, mix = controls_[2].value;
        buffer_.write(left + ping * ((left + right) * 0.5 - left) + feedback * (feedbackL + ping * (feedbackR - feedbackL)),
                      right * (1.0 - ping) + feedback * (feedbackR + ping * (feedbackL - feedbackR)));
        buffer_.advance();
        data[2 * frame] = output(left + mix * (delayedL - left));
        data[2 * frame + 1] = output(right + mix * (delayedR - right));
    }
}

void Compressor::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    reset();
}

void Compressor::configure(int samples) noexcept {
    controls_[0].set(bound(params.thresholdDb, -80.0, 0.0, -18.0), samples);
    controls_[1].set(bound(params.ratio, 1.0, 20.0, 4.0), samples);
    timeControl(controls_[2], timeKeys_[0], bound(params.attackMs, 0.1, 200.0, 10.0), sampleRate_, samples);
    timeControl(controls_[3], timeKeys_[1], bound(params.releaseMs, 5.0, 2000.0, 100.0), sampleRate_, samples);
    controls_[4].set(bound(params.kneeDb, 0.0, 24.0, 6.0), samples);
    controls_[5].set(bound(params.makeupDb, -24.0, 24.0, 0.0), samples);
}

void Compressor::reset() noexcept {
    envelope_ = 0.0;
    configure(0);
}

void Compressor::process(float* data, int frames) noexcept {
    if (!data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& control : controls_) control.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        const double peak = std::max(std::abs(left), std::abs(right));
        const double pole = peak > envelope_ ? controls_[2].value : controls_[3].value;
        envelope_ = clean(peak + pole * (envelope_ - peak));
        const double level = 20.0 * std::log10(std::max(envelope_, 1.0e-20));
        const double over = level - controls_[0].value, knee = controls_[4].value;
        const double slope = 1.0 / controls_[1].value - 1.0;
        double reduction = 0.0;
        if (over >= knee * 0.5) reduction = slope * over;
        else if (knee > 0.0 && over > -knee * 0.5) reduction = slope * (over + knee * 0.5) * (over + knee * 0.5) / (2.0 * knee);
        const double gain = dbGain(reduction + controls_[5].value);
        data[2 * frame] = output(left * gain);
        data[2 * frame + 1] = output(right * gain);
    }
}

void NoiseGate::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    reset();
}

void NoiseGate::configure(int samples) noexcept {
    controls_[0].set(dbGain(bound(params.thresholdDb, -100.0, 0.0, -50.0)), samples);
    timeControl(controls_[1], timeKeys_[0], bound(params.attackMs, 0.1, 200.0, 5.0), sampleRate_, samples);
    controls_[2].set(bound(params.holdMs, 0.0, 1000.0, 50.0) * sampleRate_ * 0.001, samples);
    timeControl(controls_[3], timeKeys_[1], bound(params.releaseMs, 5.0, 2000.0, 100.0), sampleRate_, samples);
    controls_[4].set(dbGain(-bound(params.rangeDb, 0.0, 100.0, 80.0)), samples);
}

void NoiseGate::reset() noexcept {
    holdRemaining_ = 0;
    configure(0);
    gain_ = controls_[4].value;
}

void NoiseGate::process(float* data, int frames) noexcept {
    if (!data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& control : controls_) control.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        const bool above = std::max(std::abs(left), std::abs(right)) >= controls_[0].value;
        if (above) holdRemaining_ = static_cast<int>(std::ceil(controls_[2].value));
        const bool open = above || holdRemaining_ > 0;
        if (!above && holdRemaining_ > 0) --holdRemaining_;
        const double target = open ? 1.0 : controls_[4].value;
        const double pole = open ? controls_[1].value : controls_[3].value;
        gain_ = clean(target + pole * (gain_ - target));
        data[2 * frame] = output(left * gain_);
        data[2 * frame + 1] = output(right * gain_);
    }
}

void PitchShifter::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    grain_ = std::max(64, 2 * static_cast<int>(std::round(sampleRate_ * 0.0175)));
    search_ = std::max(8, static_cast<int>(std::round(sampleRate_ * 0.006)));
    window_ = std::max(16, static_cast<int>(std::round(sampleRate_ * 0.008)));
    std::size_t size = 1;
    while (size < static_cast<std::size_t>(grain_ + 2 * search_ + window_ + 32)) size <<= 1;
    mask_ = size - 1;
    ring_.assign(size, {});
    mono_.assign(size, 0.0f);
    shape_.resize(static_cast<std::size_t>(grain_));
    for (int i = 0; i < grain_; ++i) shape_[static_cast<std::size_t>(i)] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * pi * i / grain_));
    prepared_ = true;
    reset();
}

void PitchShifter::configure(int samples) noexcept {
    ratio_.set(std::exp2(bound(params.semitones, -12.0, 12.0, 0.0) / 12.0), samples);
}

void PitchShifter::reset() noexcept {
    std::fill(ring_.begin(), ring_.end(), std::array<float, 2>{});
    std::fill(mono_.begin(), mono_.end(), 0.0f);
    written_ = 0;
    heads_[0] = {0.0, 0};
    heads_[1] = {-1.0e9, grain_ / 2};
    configure(0);
}

double PitchShifter::read(double position, std::size_t channel) const noexcept {
    const auto base = static_cast<std::int64_t>(std::floor(position));
    const double t = position - static_cast<double>(base);
    const auto at = [&](std::int64_t index) { return static_cast<double>(ring_[static_cast<std::size_t>(index) & mask_][channel]); };
    const double y0 = at(base - 1), y1 = at(base), y2 = at(base + 1), y3 = at(base + 2);
    return y1 + 0.5 * t * (y2 - y0 + t * (2.0 * y0 - 5.0 * y1 + 4.0 * y2 - y3 + t * (3.0 * (y1 - y2) + y3 - y0)));
}

void PitchShifter::restart(Head& head, const Head& other) noexcept {
    constexpr int guard = 4;
    const double ratio = ratio_.value;
    const std::int64_t nominal = written_ - guard - search_ - static_cast<std::int64_t>(std::ceil(std::max(0.0, ratio - 1.0) * grain_));
    const std::int64_t end = static_cast<std::int64_t>(std::floor(other.position));
    const auto score = [&](int shift, int stride) {
        double cross = 0.0, power = 0.0;
        const std::int64_t a = end - window_, b = nominal + shift - window_;
        for (int j = 0; j < window_; j += stride) {
            const double x = mono_[static_cast<std::size_t>(a + j) & mask_], y = mono_[static_cast<std::size_t>(b + j) & mask_];
            cross += x * y;
            power += y * y;
        }
        return cross / std::sqrt(power + 1.0e-9);
    };
    int best = 0;
    double top = score(0, 2);
    for (int shift = -search_; shift <= search_; shift += 4) {
        const double value = score(shift, 2);
        if (value > top + 1.0e-12) { top = value; best = shift; }
    }
    const int centre = best;
    top = score(centre, 1);
    for (int shift = centre - 3; shift <= centre + 3; ++shift) {
        if (shift == centre || shift < -search_ || shift > search_) continue;
        const double value = score(shift, 1);
        if (value > top + 1.0e-12) { top = value; best = shift; }
    }
    head.position = static_cast<double>(nominal + best);
}

void PitchShifter::process(float* data, int frames) noexcept {
    if (!prepared_ || !data || frames <= 0) return;
    configure(rampSamples_);
    const double lowest = 4.0 - static_cast<double>(mask_);
    for (int frame = 0; frame < frames; ++frame) {
        ratio_.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        const std::size_t slot = static_cast<std::size_t>(written_) & mask_;
        ring_[slot] = {{static_cast<float>(left), static_cast<float>(right)}};
        mono_[slot] = static_cast<float>(0.5 * (left + right));
        ++written_;
        const double newest = static_cast<double>(written_) - 3.0;
        double sum[2] = {0.0, 0.0};
        for (std::size_t i = 0; i < 2; ++i) {
            Head& head = heads_[i];
            if (head.phase == 0) restart(head, heads_[1 - i]);
            head.position = std::clamp(head.position, static_cast<double>(written_) + lowest, newest);
            const double weight = shape_[static_cast<std::size_t>(head.phase)];
            sum[0] += weight * read(head.position, 0);
            sum[1] += weight * read(head.position, 1);
            head.position += ratio_.value;
            if (++head.phase == grain_) head.phase = 0;
        }
        data[2 * frame] = output(sum[0]);
        data[2 * frame + 1] = output(sum[1]);
    }
}

void Limiter::prepare(double sampleRate, int) {
    sampleRate_ = rate(sampleRate);
    rampSamples_ = rampLength(sampleRate_);
    buffer_.prepare(static_cast<std::size_t>(std::ceil(sampleRate_ * 0.005)) + 3);
    gains_.assign(static_cast<std::size_t>(std::ceil(sampleRate_ * 0.005)) + 3, 1.0);
    prepared_ = true;
    reset();
}

int Limiter::latencyFrames() const noexcept {
    return static_cast<int>(std::round(bound(params.lookaheadMs, 1.0, 5.0, 3.0) * sampleRate_ * 0.001));
}

void Limiter::configure(int samples) noexcept {
    controls_[0].set(latencyFrames(), samples);
    controls_[1].set(dbGain(bound(params.ceilingDb, -24.0, 0.0, -1.0)), samples);
    timeControl(controls_[2], releaseKey_, bound(params.releaseMs, 5.0, 2000.0, 100.0), sampleRate_, samples);
}

void Limiter::reset() noexcept {
    buffer_.clear();
    history_ = {};
    gain_ = 1.0;
    holdRemaining_ = 0;
    std::fill(gains_.begin(), gains_.end(), 1.0);
    gainPosition_ = 0;
    configure(0);
    boxWidth_ = static_cast<int>(controls_[0].value) + 1;
    gainSum_ = boxWidth_;
    sinceExact_ = 0;
}

void Limiter::process(float* data, int frames) noexcept {
    if (!prepared_ || !data || frames <= 0) return;
    configure(rampSamples_);
    for (int frame = 0; frame < frames; ++frame) {
        for (auto& control : controls_) control.next();
        const double left = clean(data[2 * frame]), right = clean(data[2 * frame + 1]);
        double peak = std::max(std::abs(left), std::abs(right));
        for (int channel = 0; channel < 2; ++channel) {
            auto& h = history_[static_cast<std::size_t>(channel)];
            h[0] = h[1]; h[1] = h[2]; h[2] = h[3]; h[3] = channel == 0 ? left : right;
            for (int phase = 1; phase < 4; ++phase) {
                const double t = phase * 0.25;
                const double interpolated = h[1] + 0.5 * t * (h[2] - h[0] + t * (2.0 * h[0] - 5.0 * h[1] + 4.0 * h[2] - h[3] + t * (3.0 * (h[1] - h[2]) + h[3] - h[0])));
                peak = std::max(peak, std::abs(interpolated));
            }
        }
        const double ceiling = controls_[1].value;
        const double desired = peak > ceiling ? ceiling / peak : 1.0;
        if (desired <= gain_) {
            gain_ = desired;
            holdRemaining_ = static_cast<int>(std::ceil(sampleRate_ * 0.005)) + 3;
        } else if (holdRemaining_ > 0) {
            --holdRemaining_;
        } else {
            gain_ = desired + controls_[2].value * (gain_ - desired);
        }
        const std::size_t size = gains_.size();
        gains_[gainPosition_] = gain_;
        const int width = std::min(static_cast<int>(controls_[0].value), static_cast<int>(size) - 2) + 1;
        if (width != boxWidth_ || ++sinceExact_ >= width) {
            boxWidth_ = width;
            sinceExact_ = 0;
            gainSum_ = 0.0;
            for (int i = 0; i < width; ++i) gainSum_ += gains_[(gainPosition_ + size - static_cast<std::size_t>(i)) % size];
        } else {
            gainSum_ += gain_ - gains_[(gainPosition_ + size - static_cast<std::size_t>(width)) % size];
        }
        if (++gainPosition_ == size) gainPosition_ = 0;
        const double smoothed = std::min(1.0, gainSum_ / width);
        buffer_.write(left, right);
        const double delayedL = buffer_.read(controls_[0].value, 0);
        const double delayedR = buffer_.read(controls_[0].value, 1);
        buffer_.advance();
        const double safeCeiling = std::nextafter(static_cast<float>(ceiling), 0.0f);
        data[2 * frame] = output(std::clamp(delayedL * smoothed, -safeCeiling, safeCeiling));
        data[2 * frame + 1] = output(std::clamp(delayedR * smoothed, -safeCeiling, safeCeiling));
    }
}

Chain::Slot* Chain::at(std::size_t index) noexcept {
    return index < count_ ? &slots_[index] : nullptr;
}

std::size_t Chain::size() const noexcept {
    return count_;
}

void Chain::clear() noexcept {
    count_ = 0;
    prepared_ = false;
}

void Chain::prepare(double sampleRate, int maxBlock) {
    const double sampleRateValue = rate(sampleRate);
    rampSamples_ = rampLength(sampleRateValue);
    maxBlock_ = std::clamp(maxBlock, 1, 4096);
    dry_.assign(static_cast<std::size_t>(2 * maxBlock_), 0.0f);
    for (std::size_t i = 0; i < count_; ++i) slots_[i].prepareEffect(slots_[i].effect, sampleRateValue, maxBlock_);
    prepared_ = true;
    reset();
}

void Chain::reset() noexcept {
    for (std::size_t i = 0; i < count_; ++i) {
        auto& slot = slots_[i];
        slot.resetEffect(slot.effect);
        slot.amount.reset(slot.bypass ? 0.0 : bound(slot.mix, 0.0, 1.0, 1.0));
    }
}

void Chain::process(float* data, int frames) noexcept {
    if (!prepared_ || !data || frames <= 0) return;
    for (std::size_t i = 0; i < count_; ++i) {
        auto& slot = slots_[i];
        slot.amount.set(slot.bypass ? 0.0 : bound(slot.mix, 0.0, 1.0, 1.0), rampSamples_);
        for (int offset = 0; offset < frames;) {
            const int block = std::min(maxBlock_, frames - offset);
            float* current = data + 2 * offset;
            std::copy_n(current, 2 * block, dry_.data());
            slot.processEffect(slot.effect, current, block);
            for (int frame = 0; frame < block; ++frame) {
                const double amount = slot.amount.next();
                for (int channel = 0; channel < 2; ++channel) {
                    const int index = 2 * frame + channel;
                    current[index] = output(dry_[static_cast<std::size_t>(index)] + amount * (static_cast<double>(current[index]) - dry_[static_cast<std::size_t>(index)]));
                }
            }
            offset += block;
        }
    }
}

}
