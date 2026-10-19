package app.absentmotion.export

import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ExportKind { Video, Package, Frame, FrameAs, Sequence, Gif, Xml }

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
    Apv("APV", "video/apv", 8.0);

    val short get() = label.substringAfter('/')
}

val AvailableCodecs: List<Codec> by lazy {
    app.absentmotion.platform.availableCodecs()
}

enum class ImageKind(val label: String, val ext: String, val mime: String, val alpha: Boolean, val lossy: Boolean, val bytesPerPixel: Double) {
    Png("PNG", "png", "image/png", true, false, 0.8),
    Jpeg("JPEG", "jpg", "image/jpeg", false, true, 0.2),
    Webp("WebP", "webp", "image/webp", true, true, 0.12),
    WebpLossless("WebP lossless", "webp", "image/webp", true, false, 0.5),
    Bmp("BMP", "bmp", "image/bmp", false, false, 3.0),
}

val ImageQualities = listOf(50, 70, 85, 95, 100)
const val BackgroundWhite = 0xFFFFFFFF.toInt()
const val BackgroundBlack = 0xFF000000.toInt()

enum class FrameInterpolation(val label: String) { Off("Off"), Blend("Frame blending"), Flow("Optical flow") }

data class ExportSettings(
    val shortSide: Int,
    val fps: Int,
    val codec: Codec = Codec.H264,
    val quality: Float = 0.5f,
    val image: ImageKind = ImageKind.Png,
    val imageQuality: Int = 95,
    val background: Int = BackgroundWhite,
    val interpolation: FrameInterpolation = FrameInterpolation.Off,
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

const val AudioBitrate = 128_000

const val GifBytesPerPixel = 0.6

fun optionNote(kind: ExportKind, scene: Scene, s: ExportSettings): String? = when (kind) {
    ExportKind.Video -> "MP4 ${if (s.codec == Codec.H264) "" else s.codec.short + " "}${resolutionLabel(s.shortSide)} ${s.fps}fps"
    ExportKind.Sequence -> "${s.image.label} ${resolutionLabel(s.shortSide)} ${s.fps}fps"
    ExportKind.Gif -> exportSize(scene, s.shortSide, false).let { "${it.first}x${it.second} ${s.fps}fps" }
    ExportKind.Package -> "Keeping Original Media"
    ExportKind.Xml -> "for workflow integrations"
    ExportKind.Frame -> null
    ExportKind.FrameAs -> s.image.label + if (s.image.lossy) " ${s.imageQuality}%" else ""
}

fun estimateBytes(kind: ExportKind, scene: Scene, s: ExportSettings, hasAudio: Boolean): Long {
    val seconds = (scene.totalTime + 1) / 1000.0
    val frames = frameCount(scene, s.fps)
    return when (kind) {
        ExportKind.Video -> exportSize(scene, s.shortSide, true).let {
            (videoBitrate(it.first, it.second, s) + if (hasAudio) AudioBitrate else 0) / 8 * seconds
        }.toLong()
        ExportKind.Gif -> exportSize(scene, s.shortSide, false).let { it.first * it.second * frames * GifBytesPerPixel }.toLong()
        ExportKind.Sequence -> exportSize(scene, s.shortSide, false).let {
            (it.first.toLong() * it.second * frames * s.image.bytesPerPixel)
        }.toLong()
        ExportKind.Frame -> (scene.width.toLong() * scene.height * ImageKind.Png.bytesPerPixel).toLong()
        ExportKind.FrameAs -> (scene.width.toLong() * scene.height * s.image.bytesPerPixel).toLong()
        ExportKind.Xml -> scene.toXml().length.toLong()
        ExportKind.Package -> scene.toXml().length + scene.node.mediaUris().sumOf { (scene.media(it)?.size ?: 0).coerceAtLeast(0) }
    }
}

fun formatBytes(bytes: Long): String {
    fun f(v: Double, unit: String) = String.format(Locale.ROOT, "%.1f%s", v, unit)
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> f(bytes / 1024.0, "KB")
        bytes < 1024L * 1024 * 1024 -> f(bytes / (1024.0 * 1024), "MB")
        else -> f(bytes / (1024.0 * 1024 * 1024), "GB")
    }
}

fun safeName(title: String): String =
    title.filter { it.isLetterOrDigit() || it in "_-.+, " }.trim().ifEmpty { "Project" }
