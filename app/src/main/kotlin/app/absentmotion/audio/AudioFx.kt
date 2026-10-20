package app.absentmotion.audio

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import java.math.BigDecimal
import java.math.MathContext

class FxParam(
    val key: String, val label: String, val min: Float, val max: Float, val default: Float,
    val unit: String = "", val log: Boolean = false, val choices: List<String> = emptyList(),
)

enum class FxKind(val id: Int, val xml: String, val label: String, val params: List<FxParam>) {
    Eq(0, "eq", "Equalizer", listOf(
        FxParam("lowFreq", "Low Freq", 20f, 1000f, 120f, "Hz", log = true),
        FxParam("lowGain", "Low Gain", -24f, 24f, 0f, "dB"),
        FxParam("mid1Freq", "Mid 1 Freq", 20f, 20000f, 400f, "Hz", log = true),
        FxParam("mid1Gain", "Mid 1 Gain", -24f, 24f, 0f, "dB"),
        FxParam("mid1Q", "Mid 1 Q", 0.1f, 10f, 1f, "Q", log = true),
        FxParam("mid2Freq", "Mid 2 Freq", 20f, 20000f, 1500f, "Hz", log = true),
        FxParam("mid2Gain", "Mid 2 Gain", -24f, 24f, 0f, "dB"),
        FxParam("mid2Q", "Mid 2 Q", 0.1f, 10f, 1f, "Q", log = true),
        FxParam("mid3Freq", "Mid 3 Freq", 20f, 20000f, 5000f, "Hz", log = true),
        FxParam("mid3Gain", "Mid 3 Gain", -24f, 24f, 0f, "dB"),
        FxParam("mid3Q", "Mid 3 Q", 0.1f, 10f, 1f, "Q", log = true),
        FxParam("highFreq", "High Freq", 1000f, 20000f, 10000f, "Hz", log = true),
        FxParam("highGain", "High Gain", -24f, 24f, 0f, "dB"),
    )),
    LowPass(1, "lowpass", "Low-pass", filter(5000f)),
    HighPass(2, "highpass", "High-pass", filter(200f)),
    Reverb(3, "reverb", "Reverb", listOf(
        FxParam("room", "Room Size", 0f, 1f, 0.5f, "%"),
        FxParam("damping", "Damping", 0f, 1f, 0.5f, "%"),
        FxParam("wet", "Wet", 0f, 1f, 0.3f, "%"),
        FxParam("dry", "Dry", 0f, 1f, 1f, "%"),
        FxParam("width", "Width", 0f, 1f, 1f, "%"),
        FxParam("preDelay", "Pre-delay", 0f, 200f, 0f, "ms"),
    )),
    Echo(4, "echo", "Echo", listOf(
        FxParam("time", "Time", 1f, 2000f, 250f, "ms", log = true),
        FxParam("feedback", "Feedback", 0f, 0.95f, 0.35f, "%"),
        FxParam("level", "Echo Level", 0f, 1f, 0.3f, "%"),
        FxParam("pingPong", "Ping-pong", 0f, 1f, 0f, choices = listOf("Off", "On")),
        FxParam("lowCut", "Low Cut", 20f, 2000f, 80f, "Hz", log = true),
    )),
    Compressor(5, "compressor", "Compressor", listOf(
        FxParam("threshold", "Threshold", -60f, 0f, -18f, "dB"),
        FxParam("ratio", "Ratio", 1f, 20f, 4f, ":1", log = true),
        FxParam("attack", "Attack", 0.1f, 200f, 10f, "ms", log = true),
        FxParam("release", "Release", 5f, 2000f, 100f, "ms", log = true),
        FxParam("knee", "Knee", 0f, 24f, 6f, "dB"),
        FxParam("makeup", "Makeup", 0f, 24f, 0f, "dB"),
    )),
    Gate(6, "gate", "Noise Gate", listOf(
        FxParam("threshold", "Threshold", -100f, 0f, -50f, "dB"),
        FxParam("attack", "Attack", 0.1f, 200f, 5f, "ms", log = true),
        FxParam("hold", "Hold", 0f, 1000f, 50f, "ms"),
        FxParam("release", "Release", 5f, 2000f, 100f, "ms", log = true),
        FxParam("range", "Range", 0f, 100f, 80f, "dB"),
    )),
}

private fun filter(cutoff: Float) = listOf(
    FxParam("freq", "Cutoff", 20f, 20000f, cutoff, "Hz", log = true),
    FxParam("resonance", "Resonance", 0.1f, 10f, 0.70710677f, "Q", log = true),
    FxParam("slope", "Slope", 12f, 24f, 12f, choices = listOf("12 dB/oct", "24 dB/oct")),
)

@JvmInline
value class AudioFx(val node: XmlNode) {
    val kind: FxKind? get() = FxKind.entries.firstOrNull { it.xml == node["type"] }
    val mix: Float get() = node["mix"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val bypass: Boolean get() = node["bypass"] == "true"

    operator fun get(param: FxParam): Float {
        val value = node[param.key]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(param.min, param.max) ?: return param.default
        return if (param.choices.isEmpty()) value else if (value >= (param.min + param.max) / 2) param.max else param.min
    }

    fun with(param: FxParam, value: Float) = AudioFx(node.with(param.key, number(value.coerceIn(param.min, param.max))))
    fun withMix(value: Float) = AudioFx(node.with("mix", number(value.coerceIn(0f, 1f))))
    fun withBypass(value: Boolean) = AudioFx(node.with("bypass", if (value) "true" else null))
    fun reset(): AudioFx {
        val keys = kind?.params?.map { it.key }.orEmpty() + "mix"
        return AudioFx(node.copy(attrs = node.attrs.filterKeys { it !in keys }))
    }

    companion object {
        fun of(kind: FxKind, vararg values: Pair<String, Float>) = values.fold(AudioFx(XmlNode(FX_TAG, linkedMapOf("type" to kind.xml)))) { fx, (key, value) ->
            fx.with(kind.params.first { it.key == key }, value)
        }
    }
}

const val AUDIO_FX_TAG = "audioEffects"
private const val FX_TAG = "fx"
const val MAX_AUDIO_FX = 16

val Layer.audioEffects: List<AudioFx> get() = node.child(AUDIO_FX_TAG)?.children?.filter { it.name == FX_TAG }?.map(::AudioFx) ?: emptyList()

fun Layer.withAudioEffects(effects: List<AudioFx>): Layer {
    val old = node.child(AUDIO_FX_TAG)
    val others = old?.children?.filter { it.name != FX_TAG }.orEmpty()
    val next = if (effects.isEmpty() && others.isEmpty() && old?.attrs.isNullOrEmpty()) null
        else (old ?: XmlNode(AUDIO_FX_TAG)).copy(children = effects.map { it.node } + others)
    return copy(node = node.withChild(AUDIO_FX_TAG, next))
}

fun Layer.updateAudioEffect(index: Int, change: (AudioFx) -> AudioFx): Layer =
    withAudioEffects(audioEffects.mapIndexed { i, fx -> if (i == index) change(fx) else fx })

fun Layer.moveAudioEffect(from: Int, to: Int): Layer {
    val list = audioEffects.toMutableList()
    if (from !in list.indices || to !in list.indices) return this
    list.add(to, list.removeAt(from))
    return withAudioEffects(list)
}

internal class FxSpec(val kinds: IntArray, val values: FloatArray)

internal fun List<AudioFx>.spec(): FxSpec? {
    val known = mapNotNull { fx -> fx.kind?.let { fx to it } }.take(MAX_AUDIO_FX)
    if (known.isEmpty()) return null
    val values = FloatArray(known.sumOf { 2 + it.second.params.size })
    var at = 0
    for ((fx, kind) in known) {
        values[at++] = fx.mix
        values[at++] = if (fx.bypass) 1f else 0f
        for (param in kind.params) values[at++] = fx[param]
    }
    return FxSpec(IntArray(known.size) { known[it].second.id }, values)
}

class AudioPreset(val name: String, val effects: List<AudioFx>)

val AudioPresets: List<AudioPreset> by lazy { listOf(
    AudioPreset("Radio", listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 500f),
        AudioFx.of(FxKind.LowPass, "freq" to 4000f, "slope" to 24f),
        AudioFx.of(FxKind.Eq, "mid2Freq" to 1800f, "mid2Gain" to 5f, "mid2Q" to 1.2f),
        AudioFx.of(FxKind.Compressor, "threshold" to -24f, "ratio" to 6f, "attack" to 3f, "release" to 80f, "makeup" to 8f),
    )),
    AudioPreset("Telephone", listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 300f, "slope" to 24f),
        AudioFx.of(FxKind.LowPass, "freq" to 3400f, "slope" to 24f),
        AudioFx.of(FxKind.Eq, "mid1Freq" to 1000f, "mid1Gain" to 6f, "mid1Q" to 0.8f),
        AudioFx.of(FxKind.Compressor, "threshold" to -20f, "ratio" to 8f, "makeup" to 6f),
    )),
    AudioPreset("Small Room", listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.35f, "damping" to 0.6f, "wet" to 0.22f, "width" to 0.8f, "preDelay" to 4f),
    )),
    AudioPreset("Hall", listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.78f, "damping" to 0.45f, "wet" to 0.35f, "dry" to 0.9f, "preDelay" to 22f),
    )),
    AudioPreset("Stadium", listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.95f, "damping" to 0.3f, "wet" to 0.45f, "dry" to 0.85f, "preDelay" to 55f),
        AudioFx.of(FxKind.Echo, "time" to 260f, "feedback" to 0.2f, "level" to 0.18f, "lowCut" to 200f),
    )),
    AudioPreset("Underwater", listOf(
        AudioFx.of(FxKind.LowPass, "freq" to 500f, "resonance" to 2.5f, "slope" to 24f),
        AudioFx.of(FxKind.Reverb, "room" to 0.6f, "damping" to 0.85f, "wet" to 0.35f, "dry" to 0.8f, "width" to 0.6f, "preDelay" to 10f),
    )),
    AudioPreset("Echo", listOf(
        AudioFx.of(FxKind.Echo, "time" to 380f, "feedback" to 0.45f, "level" to 0.35f, "pingPong" to 1f, "lowCut" to 120f),
    )),
    AudioPreset("Bass Boost", listOf(
        AudioFx.of(FxKind.Eq, "lowFreq" to 110f, "lowGain" to 8f, "mid1Freq" to 300f, "mid1Gain" to -2f),
    )),
    AudioPreset("Vocal Clarity", listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 90f),
        AudioFx.of(FxKind.Eq, "mid1Freq" to 250f, "mid1Gain" to -3f, "mid2Freq" to 3200f, "mid2Gain" to 4f, "mid2Q" to 0.9f, "highGain" to 3f),
        AudioFx.of(FxKind.Compressor, "threshold" to -22f, "ratio" to 3f, "attack" to 5f, "release" to 120f, "makeup" to 4f),
    )),
    AudioPreset("Noise Gate", listOf(
        AudioFx.of(FxKind.Gate, "threshold" to -45f, "attack" to 2f, "hold" to 80f, "release" to 150f, "range" to 60f),
    )),
) }

private fun number(value: Float): String =
    BigDecimal(value.toDouble()).round(MathContext(6)).stripTrailingZeros().toPlainString()
