package app.absentmotion.export

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ExportKind { Video, Package, Frame, FrameAs, Sequence, Gif, Xml, Audio }

data class Resolution(val label: String, val shortSide: Int)

val VideoResolutions = listOf(
    Resolution("2160p (4k)", 2160), Resolution("1440p (QHD)", 1440), Resolution("1080p (FHD)", 1080),
    Resolution("720p (HD)", 720), Resolution("540p (SD)", 540), Resolution("360p", 360),
    Resolution("270p", 270), Resolution("180p", 180),
)

val GifResolutions = VideoResolutions.filter { it.shortSide <= 720 }
val VideoFrameRates = listOf(10, 15, 24, 25, 30, 48, 50, 60)
val GifFrameRates = listOf(5, 10, 12, 15, 20, 25, 30)

enum class Codec(val label: String, val mime: String, val bitsVsH264: Double) {
    H264("H.264/AVC", "video/avc", 1.0),
    Hevc("H.265/HEVC", "video/hevc", 0.6),
    Av1("AV1", "video/av01", 0.5),
    Vp9("VP9", "video/x-vnd.on2.vp9", 0.65),
    Apv("APV", "video/apv", 8.0),
}

enum class Container(val label: String, val ext: String, val mime: String) {
    Mp4("MP4", "mp4", "video/mp4"),
    Mov("MOV", "mov", "video/quicktime"),
    WebM("WebM", "webm", "video/webm"),
    Mkv("MKV", "mkv", "video/x-matroska"),
}

enum class AudioCodec(val label: String, val mime: String) {
    Aac("AAC", "audio/mp4a-latm"),
    Opus("Opus", "audio/opus"),
}

data class AudioSettings(
    val codec: AudioCodec = AudioCodec.Aac,
    val bitrateKbps: Int = 128,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
) {
    val effectiveRate: Int get() = if (codec == AudioCodec.Opus) 48000 else sampleRate
}

val AudioBitratesKbps = listOf(64, 96, 128, 160, 192, 256, 320)
val AudioSampleRates = listOf(44100, 48000)

enum class AudioFormat(@StringRes val label: Int, val ext: String, val mime: String, val lossless: Boolean) {
    Wav16(R.string.export_audio_wav16, "wav", "audio/x-wav", true),
    Wav24(R.string.export_audio_wav24, "wav", "audio/x-wav", true),
    Flac(R.string.export_audio_flac, "flac", "audio/flac", true),
    M4a(R.string.export_audio_m4a, "m4a", "audio/mp4", false),
    Ogg(R.string.export_audio_ogg, "ogg", "audio/ogg", false),
    Mp3(R.string.export_audio_mp3, "mp3", "audio/mpeg", false),
}

fun videoCodecsFor(container: Container): List<Codec> = when (container) {
    Container.Mp4 -> listOf(Codec.H264, Codec.Hevc, Codec.Av1, Codec.Apv)
    Container.Mov -> listOf(Codec.H264, Codec.Hevc)
    Container.WebM -> listOf(Codec.Vp9, Codec.Av1)
    Container.Mkv -> listOf(Codec.H264, Codec.Hevc, Codec.Av1, Codec.Vp9)
}

fun audioCodecsFor(container: Container): List<AudioCodec> = when (container) {
    Container.Mp4, Container.Mov -> listOf(AudioCodec.Aac)
    Container.WebM -> listOf(AudioCodec.Opus)
    Container.Mkv -> listOf(AudioCodec.Aac, AudioCodec.Opus)
}

fun containersFor(codec: Codec): List<Container> = Container.entries.filter { codec in videoCodecsFor(it) }

fun formatProblem(s: ExportSettings, hasAudio: Boolean): UiText? = when {
    s.codec !in videoCodecsFor(s.container) -> txt(R.string.export_problem_container_video, s.container.label, s.codec.label)
    hasAudio && s.audio.codec !in audioCodecsFor(s.container) -> txt(R.string.export_problem_container_audio, s.container.label, s.audio.codec.label)
    s.audio.bitrateKbps !in 32..512 -> txt(R.string.export_problem_audio_bitrate)
    s.audio.channels !in 1..2 -> txt(R.string.export_problem_audio_channels)
    s.audio.sampleRate !in AudioSampleRates -> txt(R.string.export_problem_audio_rate)
    else -> null
}

fun ExportSettings.fixedFormat(): ExportSettings {
    val container = if (codec in videoCodecsFor(container)) container else containersFor(codec).firstOrNull() ?: Container.Mp4
    val audioCodec = if (audio.codec in audioCodecsFor(container)) audio.codec else audioCodecsFor(container).first()
    return copy(container = container, audio = audio.copy(codec = audioCodec))
}

enum class ImageKind(@StringRes val label: Int, val ext: String, val mime: String, val alpha: Boolean, val lossy: Boolean, val bytesPerPixel: Double) {
    Png(R.string.export_image_png, "png", "image/png", true, false, 0.8),
    Jpeg(R.string.export_image_jpeg, "jpg", "image/jpeg", false, true, 0.2),
    Webp(R.string.export_image_webp, "webp", "image/webp", true, true, 0.12),
    WebpLossless(R.string.export_image_webp_lossless, "webp", "image/webp", true, false, 0.5),
    Bmp(R.string.export_image_bmp, "bmp", "image/bmp", false, false, 3.0),
}

val ImageQualities = listOf(50, 70, 85, 95, 100)
const val BackgroundWhite = 0xFFFFFFFF.toInt()
const val BackgroundBlack = 0xFF000000.toInt()

enum class FrameInterpolation(@StringRes val label: Int) {
    Off(R.string.export_interpolation_off), Blend(R.string.export_interpolation_blend), Flow(R.string.export_interpolation_flow)
}

data class ExportSettings(
    val shortSide: Int,
    val fps: Int,
    val codec: Codec = Codec.H264,
    val quality: Float = 0.5f,
    val image: ImageKind = ImageKind.Png,
    val imageQuality: Int = 95,
    val background: Int = BackgroundWhite,
    val interpolation: FrameInterpolation = FrameInterpolation.Off,
    val container: Container = Container.Mp4,
    val audio: AudioSettings = AudioSettings(),
    val audioFormat: AudioFormat = AudioFormat.M4a,
)

fun defaultSettings(kind: ExportKind, scene: Scene): ExportSettings = when (kind) {
    ExportKind.Video -> ExportSettings(
        VideoResolutions.firstOrNull { it.shortSide <= minOf(scene.width, scene.height) }?.shortSide ?: VideoResolutions.last().shortSide,
        scene.fps,
    )
    ExportKind.Gif -> ExportSettings(360, 15)
    ExportKind.FrameAs -> ExportSettings(360, 30, image = ImageKind.Jpeg)
    else -> ExportSettings(360, 30)
}

fun resolutionLabel(shortSide: Int): String = VideoResolutions.firstOrNull { it.shortSide == shortSide }?.label ?: "${shortSide}p"

fun exportSize(scene: Scene, shortSide: Int, even: Boolean): Pair<Int, Int> {
    val scale = shortSide.toFloat() / minOf(scene.width, scene.height).coerceAtLeast(1)
    fun side(v: Int) = (v * scale).roundToInt().let { if (even) (it + 1) and 1.inv() else it }.coerceAtLeast(2)
    return side(scene.width) to side(scene.height)
}

fun frameCount(scene: Scene, fps: Int): Int =
    if (fps == scene.fps) scene.lastFrame + 1 else (((scene.totalTime + 1L) * fps + 999) / 1000).toInt().coerceAtLeast(1)

fun frameTimeMs(scene: Scene, fps: Int, index: Int): Int = if (fps == scene.fps) scene.frameStart(index) else (index * 1000L / fps).toInt()

fun sequenceFrameName(index: Int, ext: String): String = String.format(Locale.ROOT, "frame_%05d.%s", index + 1, ext)

fun videoBitrate(width: Int, height: Int, s: ExportSettings): Int {
    val bitsPerPixel = 0.03 * 10.0.pow(s.quality.coerceIn(0f, 1f).toDouble()) * s.codec.bitsVsH264
    return (width.toDouble() * height * s.fps * bitsPerPixel).coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt()
}

fun audioRate(s: ExportSettings): Int = if (s.audioFormat == AudioFormat.Ogg) 48000 else s.audio.sampleRate

fun audioBytesPerSecond(s: ExportSettings): Double = when (s.audioFormat) {
    AudioFormat.Wav16 -> s.audio.sampleRate * s.audio.channels * 2.0
    AudioFormat.Wav24 -> s.audio.sampleRate * s.audio.channels * 3.0
    AudioFormat.Flac -> s.audio.sampleRate * s.audio.channels * 2.0 * 0.6
    AudioFormat.M4a, AudioFormat.Ogg, AudioFormat.Mp3 -> s.audio.bitrateKbps * 125.0
}

const val GifBytesPerPixel = 0.6

fun optionNote(kind: ExportKind, scene: Scene, s: ExportSettings): UiText? = when (kind) {
    ExportKind.Video -> txt(R.string.export_note_video, s.container.label, if (s.codec == Codec.H264) "" else s.codec.shortName + " ", resolutionLabel(s.shortSide), "${s.fps}")
    ExportKind.Sequence -> txt(R.string.export_note_sequence, txt(s.image.label), resolutionLabel(s.shortSide), "${s.fps}")
    ExportKind.Gif -> exportSize(scene, s.shortSide, false).let { txt(R.string.export_note_gif, "${it.first}", "${it.second}", "${s.fps}") }
    ExportKind.Package -> txt(R.string.export_note_package)
    ExportKind.Xml -> txt(R.string.export_note_xml)
    ExportKind.Audio -> audioOnlySummary(s)
    ExportKind.Frame -> null
    ExportKind.FrameAs -> if (s.image.lossy) txt(R.string.export_note_image_quality, txt(s.image.label), "${s.imageQuality}") else txt(s.image.label)
}

fun estimateBytes(kind: ExportKind, scene: Scene, s: ExportSettings, hasAudio: Boolean, bitrates: IntRange? = null): Long {
    val seconds = (scene.totalTime + 1) / 1000.0
    val frames = frameCount(scene, s.fps)
    return when (kind) {
        ExportKind.Video -> exportSize(scene, s.shortSide, true).let {
            val video = videoBitrate(it.first, it.second, s).let { rate -> bitrates?.takeIf { r -> r.last > 0 }?.let { r -> rate.coerceIn(r) } ?: rate }
            (video.toDouble() + if (hasAudio) s.audio.bitrateKbps * 1000 else 0) / 8 * seconds
        }.toLong()
        ExportKind.Gif -> exportSize(scene, s.shortSide, false).let { it.first.toLong() * it.second * frames * GifBytesPerPixel }.toLong()
        ExportKind.Sequence -> exportSize(scene, s.shortSide, false).let {
            (it.first.toLong() * it.second * frames * s.image.bytesPerPixel)
        }.toLong()
        ExportKind.Frame -> (scene.width.toLong() * scene.height * ImageKind.Png.bytesPerPixel).toLong()
        ExportKind.FrameAs -> (scene.width.toLong() * scene.height * s.image.bytesPerPixel).toLong()
        ExportKind.Xml -> scene.toXml().length.toLong()
        ExportKind.Audio -> (audioBytesPerSecond(s) * seconds).toLong() + 4096
        ExportKind.Package -> scene.toXml().length + scene.node.mediaUris().sumOf { (scene.media(it)?.size ?: 0).coerceAtLeast(0) }
    }
}

fun formatBytes(bytes: Long): String {
    fun f(v: Double, unit: String) = (if (v >= 10) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")) + unit
    return when {
        bytes < 1024 -> "${bytes.coerceAtLeast(0)}B"
        bytes < 1024L * 1024 - 512 -> f(bytes / 1024.0, "KB")
        bytes < 1024L * 1024 * 1024 - 512L * 1024 -> f(bytes / (1024.0 * 1024), "MB")
        else -> f(bytes / (1024.0 * 1024 * 1024), "GB")
    }
}

fun safeName(title: String): String =
    title.filter { it.isLetterOrDigit() || it in "_-.+, " }.trim().ifEmpty { "Project" }
