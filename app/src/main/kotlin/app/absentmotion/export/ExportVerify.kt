package app.absentmotion.export

import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.media.openDecoder
import app.absentmotion.platform.intOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.abs

class FrameLooks(val middleUs: Long, val first: FloatArray, val middle: FloatArray)

data class Expectation(val video: Boolean, val audio: Boolean, val durationUs: Long, val frameUs: Long, val looks: FrameLooks? = null)

data class ProbedTrack(val mime: String, val durationUs: Long)

data class Verification(
    val problems: List<UiText>,
    val durationUs: Long,
    val firstFrame: Boolean?,
    val lastFrame: Boolean?,
    val ms: Long,
    val middleFrame: Boolean? = null,
) {
    val ok: Boolean get() = problems.isEmpty()
}

internal const val AudioOverhangUs = 100_000L

internal const val LookCells = 8

internal fun lookDistance(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { kotlin.math.abs(a[it] - b[it]).toDouble() }.toFloat() / a.size.coerceAtLeast(1)

internal fun middleLooksRight(drawn: FrameLooks, decodedFirst: FloatArray, decodedMiddle: FloatArray): Boolean {
    if (lookDistance(drawn.middle, decodedMiddle) > 0.2f) return false
    return !(lookDistance(decodedFirst, decodedMiddle) < 0.01f && lookDistance(drawn.first, drawn.middle) > 0.06f)
}

internal fun uprightLook(look: FloatArray, degrees: Int): FloatArray {
    val n = LookCells - 1
    return when ((degrees % 360 + 360) % 360) {
        90 -> FloatArray(look.size) { i -> look[(n - i % LookCells) * LookCells + i / LookCells] }
        180 -> FloatArray(look.size) { i -> look[(n - i / LookCells) * LookCells + n - i % LookCells] }
        270 -> FloatArray(look.size) { i -> look[(i % LookCells) * LookCells + n - i / LookCells] }
        else -> look
    }
}

internal fun rgbaLook(rgba: ByteArray, width: Int, height: Int): FloatArray {
    val sums = FloatArray(LookCells * LookCells)
    val counts = IntArray(LookCells * LookCells)
    for (y in 0 until height) for (x in 0 until width) {
        val i = (y * width + x) * 4
        val a = (rgba[i + 3].toInt() and 255) / 255f
        val luma = (0.2126f * (rgba[i].toInt() and 255) + 0.7152f * (rgba[i + 1].toInt() and 255) + 0.0722f * (rgba[i + 2].toInt() and 255)) / 255f * a
        val cell = y * LookCells / height * LookCells + x * LookCells / width
        sums[cell] += luma
        counts[cell]++
    }
    return FloatArray(sums.size) { sums[it] / counts[it].coerceAtLeast(1) }
}

internal fun yLook(plane: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, width: Int, height: Int): FloatArray =
    FloatArray(LookCells * LookCells) { cell ->
        var sum = 0f
        for (sy in 0 until 4) for (sx in 0 until 4) {
            val x = left + ((cell % LookCells) * 4 + sx) * width / (LookCells * 4) + width / (LookCells * 8)
            val y = top + ((cell / LookCells) * 4 + sy) * height / (LookCells * 4) + height / (LookCells * 8)
            sum += (((plane.get(y * rowStride + x * pixelStride).toInt() and 255) - 16) / 219f).coerceIn(0f, 1f)
        }
        sum / 16
    }

internal fun judge(tracks: List<ProbedTrack>, expect: Expectation, firstFrame: Boolean?, lastFrame: Boolean?, middleFrame: Boolean? = null): List<UiText> {
    val problems = ArrayList<UiText>()
    val video = tracks.filter { it.mime.startsWith("video/") }
    val audio = tracks.filter { it.mime.startsWith("audio/") }
    if (tracks.isEmpty()) return listOf(txt(R.string.export_verify_empty))
    if (expect.video && video.isEmpty()) problems += txt(R.string.export_verify_no_video)
    if (expect.audio && audio.isEmpty()) problems += txt(R.string.export_verify_no_audio)
    val duration = tracks.maxOf { it.durationUs }
    val slack = expect.frameUs + 1_000
    val over = duration - expect.durationUs
    if (over < -slack || over > slack + if (audio.isNotEmpty()) AudioOverhangUs else 0) {
        problems += txt(R.string.export_verify_duration, "%.3f".format(java.util.Locale.ROOT, duration / 1e6), "%.3f".format(java.util.Locale.ROOT, expect.durationUs / 1e6))
    }
    if (expect.video && video.isNotEmpty()) {
        if (firstFrame == false) problems += txt(R.string.export_verify_first_frame)
        if (middleFrame == false) problems += txt(R.string.export_verify_middle_frame)
        if (lastFrame == false) problems += txt(R.string.export_verify_last_frame)
    }
    return problems
}

suspend fun verifyExport(context: Context, uri: Uri, expect: Expectation): Verification = withContext(Dispatchers.IO) {
    val start = SystemClock.elapsedRealtime()
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context.applicationContext, uri, null)
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val tracks = formats.map { f ->
            ProbedTrack(f.getString(MediaFormat.KEY_MIME).orEmpty(), if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L)
        }
        val videoIndex = tracks.indexOfFirst { it.mime.startsWith("video/") }
        val frames = if (videoIndex >= 0) decodeFrames(extractor, videoIndex, formats[videoIndex], expect.looks) else Decoded()
        Verification(judge(tracks, expect, frames.first, frames.last, frames.middle), tracks.maxOfOrNull { it.durationUs } ?: 0, frames.first, frames.last,
            SystemClock.elapsedRealtime() - start, frames.middle)
    } catch (e: Exception) {
        Verification(listOf(txt(R.string.export_verify_unreadable, e.message ?: e.javaClass.simpleName)), 0, null, null, SystemClock.elapsedRealtime() - start)
    } finally {
        extractor.release()
    }
}

internal fun decodeBudgetMs(width: Int, height: Int): Long = (800L + width.toLong() * height / 5_000).coerceIn(800L, 5_000L)

private class Decoded(val first: Boolean? = null, val middle: Boolean? = null, val last: Boolean? = null)

private class Frame(val pts: Long, val look: FloatArray?)

private fun decodeFrames(extractor: MediaExtractor, track: Int, format: MediaFormat, looks: FrameLooks?): Decoded {
    val budget = decodeBudgetMs(format.intOr(MediaFormat.KEY_WIDTH, 1920), format.intOr(MediaFormat.KEY_HEIGHT, 1080))
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
    val codec = try { openDecoder(format, null) } catch (e: Exception) { return Decoded() }
    try {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val turned = format.intOr(MediaFormat.KEY_ROTATION, 0)
        val first = frameAt(codec, extractor, SystemClock.elapsedRealtime() + budget, 0, looks != null)
        var middle: Boolean? = null
        val firstLook = first?.look?.let { uprightLook(it, turned) }
        if (looks != null && firstLook != null) {
            codec.flush()
            extractor.seekTo(looks.middleUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            middle = frameAt(codec, extractor, SystemClock.elapsedRealtime() + budget, looks.middleUs, true)?.look
                ?.let { middleLooksRight(looks, firstLook, uprightLook(it, turned)) }
        }
        var lastSample = 0L
        extractor.seekTo(if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime >= 0) {
            lastSample = maxOf(lastSample, extractor.sampleTime)
            if (!extractor.advance()) break
        }
        codec.flush()
        extractor.seekTo(lastSample, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val last = decode(codec, extractor, SystemClock.elapsedRealtime() + budget)?.let { it >= 0 && lastSample - it <= 1_000 }
        return Decoded(first?.let { it.pts >= 0 }, middle, last)
    } catch (e: MediaCodec.CodecException) {
        return if (e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED) Decoded() else Decoded(false, null, false)
    } catch (e: Exception) {
        return Decoded(false, null, false)
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
}

private fun feed(codec: MediaCodec, extractor: MediaExtractor): Boolean {
    val index = codec.dequeueInputBuffer(5_000)
    if (index < 0) return false
    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
    if (size < 0) {
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }
    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
    extractor.advance()
    return false
}

private fun frameAt(codec: MediaCodec, extractor: MediaExtractor, deadline: Long, targetUs: Long, look: Boolean): Frame? {
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!inputDone) inputDone = feed(codec, extractor)
        val out = codec.dequeueOutputBuffer(info, 5_000)
        if (out < 0) continue
        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val hit = info.size > 0 && info.presentationTimeUs >= targetUs - 1_000
        val seen = if (hit && look) runCatching { codec.getOutputImage(out)?.use(::imageLook) }.getOrNull() else null
        codec.releaseOutputBuffer(out, false)
        if (hit) return Frame(info.presentationTimeUs, seen)
        if (end) return Frame(-1, null)
    }
    return null
}

private fun imageLook(image: android.media.Image): FloatArray {
    val plane = image.planes[0]
    val crop = image.cropRect
    return yLook(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.width(), crop.height())
}

private fun decode(codec: MediaCodec, extractor: MediaExtractor, deadline: Long): Long? {
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var lastPts = -1L
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!inputDone) inputDone = feed(codec, extractor)
        val out = codec.dequeueOutputBuffer(info, 5_000)
        if (out >= 0) {
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 || !end) lastPts = maxOf(lastPts, info.presentationTimeUs)
            codec.releaseOutputBuffer(out, false)
            if (end) return lastPts
        }
    }
    return null
}

internal fun expectationFor(kind: ExportKind, scene: app.absentmotion.model.Scene, s: ExportSettings, hasAudio: Boolean, looks: FrameLooks? = null): Expectation? = when (kind) {
    ExportKind.Video -> Expectation(true, hasAudio, frameCount(scene, s.fps) * 1_000_000L / s.fps, 1_000_000L / s.fps, looks)
    ExportKind.Audio -> Expectation(false, true, (scene.totalTime + 1) * 1000L, 30_000)
    else -> null
}

data class PictureExpectation(val width: Int, val height: Int, val frames: Int = 1, val durationUs: Long = 0, val frameUs: Long = 0) {
    val animated: Boolean get() = durationUs > 0
}

internal fun pictureExpectationFor(kind: ExportKind, scene: app.absentmotion.model.Scene, s: ExportSettings): PictureExpectation? = when (kind) {
    ExportKind.Gif -> {
        val (width, height) = exportSize(scene, s.shortSide, even = false)
        val frames = frameCount(scene, s.fps)
        PictureExpectation(width, height, frames, frames * 1_000_000L / s.fps, 1_000_000L / s.fps)
    }
    ExportKind.Frame, ExportKind.FrameAs -> PictureExpectation(scene.width, scene.height)
    else -> null
}

internal class GifFacts(val width: Int, val height: Int, val frames: Int, val durationUs: Long, val complete: Boolean)

internal fun gifFacts(input: InputStream): GifFacts? {
    val data = DataInputStream(input.buffered())
    val buffer = ByteArray(768)
    var width = 0
    var height = -1
    var frames = 0
    var centiseconds = 0L
    var delay = 0
    fun byte() = data.readUnsignedByte()
    fun short() = byte() or (byte() shl 8)
    fun skip(count: Int) = data.readFully(buffer, 0, count)
    fun table(packed: Int) { if (packed and 0x80 != 0) skip(3 shl ((packed and 7) + 1)) }
    fun blocks() {
        while (true) {
            val size = byte()
            if (size == 0) return
            skip(size)
        }
    }
    fun facts(complete: Boolean) = if (height < 0) null else GifFacts(width, height, frames, centiseconds * 10_000, complete)
    try {
        skip(6)
        val signature = String(buffer, 0, 6, Charsets.US_ASCII)
        if (signature != "GIF89a" && signature != "GIF87a") return null
        width = short()
        height = short()
        val packed = byte()
        skip(2)
        table(packed)
        while (true) when (byte()) {
            0x21 -> if (byte() == 0xF9) {
                val size = byte()
                skip(size)
                if (size >= 3) delay = (buffer[1].toInt() and 255) or ((buffer[2].toInt() and 255) shl 8)
                blocks()
            } else blocks()
            0x2C -> {
                skip(8)
                table(byte())
                byte()
                blocks()
                frames++
                centiseconds += delay
                delay = 0
            }
            0x3B -> return facts(true)
            else -> return facts(false)
        }
    } catch (e: EOFException) {
        return facts(false)
    }
}

internal fun judgePicture(width: Int, height: Int, gif: GifFacts?, expect: PictureExpectation): List<UiText> {
    val problems = ArrayList<UiText>()
    if (width != expect.width || height != expect.height) problems += txt(R.string.export_verify_size, "${width}x$height", "${expect.width}x${expect.height}")
    if (!expect.animated) return problems
    if (gif == null || !gif.complete) return problems + txt(R.string.export_verify_picture)
    if (gif.frames != expect.frames) problems += txt(R.string.export_verify_frames, gif.frames.toString(), expect.frames.toString())
    if (abs(gif.durationUs - expect.durationUs) > expect.frameUs + 10_000) {
        problems += txt(R.string.export_verify_duration, "%.3f".format(java.util.Locale.ROOT, gif.durationUs / 1e6), "%.3f".format(java.util.Locale.ROOT, expect.durationUs / 1e6))
    }
    return problems
}

suspend fun verifyPicture(context: Context, uri: Uri, expect: PictureExpectation): Verification = withContext(Dispatchers.IO) {
    val start = SystemClock.elapsedRealtime()
    try {
        val size = decodedSize(context, uri)
        val gif = if (expect.animated) context.contentResolver.openInputStream(uri)?.use(::gifFacts) else null
        val problems = if (size == null) listOf(txt(R.string.export_verify_picture)) else judgePicture(size.width, size.height, gif, expect)
        Verification(problems, gif?.durationUs ?: 0, size != null, null, SystemClock.elapsedRealtime() - start)
    } catch (e: Exception) {
        Verification(listOf(txt(R.string.export_verify_unreadable, e.message ?: e.javaClass.simpleName)), 0, null, null, SystemClock.elapsedRealtime() - start)
    }
}

private fun decodedSize(context: Context, uri: Uri): Size? {
    var size: Size? = null
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            size = info.size
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 512))
            decoder.setOnPartialImageListener { false }
        }.recycle()
    } catch (e: ImageDecoder.DecodeException) {
        return null
    }
    return size
}

internal fun verificationSummary(v: Verification): String =
    if (v.ok) "verified in ${v.ms} ms" else v.problems.joinToString(" ") { AppText.get(it) } + " (${v.ms} ms)"
