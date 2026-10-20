package app.absentmotion.export

import app.absentmotion.i18n.AppText

import java.util.Locale

data class Choice<T>(val value: T, val reason: String? = null) {
    val enabled: Boolean get() = reason == null
}

val Codec.shortName: String get() = label.substringBefore('/')

fun sizeLabel(shortSide: Int): String = if (shortSide >= 2160) "4K" else "${shortSide}p"

fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0)

fun channelsLabel(channels: Int): String = if (channels == 1) "Mono" else "Stereo"

fun audioSummary(a: AudioSettings): String = "${a.codec.label} · ${a.bitrateKbps} kbps · ${khz(a.effectiveRate)} · ${channelsLabel(a.channels)}"

fun audioOnlySummary(s: ExportSettings): String = listOfNotNull(
    s.audioFormat.label,
    if (s.audioFormat.lossless) null else "${s.audio.bitrateKbps} kbps",
    khz(audioRate(s)),
    channelsLabel(s.audio.channels),
).joinToString(" · ")

private fun ExportSettings.fitAudio(): ExportSettings =
    if (audio.codec in audioCodecsFor(container)) this else copy(audio = audio.copy(codec = audioCodecsFor(container).first()))

private fun unsupported(support: Support): String? = (support as? Support.Unsupported)?.reason?.let(AppText::get)

fun containerChoices(s: ExportSettings, check: (ExportSettings) -> Support): List<Choice<Container>> = Container.entries.map { c ->
    val codecs = videoCodecsFor(c)
    val possible = codecs.any { check(s.copy(container = c, codec = it).fitAudio()) !is Support.Unsupported }
    Choice(c, if (possible) null else "${c.label} needs ${codecs.joinToString(" or ") { it.shortName }} video, which this phone cannot make at ${sizeLabel(s.shortSide)}.")
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
            "${container.label} files cannot hold ${a.label} audio. ${a.label} works in ${Container.entries.filter { a in audioCodecsFor(it) }.joinToString(" and ") { it.label }}."
        !hasEncoder(a.mime) -> "This phone has no ${a.label} encoder."
        else -> null
    })
}

fun sampleRateChoices(only48: String?): List<Choice<Int>> =
    AudioSampleRates.map { Choice(it, if (only48 != null && it != 48000) "$only48 always uses 48 kHz." else null) }

fun bitrateChoices(format: AudioFormat?): List<Choice<Int>> =
    AudioBitratesKbps.map { Choice(it, if (format?.lossless == true) "${format.label} is lossless, so it has no bit rate to pick." else null) }

fun audioFormatChoices(hasEncoder: (String) -> Boolean): List<Choice<AudioFormat>> = AudioFormat.entries.map { f ->
    val mime = when (f) {
        AudioFormat.Flac -> "audio/flac"
        AudioFormat.M4a -> AudioCodec.Aac.mime
        AudioFormat.Ogg -> AudioCodec.Opus.mime
        else -> null
    }
    Choice(f, if (mime != null && !hasEncoder(mime)) "This phone has no ${f.label} encoder." else null)
}

fun codecWarning(s: ExportSettings, support: Support): String? = when (support) {
    Support.Supported -> null
    is Support.SlowSoftwareOnly -> "Your device does not support hardware ${s.codec.shortName} encoding at ${sizeLabel(s.shortSide)} ${s.fps}fps. " +
        "Software encoding may be available, but exporting could take significantly longer."
    is Support.Unsupported -> "${support.reason} These settings cannot be exported on this phone."
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

fun suggestionLabel(from: ExportSettings, to: ExportSettings): String = "Use " + listOfNotNull(
    to.codec.shortName.takeIf { to.codec != from.codec },
    sizeLabel(to.shortSide).takeIf { to.shortSide != from.shortSide },
    "${to.fps}fps".takeIf { to.fps != from.fps },
    to.container.label.takeIf { to.container != from.container },
    to.audio.codec.label.takeIf { to.audio.codec != from.audio.codec },
).joinToString(" · ").ifEmpty { "suggested settings" }

fun exportBlock(storage: StorageStatus, support: Support): String? = when {
    storage is StorageStatus.NotEnough -> "Not enough storage"
    support is Support.Unsupported -> "Not supported on this phone"
    else -> null
}

fun thermalLabel(level: ThermalLevel): String = when (level) {
    ThermalLevel.Normal -> "Normal"
    ThermalLevel.Warm -> "Warm"
    ThermalLevel.Hot -> "Hot — exports slowed"
    ThermalLevel.Critical -> "Very hot — exports paused"
}

fun exportHeatText(level: ThermalLevel): String? = when (level) {
    ThermalLevel.Hot -> "Your phone is hot — export slowed down to protect it"
    ThermalLevel.Critical -> "Your phone is very hot — export paused between frames to let it cool"
    else -> null
}

fun verificationLine(v: Verification): String = if (v.ok) "Checked: plays correctly" else "Checked: " + v.problems.joinToString(" ")
