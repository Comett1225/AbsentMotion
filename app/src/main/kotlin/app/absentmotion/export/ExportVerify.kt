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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.abs

data class Expectation(val video: Boolean, val audio: Boolean, val durationUs: Long, val frameUs: Long)

data class ProbedTrack(val mime: String, val durationUs: Long)

data class Verification(
    val problems: List<UiText>,
    val durationUs: Long,
    val firstFrame: Boolean?,
    val lastFrame: Boolean?,
    val ms: Long,
) {
    val ok: Boolean get() = problems.isEmpty()
}

internal const val AudioOverhangUs = 100_000L

internal fun judge(tracks: List<ProbedTrack>, expect: Expectation, firstFrame: Boolean?, lastFrame: Boolean?): List<UiText> {
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
        val (first, last) = if (videoIndex >= 0) decodeEnds(extractor, videoIndex, formats[videoIndex], start + DecodeBudgetMs) else null to null
        Verification(judge(tracks, expect, first, last), tracks.maxOfOrNull { it.durationUs } ?: 0, first, last, SystemClock.elapsedRealtime() - start)
    } catch (e: Exception) {
        Verification(listOf(txt(R.string.export_verify_unreadable, e.message ?: e.javaClass.simpleName)), 0, null, null, SystemClock.elapsedRealtime() - start)
    } finally {
        extractor.release()
    }
}

private const val DecodeBudgetMs = 800L

private fun decodeEnds(extractor: MediaExtractor, track: Int, format: MediaFormat, deadline: Long): Pair<Boolean?, Boolean?> {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null to null
    val codec = try { openDecoder(format, null) } catch (e: Exception) { return null to null }
    try {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val first = decode(codec, extractor, deadline, untilEnd = false)
        var lastSample = 0L
        extractor.seekTo(if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime >= 0) {
            lastSample = maxOf(lastSample, extractor.sampleTime)
            if (!extractor.advance()) break
        }
        codec.flush()
        extractor.seekTo(lastSample, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val last = decode(codec, extractor, deadline, untilEnd = true)?.let { it >= 0 && lastSample - it <= 1_000 }
        return first?.let { it >= 0 } to last
    } catch (e: MediaCodec.CodecException) {
        return if (e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED) null to null else false to false
    } catch (e: Exception) {
        return false to false
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
}

private fun decode(codec: MediaCodec, extractor: MediaExtractor, deadline: Long, untilEnd: Boolean): Long? {
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var lastPts = -1L
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(5_000)
            if (index >= 0) {
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val out = codec.dequeueOutputBuffer(info, 5_000)
        if (out >= 0) {
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 || !end) lastPts = maxOf(lastPts, info.presentationTimeUs)
            codec.releaseOutputBuffer(out, false)
            if (!untilEnd && lastPts >= 0) return lastPts
            if (end) return lastPts
        }
    }
    return null
}

internal fun expectationFor(kind: ExportKind, scene: app.absentmotion.model.Scene, s: ExportSettings, hasAudio: Boolean): Expectation? = when (kind) {
    ExportKind.Video -> Expectation(true, hasAudio, frameCount(scene, s.fps) * 1_000_000L / s.fps, 1_000_000L / s.fps)
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
