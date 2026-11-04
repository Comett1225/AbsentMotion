package app.absentmotion.tools

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.i18n.txt
import app.absentmotion.library.openableUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

private const val MIN_BUFFER = 2 shl 20

internal suspend fun copyRange(
    context: Context, uri: String, fromUs: Long, toUs: Long, withVideo: Boolean, rotation: Int, out: File, progress: (Float) -> Unit, spanUs: Long,
) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tracks = HashMap<Int, Int>()
        var video = -1
        var audio = -1
        var buffer = MIN_BUFFER
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val isVideo = mime.startsWith("video/")
            if (!(isVideo && withVideo && video < 0) && !(mime.startsWith("audio/") && audio < 0)) continue
            extractor.selectTrack(i)
            tracks[i] = muxer.addTrack(format)
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) buffer = maxOf(buffer, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            if (isVideo) video = i else audio = i
        }
        if (tracks.isEmpty()) throw ExportError(txt(if (withVideo) R.string.compress_unreadable else R.string.tools_no_sound))
        if (withVideo && rotation != 0) muxer.setOrientationHint(((rotation % 360) + 360) % 360 / 90 * 90)
        muxer.start()
        started = true
        if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val data = ByteBuffer.allocateDirect(buffer)
        val info = MediaCodec.BufferInfo()
        val window = CopyWindow(fromUs, toUs, tracks.size, if (video >= 0) lastVideoUs(context, uri, video, toUs) else null)
        var written = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val track = extractor.sampleTrackIndex
            val time = extractor.sampleTime
            if (track < 0 || time < 0) break
            val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            val take = window.take(track, track == video, time, sync)
            if (window.done) break
            if (take) {
                data.clear()
                val size = extractor.readSampleData(data, 0)
                if (size < 0) break
                info.set(0, size, (time - fromUs).coerceAtLeast(0), if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks.getValue(track), data, info)
                written++
                if (spanUs > 0) progress(((time - fromUs).toFloat() / spanUs).coerceIn(0f, 0.99f))
            }
            if (!extractor.advance()) break
        }
        if (written == 0) throw ExportError(txt(R.string.tools_nothing_made))
        started = false
        muxer.stop()
    } finally {
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        extractor.release()
    }
}

private fun lastVideoUs(context: Context, uri: String, track: Int, toUs: Long): Long? {
    val scan = MediaExtractor()
    try {
        scan.setDataSource(context, Uri.parse(openableUri(uri)), null)
        scan.selectTrack(track)
        scan.seekTo(toUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return lastDecodedUs(generateSequence {
            (scan.sampleTime to (scan.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)).takeIf { it.first >= 0 }?.also { scan.advance() }
        }, toUs)
    } finally {
        scan.release()
    }
}
