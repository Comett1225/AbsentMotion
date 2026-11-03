package app.absentmotion.tools

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CompressLevel { Low, Medium, High, Discord, Custom }

enum class DiscordLimit(val bytes: Long) { Free(20_000_000), Basic(50_000_000), Nitro(1_000_000_000) }

class CompressSource(
    val video: Boolean, val bytes: Long, val width: Int, val height: Int,
    val fps: Float = 30f, val durationUs: Long = 0, val audio: Boolean = false, val audioAac: Boolean = false, val audioKbps: Int = 0,
)

data class CustomChoice(
    val size: Int = 0, val quality: Int = 1, val bySize: Boolean = false, val targetMb: Int = 10,
    val fps: Int = 0, val sound: Boolean = true, val soundKbps: Int = 128, val webp: Boolean = false,
)

data class VideoJob(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val audioKbps: Int, val copyAudio: Boolean, val limitBytes: Long) {
    val fits: Boolean get() = bitrate >= MIN_VIDEO_BITRATE
}

class PictureJob(val maxSide: Int, val quality: Int, val webp: Boolean, val limitBytes: Long)

const val MIN_VIDEO_BITRATE = 150_000
const val MEDIUM_TARGET_BYTES = 20_000_000L
val CustomVideoSides = listOf(0, 1080, 720, 480)
val CustomPictureSides = listOf(0, 2048, 1280, 800)
val CustomFrameRates = listOf(0, 60, 30, 24)
val SoundKbps = listOf(64, 128, 192)
private val VideoQuality = listOf(0.12, 0.08, 0.05, 0.03)
private val PictureQuality = listOf(92, 82, 70, 55)
private const val FIT_SHARE = 0.92

fun scaledToShort(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
    val short = min(width, height)
    val scale = if (shortSide <= 0 || short <= shortSide) 1.0 else shortSide.toDouble() / short
    fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
    return even(width * scale) to even(height * scale)
}

fun bitsFor(width: Int, height: Int, fps: Int, perPixel: Double): Int = (width.toDouble() * height * fps * perPixel).roundToInt()

fun sourceVideoBitrate(src: CompressSource): Int {
    if (src.durationUs <= 0) return Int.MAX_VALUE
    val total = src.bytes * 8.0 * 1_000_000 / src.durationUs
    return (total - src.audioKbps * 1000.0).coerceIn(MIN_VIDEO_BITRATE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
}

fun bitrateToFit(limitBytes: Long, durationUs: Long, audioKbps: Int): Int {
    if (durationUs <= 0) return Int.MAX_VALUE
    return (limitBytes * FIT_SHARE * 8.0 * 1_000_000 / durationUs - audioKbps * 1000.0).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
}

fun smallerBitrate(job: VideoJob, durationUs: Long, gotBytes: Long): Int {
    val audioBytes = job.audioKbps * 1000.0 * durationUs / 8_000_000
    val videoGot = (gotBytes - audioBytes).coerceAtLeast(1.0)
    val videoWanted = job.limitBytes * FIT_SHARE - audioBytes
    return (job.bitrate * videoWanted / videoGot * 0.95).coerceAtLeast(0.0).toInt()
}

internal fun sound(src: CompressSource, kbps: Int, keepAac: Boolean = false): Pair<Int, Boolean> = when {
    !src.audio || kbps <= 0 -> 0 to false
    src.audioAac && (keepAac || src.audioKbps in 1..kbps) -> (if (src.audioKbps > 0) src.audioKbps else kbps) to true
    else -> kbps to false
}

fun planVideo(src: CompressSource, level: CompressLevel, custom: CustomChoice = CustomChoice(), discord: DiscordLimit = DiscordLimit.Free): VideoJob {
    val srcFps = src.fps.roundToInt().coerceIn(1, 240)
    val source = sourceVideoBitrate(src)
    fun job(shortSide: Int, fps: Int, bitrate: (Int, Int, Int) -> Int, kbps: Int, keepAac: Boolean = false, limit: Long = 0): VideoJob {
        val (w, h) = scaledToShort(src.width, src.height, shortSide)
        val (audio, copy) = sound(src, kbps, keepAac)
        return VideoJob(w, h, fps, bitrate(w, h, audio), audio, copy, limit)
    }
    return when (level) {
        CompressLevel.Low -> job(0, min(srcFps, 60), { w, h, _ -> min(bitsFor(w, h, min(srcFps, 60), 0.10), (source * 0.6).toInt()).coerceAtLeast(MIN_VIDEO_BITRATE) }, 160, keepAac = true)
        CompressLevel.Medium -> {
            val fps = min(srcFps, 30)
            job(1080, fps, { w, h, audio ->
                val ceiling = bitsFor(w, h, fps, 0.07)
                val floor = bitsFor(w, h, fps, 0.035)
                minOf(bitrateToFit(MEDIUM_TARGET_BYTES, src.durationUs, audio).coerceIn(floor, max(floor, ceiling)), (source * 0.6).toInt()).coerceAtLeast(MIN_VIDEO_BITRATE)
            }, 128)
        }
        CompressLevel.High -> {
            val fps = min(srcFps, 30)
            job(720, fps, { w, h, _ -> min(bitsFor(w, h, fps, 0.045), (source * 0.5).toInt()).coerceAtLeast(MIN_VIDEO_BITRATE) }, 96)
        }
        CompressLevel.Discord -> {
            val fps = min(srcFps, 30)
            val kbps = if (src.durationUs > 300_000_000L) 64 else 96
            val (audio, _) = sound(src, kbps)
            val budget = bitrateToFit(discord.bytes, src.durationUs, audio)
            val side = listOf(1080, 720, 540, 480, 360).firstOrNull { s ->
                scaledToShort(src.width, src.height, s).let { (w, h) -> budget >= bitsFor(w, h, fps, 0.04) }
            } ?: 240
            job(side, fps, { w, h, _ -> minOf(budget, bitsFor(w, h, fps, 0.10), source) }, kbps, limit = discord.bytes)
        }
        CompressLevel.Custom -> {
            val fps = if (custom.fps > 0) min(srcFps, custom.fps) else srcFps
            val limit = if (custom.bySize) custom.targetMb.coerceAtLeast(1) * 1_000_000L else 0L
            job(CustomVideoSides.getOrElse(custom.size) { 0 }, fps, { w, h, audio ->
                if (custom.bySize) min(bitrateToFit(limit, src.durationUs, audio), source)
                else min(bitsFor(w, h, fps, VideoQuality.getOrElse(custom.quality) { 0.08 }), source).coerceAtLeast(MIN_VIDEO_BITRATE)
            }, if (custom.sound) custom.soundKbps else 0, limit = limit)
        }
    }
}

fun planPicture(level: CompressLevel, custom: CustomChoice = CustomChoice(), discord: DiscordLimit = DiscordLimit.Free): PictureJob = when (level) {
    CompressLevel.Low -> PictureJob(0, 85, false, 0)
    CompressLevel.Medium -> PictureJob(2048, 78, false, 0)
    CompressLevel.High -> PictureJob(1280, 65, false, 0)
    CompressLevel.Discord -> PictureJob(0, 85, false, discord.bytes)
    CompressLevel.Custom -> PictureJob(
        CustomPictureSides.getOrElse(custom.size) { 0 }, PictureQuality.getOrElse(custom.quality) { 82 }, custom.webp,
        if (custom.bySize) custom.targetMb.coerceAtLeast(1) * 1_000_000L else 0L,
    )
}

fun estimateVideo(job: VideoJob, durationUs: Long): Long {
    val bytes = ((job.bitrate.toLong() + job.audioKbps * 1000L) * durationUs.coerceAtLeast(0) / 8_000_000.0 * 1.01).roundToLong() + 30_000
    return if (job.limitBytes > 0) min(bytes, job.limitBytes) else bytes
}

fun pictureSide(job: PictureJob, width: Int, height: Int, memoryLimit: Int): Int =
    minOf(max(width, height), if (job.maxSide > 0) job.maxSide else Int.MAX_VALUE, memoryLimit).coerceAtLeast(1)

fun estimatePicture(job: PictureJob, width: Int, height: Int, memoryLimit: Int): Long {
    val long = max(width, height).coerceAtLeast(1)
    val scale = pictureSide(job, width, height, memoryLimit).toDouble() / long
    val q = job.quality / 100.0
    val bitsPerPixel = (0.3 + 2.2 * q * q * q) * if (job.webp) 0.75 else 1.0
    val bytes = (width.toDouble() * height * scale * scale * bitsPerPixel / 8).roundToLong() + 2_000
    return if (job.limitBytes > 0) min(bytes, job.limitBytes) else bytes
}
