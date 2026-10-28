#include <jni.h>

#include <cmath>
#include <string>
#include <vector>

#include "scene.h"

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativePlaces(JNIEnv* env, jclass, jstring xml, jlongArray ids, jdoubleArray times) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    am::Scene scene;
    std::string error;
    const bool parsed = am::ParseScene(text, &scene, &error);
    env->ReleaseStringUTFChars(xml, text);
    if (!parsed) return nullptr;
    const jsize n = env->GetArrayLength(ids);
    std::vector<jlong> id((size_t)n);
    std::vector<jdouble> at((size_t)n);
    env->GetLongArrayRegion(ids, 0, n, id.data());
    env->GetDoubleArrayRegion(times, 0, n, at.data());
    constexpr int kEach = 33;
    std::vector<float> out((size_t)n * kEach, NAN);
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    for (jsize i = 0; i < n; i++) {
        const am::Layer* layer = scene.layer(id[(size_t)i]);
        if (!layer) continue;
        float* o = out.data() + (size_t)i * kEach;
        am::WorldMatrix(scene, *layer, at[(size_t)i]).get9(o);
        const am::Placement p = am::EffectivePlacement(scene.layers, *layer, at[(size_t)i]);
        const float place[] = {p.location.fX, p.location.fY, p.z, p.pivot.fX, p.pivot.fY, p.scale.fX, p.scale.fY, p.rotation};
        for (int k = 0; k < 8; k++) o[9 + k] = place[k];
        p.tilt.getRowMajor(o + 17);
    }
    jfloatArray result = env->NewFloatArray((jsize)out.size());
    if (result) env->SetFloatArrayRegion(result, 0, (jsize)out.size(), out.data());
    return result;
}
