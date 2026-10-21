package app.absentmotion.export

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import java.util.Locale

data class Choice<T>(val value: T, val reason: UiText? = null) {
    val enabled: Boolean get() = reason == null
}

val Codec.shortName: String get() = label.substringBefore('/')

fun sizeLabel(shortSide: Int): String = if (shortSide >= 2160) "4K" else "${shortSide}p"

fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0)

fun channelsLabel(channels: Int): UiText = txt(if (channels == 1) R.string.export_channels_mono else R.string.export_channels_stereo)

fun audioSummary(a: AudioSettings): UiText = txt(R.string.export_audio_summary, a.codec.label, "${a.bitrateKbps}", khz(a.effectiveRate), channelsLabel(a.channels))

fun audioOnlySummary(s: ExportSettings): UiText =
    if (s.audioFormat.lossless) txt(R.string.export_audio_summary_lossless, txt(s.audioFormat.label), khz(audioRate(s)), channelsLabel(s.audio.channels))
    else txt(R.string.export_audio_summary, txt(s.audioFormat.label), "${s.audio.bitrateKbps}", khz(audioRate(s)), channelsLabel(s.audio.channels))

fun listed(items: List<String>, @StringRes join: Int): Any =
    items.drop(1).fold<String, Any>(items.first()) { list, next -> txt(join, list, next) }

private fun ExportSettings.fitAudio(): ExportSettings =
    if (audio.codec in audioCodecsFor(container)) this else copy(audio = audio.copy(codec = audioCodecsFor(container).first()))

private fun unsupported(support: Support): UiText? = (support as? Support.Unsupported)?.reason

fun containerChoices(s: ExportSettings, check: (ExportSettings) -> Support): List<Choice<Container>> = Container.entries.map { c ->
    val codecs = videoCodecsFor(c)
    val possible = codecs.any { check(s.copy(container = c, codec = it).fitAudio()) !is Support.Unsupported }
    Choice(c, if (possible) null else txt(R.string.export_reason_container_codecs, c.label, listed(codecs.map { it.shortName }, R.string.export_list_or), sizeLabel(s.shortSide)))
}

fun withContainer(s: ExportSettings, c: Container, check: (ExportSettings) -> Support): ExportSettings {
    val options = (listOf(s.codec) + videoCodecsFor(c)).distinct().filter { it in videoCodecsFor(c) }.map { s.copy(container = c, codec = it).fitAudio() }
    return options.firstOrNull { check(it) is Support.Supported } ?: options.firstOrNull { check(it) !is Support.Unsupported } ?: options.first()
}

fun codecChoices(s: ExportSettings, check: (ExportSettings) -> Support): List<Choice<Codec>> =
    videoCodecsFor(s.container).map { Choice(it, unsupported(check(s.copy(codec = it)))) }

fun resolutionChoices(s: ExportSettings, check: (ExportSettings) -> Support): List<Choice<Resolution>> =
    VideoResolutions.map { Choice(it, unsupported(check(s.copy(shortSide = it.shortSide)))) }

fun audioCodecChoices(container: Container, hasEncoder: (String) -> Boolean): List<Choice<AudioCodec>> = AudioCodec.entries.map { a ->
    Choice(a, when {
        a !in audioCodecsFor(container) ->
            txt(R.string.export_reason_audio_container, container.label, a.label, listed(Container.entries.filter { a in audioCodecsFor(it) }.map { it.label }, R.string.export_list_and))
        !hasEncoder(a.mime) -> txt(R.string.export_support_no_encoder, a.label)
        else -> null
    })
}

fun sampleRateChoices(only48: Any?): List<Choice<Int>> =
    AudioSampleRates.map { Choice(it, if (only48 != null && it != 48000) txt(R.string.export_reason_only_48k, only48) else null) }

fun bitrateChoices(format: AudioFormat?): List<Choice<Int>> =
    AudioBitratesKbps.map { Choice(it, if (format?.lossless == true) txt(R.string.export_reason_lossless, txt(format.label)) else null) }

fun audioFormatChoices(hasEncoder: (String) -> Boolean): List<Choice<AudioFormat>> = AudioFormat.entries.map { f ->
    val mime = when (f) {
        AudioFormat.Flac -> "audio/flac"
        AudioFormat.M4a -> AudioCodec.Aac.mime
        AudioFormat.Ogg -> AudioCodec.Opus.mime
        else -> null
    }
    Choice(f, if (mime != null && !hasEncoder(mime)) txt(R.string.export_support_no_encoder, txt(f.label)) else null)
}

fun codecWarning(s: ExportSettings, support: Support): UiText? = when (support) {
    Support.Supported -> null
    is Support.SlowSoftwareOnly -> txt(R.string.export_warning_slow, s.codec.shortName, sizeLabel(s.shortSide), "${s.fps}")
    is Support.Unsupported -> txt(R.string.export_warning_unsupported, support.reason)
}

fun betterChoice(s: ExportSettings, support: Support, check: (ExportSettings) -> Support): ExportSettings? {
    if (support is Support.Supported) return null
    (support as? Support.Unsupported)?.suggestion?.let { return it }
    val codecs = (listOf(s.codec, Codec.H264, Codec.Hevc) + Codec.entries).distinct()
    val sides = listOf(s.shortSide) + VideoResolutions.map { it.shortSide }.filter { it < s.shortSide }
    return sides.asSequence()
        .flatMap { side -> codecs.asSequence().map { s.copy(shortSide = side, codec = it) } }
        .map { if (it.codec in videoCodecsFor(it.container)) it.fitAudio() else it.fixedFormat() }
        .firstOrNull { it != s && check(it) is Support.Supported }
}

fun suggestionLabel(from: ExportSettings, to: ExportSettings): UiText = listOfNotNull(
    to.codec.shortName.takeIf { to.codec != from.codec },
    sizeLabel(to.shortSide).takeIf { to.shortSide != from.shortSide },
    "${to.fps}fps".takeIf { to.fps != from.fps },
    to.container.label.takeIf { to.container != from.container },
    to.audio.codec.label.takeIf { to.audio.codec != from.audio.codec },
).joinToString(" · ").let { if (it.isEmpty()) txt(R.string.export_use_suggested) else txt(R.string.export_use, it) }

fun exportBlock(storage: StorageStatus, support: Support): UiText? = when {
    storage is StorageStatus.NotEnough -> txt(R.string.export_block_storage)
    support is Support.Unsupported -> txt(R.string.export_block_unsupported)
    else -> null
}

fun thermalLabel(level: ThermalLevel): UiText = txt(when (level) {
    ThermalLevel.Normal -> R.string.export_heat_normal
    ThermalLevel.Warm -> R.string.export_heat_warm
    ThermalLevel.Hot -> R.string.export_heat_hot
    ThermalLevel.Critical -> R.string.export_heat_critical
})

fun exportHeatText(level: ThermalLevel): UiText? = when (level) {
    ThermalLevel.Hot -> txt(R.string.export_heat_text_hot)
    ThermalLevel.Critical -> txt(R.string.export_heat_text_critical)
    else -> null
}

fun verificationLine(v: Verification, still: Boolean = false): UiText =
    if (v.ok) txt(if (still) R.string.export_checked_ok_still else R.string.export_checked_ok) else txt(R.string.export_checked_problems, v.problems)

fun app.absentmotion.model.Scene.withMissing(uris: Set<String>): app.absentmotion.model.Scene {
    val declared = uris.fold(this) { s, uri -> s.withMedia(app.absentmotion.model.MediaDeclaration(app.absentmotion.model.XmlNode("media", mapOf("uri" to uri)))) }
    return app.absentmotion.model.Scene(declared.node.copy(children = declared.node.children.map { if (it.name == "media" && it["uri"] in uris) it.with("missing", "true") else it }))
}
