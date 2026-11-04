package app.absentmotion.tools

import app.absentmotion.export.GifBytesPerPixel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val MIN_CUT_US = 100_000L
const val MAX_GIF_US = 30_000_000L
const val MAX_GIF_FRAMES = 720
const val GIF_BIG_BYTES = 25_000_000L
const val KEY_SLACK_US = 2_000L
const val VIDEO_TAIL_US = 2_000_000L
const val FALLBACK_SOUND_KBPS = 128
const val TRIM_BITS_PER_PIXEL = 0.15
const val GIF_FRAME_SPAN = 90

val GifSides = listOf(320, 480, 720)
val GifRates = listOf(10, 15, 24)

data class ExtractChoice(val mp3: Boolean = false, val kbps: Int = FALLBACK_SOUND_KBPS)

data class AudioJob(val mp3: Boolean, val kbps: Int, val copy: Boolean) {
    val extension: String get() = if (mp3) "mp3" else "m4a"
    val mime: String get() = if (mp3) "audio/mpeg" else "audio/mp4"
}

fun canCopySound(src: CompressSource, choice: ExtractChoice): Boolean = src.audio && src.audioAac && !choice.mp3

fun planAudio(src: CompressSource, choice: ExtractChoice): AudioJob {
    if (choice.kbps <= 0 && canCopySound(src, choice)) return AudioJob(false, src.audioKbps.takeIf { it > 0 } ?: FALLBACK_SOUND_KBPS, true)
    return AudioJob(choice.mp3, choice.kbps.takeIf { it > 0 } ?: FALLBACK_SOUND_KBPS, false)
}

fun estimateAudio(job: AudioJob, durationUs: Long): Long = (job.kbps * 125.0 * durationUs.coerceAtLeast(0) / 1_000_000).toLong() + 4_096

data class TrimRange(val startUs: Long, val endUs: Long) {
    val lengthUs: Long get() = endUs - startUs
}

fun fullRange(durationUs: Long, maxSpanUs: Long = Long.MAX_VALUE): TrimRange = TrimRange(0, min(durationUs.coerceAtLeast(0), maxSpanUs))

fun moveStart(range: TrimRange, startUs: Long, durationUs: Long, maxSpanUs: Long = Long.MAX_VALUE): TrimRange {
    val gap = min(MIN_CUT_US, durationUs.coerceAtLeast(0))
    val floor = max(0L, range.endUs - maxSpanUs)
    return TrimRange(startUs.coerceIn(floor, max(floor, range.endUs - gap)), range.endUs)
}

fun moveEnd(range: TrimRange, endUs: Long, durationUs: Long, maxSpanUs: Long = Long.MAX_VALUE): TrimRange {
    val gap = min(MIN_CUT_US, durationUs.coerceAtLeast(0))
    val top = min(durationUs.coerceAtLeast(0), if (maxSpanUs == Long.MAX_VALUE) Long.MAX_VALUE else range.startUs + maxSpanUs)
    return TrimRange(range.startUs, endUs.coerceIn(min(range.startUs + gap, top), top))
}

fun nearestKeyframe(timeUs: Long, keys: LongArray): Long {
    if (keys.isEmpty()) return timeUs
    val found = keys.binarySearch(timeUs)
    if (found >= 0) return keys[found]
    val at = -found - 1
    val before = keys.getOrNull(at - 1)
    val after = keys.getOrNull(at)
    return when {
        before == null -> after ?: timeUs
        after == null -> before
        timeUs - before <= after - timeUs -> before
        else -> after
    }
}

fun stepKeyframe(timeUs: Long, keys: LongArray, forward: Boolean): Long {
    if (keys.isEmpty()) return timeUs
    val found = keys.binarySearch(timeUs)
    val at = if (found >= 0) found else -found - 1
    return if (forward) keys.getOrNull(if (found >= 0) at + 1 else at) ?: timeUs else keys.getOrNull(at - 1) ?: timeUs
}

class CopyWindow(private val fromUs: Long, private val toUs: Long, private val tracks: Int, private val lastVideoUs: Long? = null) {
    private val ended = HashSet<Int>()

    val done: Boolean get() = ended.size == tracks

    fun take(track: Int, video: Boolean, timeUs: Long, sync: Boolean): Boolean {
        if (timeUs > toUs && (!video || sync || timeUs > toUs + VIDEO_TAIL_US)) ended += track
        if (track in ended || timeUs < fromUs - KEY_SLACK_US) return false
        if (!video || lastVideoUs == null || lastVideoUs < fromUs - KEY_SLACK_US) return timeUs <= toUs
        if (timeUs == lastVideoUs) ended += track
        return true
    }
}

fun lastDecodedUs(samples: Sequence<Pair<Long, Boolean>>, toUs: Long): Long? {
    var last: Long? = null
    for ((timeUs, sync) in samples) {
        if (timeUs > toUs && (sync || timeUs > toUs + VIDEO_TAIL_US)) break
        if (timeUs <= toUs) last = timeUs
    }
    return last
}

data class TrimJob(val range: TrimRange, val copy: Boolean)

fun snapStart(timeUs: Long, keys: LongArray, endUs: Long): Long {
    if (keys.isEmpty()) return timeUs
    val near = nearestKeyframe(timeUs, keys)
    if (near <= endUs - MIN_CUT_US) return near
    return stepKeyframe(near, keys, false).takeIf { it <= endUs - MIN_CUT_US } ?: timeUs
}

fun onKeyframe(timeUs: Long, keys: LongArray): Boolean = keys.isNotEmpty() && abs(nearestKeyframe(timeUs, keys) - timeUs) <= KEY_SLACK_US

fun planTrim(range: TrimRange, keys: LongArray, fast: Boolean): TrimJob = TrimJob(range, fast && onKeyframe(range.startUs, keys))

fun trimVideoJob(src: CompressSource): VideoJob {
    val fps = min(src.fps.roundToInt().coerceIn(1, 240), 60)
    val (w, h) = scaledToShort(src.width, src.height, 0)
    val (audio, copy) = sound(src, FALLBACK_SOUND_KBPS, keepAac = true)
    val bitrate = min(sourceVideoBitrate(src), bitsFor(w, h, fps, TRIM_BITS_PER_PIXEL)).coerceAtLeast(MIN_VIDEO_BITRATE)
    return VideoJob(w, h, fps, bitrate, audio, copy, 0)
}

fun estimateTrim(src: CompressSource, job: TrimJob): Long {
    val length = job.range.lengthUs.coerceAtLeast(0)
    if (job.copy) return if (src.durationUs > 0) (src.bytes.toDouble() * length / src.durationUs).toLong() else src.bytes
    return estimateVideo(trimVideoJob(src), length)
}

data class GifChoice(val side: Int = 480, val fps: Int = 15, val loop: Boolean = true)

data class GifJob(val width: Int, val height: Int, val fps: Int, val loop: Boolean, val range: TrimRange, val frames: Int)

fun gifSize(width: Int, height: Int, turn: Int, side: Int): Pair<Int, Int> {
    val turned = turn % 180 != 0
    val w = if (turned) height else width
    val h = if (turned) width else height
    val scale = min(1.0, side.toDouble() / max(w, h).coerceAtLeast(1))
    return max(2, (w * scale).roundToInt()) to max(2, (h * scale).roundToInt())
}

fun planGif(src: CompressSource, turn: Int, choice: GifChoice, range: TrimRange): GifJob {
    val (w, h) = gifSize(src.width, src.height, turn, choice.side)
    val fps = min(choice.fps, src.fps.roundToInt().coerceAtLeast(1))
    val span = min(range.lengthUs, MAX_GIF_US).coerceAtLeast(1)
    val frames = ((span * fps + 999_999) / 1_000_000).toInt().coerceIn(1, MAX_GIF_FRAMES)
    return GifJob(w, h, fps, choice.loop, TrimRange(range.startUs, range.startUs + span), frames)
}

fun gifFrameTimes(job: GifJob): LongArray = LongArray(job.frames) { job.range.startUs + it * 1_000_000L / job.fps }

fun gifFrameEndMs(job: GifJob, index: Int): Long = min((index + 1) * 1000L / job.fps, job.range.lengthUs / 1000)

fun estimateGif(job: GifJob): Long = (job.width.toLong() * job.height * job.frames * GifBytesPerPixel).toLong() + 800

fun frameChunks(times: LongArray, stamps: LongArray, maxSpan: Int = GIF_FRAME_SPAN): List<IntRange> {
    fun index(t: Long) = stamps.binarySearch(t).let { if (it >= 0) it else -it - 1 }
    val out = ArrayList<IntRange>()
    var from = 0
    while (from < times.size) {
        val first = index(times[from])
        var to = from
        while (to + 1 < times.size && index(times[to + 1]) - first <= maxSpan) to++
        out += from..to
        from = to + 1
    }
    return out
}

internal fun framePixels(argb: IntArray, ss: Int, turn: Int, width: Int, height: Int, out: ByteArray) {
    val turned = turn % 180 != 0
    val cw = if (turned) height else width
    val ch = if (turned) width else height
    val rowSize = cw * ss
    val area = ss * ss
    for (y in 0 until height) {
        for (x in 0 until width) {
            val cx: Int
            val cy: Int
            when (((turn % 360) + 360) % 360) {
                90 -> { cx = y; cy = ch - 1 - x }
                180 -> { cx = cw - 1 - x; cy = ch - 1 - y }
                270 -> { cx = cw - 1 - y; cy = x }
                else -> { cx = x; cy = y }
            }
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until ss) {
                val base = (cy * ss + dy) * rowSize + cx * ss
                for (dx in 0 until ss) {
                    val p = argb[base + dx]
                    r += (p shr 16) and 255
                    g += (p shr 8) and 255
                    b += p and 255
                }
            }
            val o = (y * width + x) * 4
            out[o] = (r / area).toByte()
            out[o + 1] = (g / area).toByte()
            out[o + 2] = (b / area).toByte()
            out[o + 3] = -1
        }
    }
}

fun clock(us: Long): String {
    val tenths = (us.coerceAtLeast(0) + 50_000) / 100_000
    val total = tenths / 10
    val fraction = tenths % 10
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d.%d", total / 3600, total / 60 % 60, total % 60, fraction)
    else String.format(Locale.ROOT, "%d:%02d.%d", total / 60, total % 60, fraction)
}

fun clockWhole(us: Long): String {
    val total = (us.coerceAtLeast(0) + 500_000) / 1_000_000
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}
