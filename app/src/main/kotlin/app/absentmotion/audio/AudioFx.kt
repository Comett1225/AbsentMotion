package app.absentmotion.audio

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.pow

class FxParam(
    val key: String, @StringRes val label: Int, val min: Float, val max: Float, val default: Float,
    val unit: String = "", val log: Boolean = false, @StringRes val choices: List<Int> = emptyList(),
)

enum class FxKind(val id: Int, val xml: String, @StringRes val label: Int, val params: List<FxParam>, val units: Int = 1) {
    Eq(0, "eq", R.string.audio_fx_equalizer, listOf(
        FxParam("lowFreq", R.string.audio_param_low_freq, 20f, 1000f, 120f, "Hz", log = true),
        FxParam("lowGain", R.string.audio_param_low_gain, -24f, 24f, 0f, "dB"),
        FxParam("mid1Freq", R.string.audio_param_mid_1_freq, 20f, 20000f, 400f, "Hz", log = true),
        FxParam("mid1Gain", R.string.audio_param_mid_1_gain, -24f, 24f, 0f, "dB"),
        FxParam("mid1Q", R.string.audio_param_mid_1_q, 0.1f, 10f, 1f, "Q", log = true),
        FxParam("mid2Freq", R.string.audio_param_mid_2_freq, 20f, 20000f, 1500f, "Hz", log = true),
        FxParam("mid2Gain", R.string.audio_param_mid_2_gain, -24f, 24f, 0f, "dB"),
        FxParam("mid2Q", R.string.audio_param_mid_2_q, 0.1f, 10f, 1f, "Q", log = true),
        FxParam("mid3Freq", R.string.audio_param_mid_3_freq, 20f, 20000f, 5000f, "Hz", log = true),
        FxParam("mid3Gain", R.string.audio_param_mid_3_gain, -24f, 24f, 0f, "dB"),
        FxParam("mid3Q", R.string.audio_param_mid_3_q, 0.1f, 10f, 1f, "Q", log = true),
        FxParam("highFreq", R.string.audio_param_high_freq, 1000f, 20000f, 10000f, "Hz", log = true),
        FxParam("highGain", R.string.audio_param_high_gain, -24f, 24f, 0f, "dB"),
    )),
    LowPass(1, "lowpass", R.string.audio_fx_low_pass, filter(5000f)),
    HighPass(2, "highpass", R.string.audio_fx_high_pass, filter(200f)),
    Reverb(3, "reverb", R.string.audio_fx_reverb, listOf(
        FxParam("room", R.string.audio_param_room_size, 0f, 1f, 0.5f, "%"),
        FxParam("damping", R.string.audio_param_damping, 0f, 1f, 0.5f, "%"),
        FxParam("wet", R.string.audio_param_wet, 0f, 1f, 0.3f, "%"),
        FxParam("dry", R.string.audio_param_dry, 0f, 1f, 1f, "%"),
        FxParam("width", R.string.audio_param_width, 0f, 1f, 1f, "%"),
        FxParam("preDelay", R.string.audio_param_pre_delay, 0f, 200f, 0f, "ms"),
    )),
    Echo(4, "echo", R.string.audio_fx_echo, listOf(
        FxParam("time", R.string.audio_param_time, 1f, 2000f, 250f, "ms", log = true),
        FxParam("feedback", R.string.audio_param_feedback, 0f, 0.95f, 0.35f, "%"),
        FxParam("level", R.string.audio_param_echo_level, 0f, 1f, 0.3f, "%"),
        FxParam("pingPong", R.string.audio_param_ping_pong, 0f, 1f, 0f, choices = listOf(R.string.audio_choice_off, R.string.audio_choice_on)),
        FxParam("lowCut", R.string.audio_param_low_cut, 20f, 2000f, 80f, "Hz", log = true),
    )),
    Compressor(5, "compressor", R.string.audio_fx_compressor, listOf(
        FxParam("threshold", R.string.audio_param_threshold, -60f, 0f, -18f, "dB"),
        FxParam("ratio", R.string.audio_param_ratio, 1f, 20f, 4f, ":1", log = true),
        FxParam("attack", R.string.audio_param_attack, 0.1f, 200f, 10f, "ms", log = true),
        FxParam("release", R.string.audio_param_release, 5f, 2000f, 100f, "ms", log = true),
        FxParam("knee", R.string.audio_param_knee, 0f, 24f, 6f, "dB"),
        FxParam("makeup", R.string.audio_param_makeup, 0f, 24f, 0f, "dB"),
    )),
    Gate(6, "gate", R.string.audio_fx_noise_gate, listOf(
        FxParam("threshold", R.string.audio_param_threshold, -100f, 0f, -50f, "dB"),
        FxParam("attack", R.string.audio_param_attack, 0.1f, 200f, 5f, "ms", log = true),
        FxParam("hold", R.string.audio_param_hold, 0f, 1000f, 50f, "ms"),
        FxParam("release", R.string.audio_param_release, 5f, 2000f, 100f, "ms", log = true),
        FxParam("range", R.string.audio_param_range, 0f, 100f, 80f, "dB"),
    )),
    HighPitch(NativeFx.PITCH, "highpitch", R.string.audio_fx_high_pitch, listOf(
        FxParam("amount", R.string.audio_param_amount, 0f, 12f, 5f, "st"),
    )),
    LowPitch(NativeFx.PITCH, "lowpitch", R.string.audio_fx_low_pitch, listOf(
        FxParam("amount", R.string.audio_param_amount, 0f, 12f, 5f, "st"),
    )),
    Voice(-1, "voice", R.string.audio_fx_voice_filter, listOf(
        FxParam("pitch", R.string.audio_param_pitch, -12f, 12f, 0f, "st"),
        FxParam("bass", R.string.audio_param_bass, -12f, 12f, 0f, "dB"),
        FxParam("treble", R.string.audio_param_treble, -12f, 12f, 0f, "dB"),
        FxParam("muffle", R.string.audio_param_muffle, 0f, 1f, 0f, "%"),
        FxParam("thin", R.string.audio_param_thin, 0f, 1f, 0f, "%"),
        FxParam("full", R.string.audio_param_fullness, 0f, 1f, 0f, "%"),
        FxParam("echo", R.string.audio_fx_echo, 0f, 1f, 0f, "%"),
        FxParam("reverb", R.string.audio_fx_reverb, 0f, 1f, 0f, "%"),
        FxParam("pingPong", R.string.audio_param_ping_pong, 0f, 1f, 0f, choices = listOf(R.string.audio_choice_off, R.string.audio_choice_on)),
    ), units = 7),
}

private fun filter(cutoff: Float) = listOf(
    FxParam("freq", R.string.audio_param_cutoff, 20f, 20000f, cutoff, "Hz", log = true),
    FxParam("resonance", R.string.audio_param_resonance, 0.1f, 10f, 0.70710677f, "Q", log = true),
    FxParam("slope", R.string.audio_param_slope, 12f, 24f, 12f, choices = listOf(R.string.audio_slope_12, R.string.audio_slope_24)),
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

internal class FxUnit(val native: Int, val mix: Float, val bypass: Boolean, val values: FloatArray)

private fun AudioFx.units(kind: FxKind): List<FxUnit> = when (kind) {
    FxKind.HighPitch -> listOf(FxUnit(kind.id, mix, bypass, floatArrayOf(this[kind.params[0]])))
    FxKind.LowPitch -> listOf(FxUnit(kind.id, mix, bypass, floatArrayOf(-this[kind.params[0]])))
    FxKind.Voice -> voiceUnits()
    else -> listOf(FxUnit(kind.id, mix, bypass, FloatArray(kind.params.size) { this[kind.params[it]] }))
}

private fun AudioFx.voiceUnits(): List<FxUnit> {
    val p = FxKind.Voice.params
    val pitch = this[p[0]]
    val bass = this[p[1]]
    val treble = this[p[2]]
    val muffle = this[p[3]]
    val thin = this[p[4]]
    val full = this[p[5]]
    val echo = this[p[6]]
    val reverb = this[p[7]]
    val pingPong = this[p[8]]
    fun unit(native: Int, off: Boolean, vararg values: Float) = FxUnit(native, mix, bypass || off, values)
    return listOf(
        unit(NativeFx.PITCH, abs(pitch) < 0.05f, pitch),
        unit(FxKind.Eq.id, bass == 0f && treble == 0f, 150f, bass, 400f, 0f, 1f, 1500f, 0f, 1f, 5000f, 0f, 1f, 4500f, treble),
        unit(FxKind.HighPass.id, thin < 0.01f, 20f * 60f.pow(thin), 0.70710677f, 12f),
        unit(FxKind.LowPass.id, muffle < 0.01f, 20000f * 0.02f.pow(muffle), 0.70710677f, 24f),
        unit(FxKind.Compressor.id, full < 0.01f, -20f - 10f * full, 1.5f + 5f * full, 8f, 140f, 8f, 10f * full),
        unit(FxKind.Echo.id, echo < 0.01f, 300f, 0.35f, 0.5f * echo, pingPong, 120f),
        unit(FxKind.Reverb.id, reverb < 0.01f, 0.3f + 0.65f * reverb, 0.5f, 0.55f * reverb, 1f, 1f, 12f),
    )
}

fun List<AudioFx>.fits(kind: FxKind): Boolean = sumOf { it.kind?.units ?: 0 } + kind.units <= MAX_AUDIO_FX

internal fun List<AudioFx>.spec(): FxSpec? {
    val units = mapNotNull { fx -> fx.kind?.let(fx::units) }.flatten().take(MAX_AUDIO_FX)
    if (units.isEmpty()) return null
    val values = FloatArray(units.sumOf { 2 + it.values.size })
    var at = 0
    for (unit in units) {
        values[at++] = unit.mix
        values[at++] = if (unit.bypass) 1f else 0f
        for (value in unit.values) values[at++] = value
    }
    return FxSpec(IntArray(units.size) { units[it].native }, values)
}

class AudioPreset(@StringRes val name: Int, val effects: List<AudioFx>)

val AudioPresets: List<AudioPreset> by lazy { listOf(
    AudioPreset(R.string.audio_preset_radio, listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 500f),
        AudioFx.of(FxKind.LowPass, "freq" to 4000f, "slope" to 24f),
        AudioFx.of(FxKind.Eq, "mid2Freq" to 1800f, "mid2Gain" to 5f, "mid2Q" to 1.2f),
        AudioFx.of(FxKind.Compressor, "threshold" to -24f, "ratio" to 6f, "attack" to 3f, "release" to 80f, "makeup" to 8f),
    )),
    AudioPreset(R.string.audio_preset_telephone, listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 300f, "slope" to 24f),
        AudioFx.of(FxKind.LowPass, "freq" to 3400f, "slope" to 24f),
        AudioFx.of(FxKind.Eq, "mid1Freq" to 1000f, "mid1Gain" to 6f, "mid1Q" to 0.8f),
        AudioFx.of(FxKind.Compressor, "threshold" to -20f, "ratio" to 8f, "makeup" to 6f),
    )),
    AudioPreset(R.string.audio_preset_small_room, listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.35f, "damping" to 0.6f, "wet" to 0.22f, "width" to 0.8f, "preDelay" to 4f),
    )),
    AudioPreset(R.string.audio_preset_hall, listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.78f, "damping" to 0.45f, "wet" to 0.35f, "dry" to 0.9f, "preDelay" to 22f),
    )),
    AudioPreset(R.string.audio_preset_stadium, listOf(
        AudioFx.of(FxKind.Reverb, "room" to 0.95f, "damping" to 0.3f, "wet" to 0.45f, "dry" to 0.85f, "preDelay" to 55f),
        AudioFx.of(FxKind.Echo, "time" to 260f, "feedback" to 0.2f, "level" to 0.18f, "lowCut" to 200f),
    )),
    AudioPreset(R.string.audio_preset_underwater, listOf(
        AudioFx.of(FxKind.LowPass, "freq" to 500f, "resonance" to 2.5f, "slope" to 24f),
        AudioFx.of(FxKind.Reverb, "room" to 0.6f, "damping" to 0.85f, "wet" to 0.35f, "dry" to 0.8f, "width" to 0.6f, "preDelay" to 10f),
    )),
    AudioPreset(R.string.audio_fx_echo, listOf(
        AudioFx.of(FxKind.Echo, "time" to 380f, "feedback" to 0.45f, "level" to 0.35f, "pingPong" to 1f, "lowCut" to 120f),
    )),
    AudioPreset(R.string.audio_preset_bass_boost, listOf(
        AudioFx.of(FxKind.Eq, "lowFreq" to 110f, "lowGain" to 8f, "mid1Freq" to 300f, "mid1Gain" to -2f),
    )),
    AudioPreset(R.string.audio_preset_vocal_clarity, listOf(
        AudioFx.of(FxKind.HighPass, "freq" to 90f),
        AudioFx.of(FxKind.Eq, "mid1Freq" to 250f, "mid1Gain" to -3f, "mid2Freq" to 3200f, "mid2Gain" to 4f, "mid2Q" to 0.9f, "highGain" to 3f),
        AudioFx.of(FxKind.Compressor, "threshold" to -22f, "ratio" to 3f, "attack" to 5f, "release" to 120f, "makeup" to 4f),
    )),
    AudioPreset(R.string.audio_fx_noise_gate, listOf(
        AudioFx.of(FxKind.Gate, "threshold" to -45f, "attack" to 2f, "hold" to 80f, "release" to 150f, "range" to 60f),
    )),
) }

class VoicePreset(@StringRes val name: Int, val effect: AudioFx)

val VoicePresets: List<VoicePreset> by lazy { listOf(
    VoicePreset(R.string.voice_helium, AudioFx.of(FxKind.Voice, "pitch" to 8f, "treble" to 3f)),
    VoicePreset(R.string.voice_deep, AudioFx.of(FxKind.Voice, "pitch" to -5f, "bass" to 5f, "full" to 0.3f)),
    VoicePreset(R.string.audio_preset_bass_boost, AudioFx.of(FxKind.Voice, "bass" to 9f, "full" to 0.2f)),
    VoicePreset(R.string.voice_full, AudioFx.of(FxKind.Voice, "bass" to 4f, "treble" to 2f, "full" to 0.55f)),
    VoicePreset(R.string.audio_preset_vocal_clarity, AudioFx.of(FxKind.Voice, "thin" to 0.2f, "treble" to 4f, "full" to 0.4f)),
    VoicePreset(R.string.voice_echoey, AudioFx.of(FxKind.Voice, "echo" to 0.55f, "reverb" to 0.15f)),
    VoicePreset(R.string.voice_ethereal, AudioFx.of(FxKind.Voice, "pitch" to 2f, "treble" to 3f, "echo" to 0.2f, "reverb" to 0.75f, "pingPong" to 1f)),
    VoicePreset(R.string.audio_preset_underwater, AudioFx.of(FxKind.Voice, "muffle" to 0.85f, "reverb" to 0.4f)),
    VoicePreset(R.string.voice_cheap_mic, AudioFx.of(FxKind.Voice, "thin" to 0.55f, "muffle" to 0.45f, "full" to 0.6f)),
    VoicePreset(R.string.audio_preset_telephone, AudioFx.of(FxKind.Voice, "thin" to 0.7f, "muffle" to 0.45f, "full" to 0.4f)),
) }

fun Layer.withVoicePreset(preset: VoicePreset): Layer {
    val effects = audioEffects
    val at = effects.indexOfFirst { it.kind == FxKind.Voice }
    return withAudioEffects(if (at < 0) effects + preset.effect else effects.mapIndexed { i, fx -> if (i == at) preset.effect else fx })
}

private fun number(value: Float): String =
    BigDecimal(value.toDouble()).round(MathContext(6)).stripTrailingZeros().toPlainString()
