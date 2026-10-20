package app.absentmotion.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { return null to null }
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val first = decode(codec, extractor, deadline, untilEnd = false)
        var lastSample = 0L
        extractor.seekTo(Long.MAX_VALUE / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime >= 0) {
            lastSample = maxOf(lastSample, extractor.sampleTime)
            if (!extractor.advance()) break
        }
        codec.flush()
        extractor.seekTo(lastSample, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val last = decode(codec, extractor, deadline, untilEnd = true)?.let { it >= 0 && lastSample - it <= 1_000 }
        return first?.let { it >= 0 } to last
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

internal fun verificationSummary(v: Verification): String =
    if (v.ok) "verified in ${v.ms} ms" else v.problems.joinToString(" ") { AppText.get(it) } + " (${v.ms} ms)"
