#include <jni.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "audio_dsp.h"

namespace {

enum Kind { Eq = 0, LowPass = 1, HighPass = 2, Reverb = 3, Echo = 4, Compressor = 5, Gate = 6, Limiter = 7 };

int paramCount(int kind) {
    switch (kind) {
    case Eq: return 13;
    case LowPass: case HighPass: return 3;
    case Reverb: return 6;
    case Echo: return 5;
    case Compressor: return 6;
    case Gate: return 5;
    case Limiter: return 3;
    default: return -1;
    }
}

struct Unit {
    int kind = 0;
    std::unique_ptr<dsp::Equalizer> eq;
    std::unique_ptr<dsp::Filters> filters;
    std::unique_ptr<dsp::Reverb> reverb;
    std::unique_ptr<dsp::Delay> delay;
    std::unique_ptr<dsp::Compressor> compressor;
    std::unique_ptr<dsp::NoiseGate> gate;
    std::unique_ptr<dsp::Limiter> limiter;
};

struct Rack {
    std::vector<Unit> units;
    dsp::Chain chain;
    std::size_t values = 0;
    bool fresh = true;
};

Rack* rackOf(jlong handle) {
    return reinterpret_cast<Rack*>(static_cast<intptr_t>(handle));
}

bool attach(Rack& rack, Unit& unit) {
    switch (unit.kind) {
    case Eq: unit.eq = std::make_unique<dsp::Equalizer>(); return rack.chain.add(*unit.eq);
    case LowPass: case HighPass:
        unit.filters = std::make_unique<dsp::Filters>();
        unit.filters->params.lowPass = unit.kind == LowPass;
        unit.filters->params.highPass = unit.kind == HighPass;
        return rack.chain.add(*unit.filters);
    case Reverb: unit.reverb = std::make_unique<dsp::Reverb>(); return rack.chain.add(*unit.reverb);
    case Echo: unit.delay = std::make_unique<dsp::Delay>(); return rack.chain.add(*unit.delay);
    case Compressor: unit.compressor = std::make_unique<dsp::Compressor>(); return rack.chain.add(*unit.compressor);
    case Gate: unit.gate = std::make_unique<dsp::NoiseGate>(); return rack.chain.add(*unit.gate);
    case Limiter: unit.limiter = std::make_unique<dsp::Limiter>(); return rack.chain.add(*unit.limiter);
    default: return false;
    }
}

void apply(Unit& unit, const float* p) {
    switch (unit.kind) {
    case Eq: {
        auto& e = unit.eq->params;
        e.lowShelf.frequency = p[0]; e.lowShelf.gainDb = p[1];
        for (int i = 0; i < 3; ++i) {
            e.peaks[static_cast<std::size_t>(i)].frequency = p[2 + 3 * i];
            e.peaks[static_cast<std::size_t>(i)].gainDb = p[3 + 3 * i];
            e.peaks[static_cast<std::size_t>(i)].q = p[4 + 3 * i];
        }
        e.highShelf.frequency = p[11]; e.highShelf.gainDb = p[12];
        break;
    }
    case LowPass: case HighPass: {
        auto& f = unit.filters->params;
        if (unit.kind == LowPass) f.lowPassHz = p[0]; else f.highPassHz = p[0];
        f.resonance = p[1];
        f.slope = p[2] >= 18.0f ? 24 : 12;
        break;
    }
    case Reverb: {
        auto& r = unit.reverb->params;
        r.roomSize = p[0]; r.damping = p[1]; r.wet = p[2]; r.dry = p[3]; r.width = p[4]; r.preDelayMs = p[5];
        break;
    }
    case Echo: {
        auto& d = unit.delay->params;
        d.timeMs = p[0]; d.feedback = p[1]; d.mix = p[2]; d.pingPong = p[3] >= 0.5f; d.lowCutHz = p[4];
        break;
    }
    case Compressor: {
        auto& c = unit.compressor->params;
        c.thresholdDb = p[0]; c.ratio = p[1]; c.attackMs = p[2]; c.releaseMs = p[3]; c.kneeDb = p[4]; c.makeupDb = p[5];
        break;
    }
    case Gate: {
        auto& g = unit.gate->params;
        g.thresholdDb = p[0]; g.attackMs = p[1]; g.holdMs = p[2]; g.releaseMs = p[3]; g.rangeDb = p[4];
        break;
    }
    case Limiter: {
        auto& l = unit.limiter->params;
        l.lookaheadMs = p[0]; l.ceilingDb = p[1]; l.releaseMs = p[2];
        break;
    }
    default: break;
    }
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_audio_NativeFx_nativeCreate(JNIEnv* env, jclass, jintArray kinds, jint rate, jint maxBlock) {
    const jsize count = kinds ? env->GetArrayLength(kinds) : 0;
    if (count <= 0 || static_cast<std::size_t>(count) > dsp::Chain::capacity) return 0;
    std::vector<jint> list(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(kinds, 0, count, list.data());
    auto rack = std::make_unique<Rack>();
    rack->units.resize(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        Unit& unit = rack->units[static_cast<std::size_t>(i)];
        unit.kind = list[static_cast<std::size_t>(i)];
        const int params = paramCount(unit.kind);
        if (params < 0 || !attach(*rack, unit)) return 0;
        rack->values += 2 + static_cast<std::size_t>(params);
    }
    rack->chain.prepare(rate, maxBlock);
    return reinterpret_cast<intptr_t>(rack.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_absentmotion_audio_NativeFx_nativeSet(JNIEnv* env, jclass, jlong handle, jfloatArray values) {
    Rack* rack = rackOf(handle);
    if (!rack || !values || static_cast<std::size_t>(env->GetArrayLength(values)) != rack->values) return JNI_FALSE;
    auto* data = static_cast<float*>(env->GetPrimitiveArrayCritical(values, nullptr));
    if (!data) return JNI_FALSE;
    const float* p = data;
    for (std::size_t i = 0; i < rack->units.size(); ++i) {
        Unit& unit = rack->units[i];
        dsp::Chain::Slot* slot = rack->chain.at(i);
        slot->mix = p[0];
        slot->bypass = p[1] >= 0.5f;
        apply(unit, p + 2);
        p += 2 + paramCount(unit.kind);
    }
    env->ReleasePrimitiveArrayCritical(values, data, JNI_ABORT);
    if (rack->fresh) rack->chain.reset();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_audio_NativeFx_nativeProcess(JNIEnv* env, jclass, jlong handle, jfloatArray data, jint frames) {
    Rack* rack = rackOf(handle);
    if (!rack || !data || frames <= 0) return;
    const jint available = env->GetArrayLength(data) / 2;
    const jint count = frames < available ? frames : available;
    auto* samples = static_cast<float*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (!samples) return;
    rack->chain.process(samples, count);
    env->ReleasePrimitiveArrayCritical(data, samples, 0);
    rack->fresh = false;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_audio_NativeFx_nativeReset(JNIEnv*, jclass, jlong handle) {
    if (Rack* rack = rackOf(handle)) {
        rack->chain.reset();
        rack->fresh = true;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_app_absentmotion_audio_NativeFx_nativeLatency(JNIEnv*, jclass, jlong handle) {
    Rack* rack = rackOf(handle);
    if (!rack) return 0;
    jint total = 0;
    for (auto& unit : rack->units) if (unit.limiter) total += unit.limiter->latencyFrames();
    return total;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_audio_NativeFx_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete rackOf(handle);
}
