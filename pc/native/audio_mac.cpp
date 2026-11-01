#include <jni.h>
#include <CoreAudio/CoreAudio.h>

template <typename T> static T property(AudioObjectID id, AudioObjectPropertySelector selector, AudioObjectPropertyScope scope) {
    AudioObjectPropertyAddress address = { selector, scope, kAudioObjectPropertyElementMain };
    T value{};
    UInt32 size = sizeof(T);
    return AudioObjectGetPropertyData(id, &address, 0, nullptr, &size, &value) == noErr ? value : T{};
}

extern "C" JNIEXPORT jlong JNICALL Java_app_absentmotion_audio_DeskOutput_deviceLatencyNs(JNIEnv*, jclass) {
    AudioObjectID device = property<AudioObjectID>(kAudioObjectSystemObject, kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal);
    Float64 rate = device ? property<Float64>(device, kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal) : 0;
    if (rate <= 0) return -1;
    UInt32 frames = property<UInt32>(device, kAudioDevicePropertyLatency, kAudioDevicePropertyScopeOutput)
        + property<UInt32>(device, kAudioDevicePropertySafetyOffset, kAudioDevicePropertyScopeOutput)
        + property<UInt32>(device, kAudioDevicePropertyBufferFrameSize, kAudioDevicePropertyScopeOutput);
    AudioObjectPropertyAddress address = { kAudioDevicePropertyStreams, kAudioDevicePropertyScopeOutput, kAudioObjectPropertyElementMain };
    AudioStreamID stream = 0;
    UInt32 size = sizeof(stream);
    if (AudioObjectGetPropertyData(device, &address, 0, nullptr, &size, &stream) == noErr && size >= sizeof(stream))
        frames += property<UInt32>(stream, kAudioStreamPropertyLatency, kAudioObjectPropertyScopeGlobal);
    return (jlong) (frames * 1e9 / rate);
}
