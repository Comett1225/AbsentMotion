package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import app.absentmotion.library.openableUri

internal interface DesktopFrameInput : Closeable {
    val timestamps: LongArray
    val colorStandard: Int
    val fullRange: Boolean
    suspend fun read(wanted: List<Long>, consume: suspend (Long, Yuv) -> Unit): Int
}

internal class StopReading(val now: () -> Boolean) : kotlin.coroutines.AbstractCoroutineContextElement(StopReading) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<StopReading>
}

internal class FrameReader(private val context: Context, private val uri: String) : Closeable {
    private var desktop = app.absentmotion.platform.desktopFrameReader(context, uri)
    private lateinit var extractor: Demuxer
    private var codec: MediaCodec? = null
    private val info by lazy { MediaCodec.BufferInfo() }
    private var queued = false
    private var positioned = false
    private var inputEnded = false
    private var lastPts = 0L
    val timestamps: LongArray
    private var systemStandard = 0
    private var systemFull = false
    val colorStandard: Int get() = desktop?.colorStandard ?: systemStandard
    val fullRange: Boolean get() = desktop?.fullRange ?: systemFull

    init {
        val ready = desktop
        if (ready != null) {
            timestamps = ready.timestamps
        } else try {
            extractor = openDemuxer(context, Uri.parse(openableUri(uri)), "video/")
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            timestamps = VideoSources.timestamps(context, uri)
            readColor(format)
            codec = systemOrFfamp({
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                format.requestSdr()
                openDecoder(format, null)
            }, ::rescued) { null }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun readColor(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) systemStandard = videoColorStandard(format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) systemFull = format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
    }

    private fun rescued(failed: Exception): DesktopFrameInput? {
        val readers = MediaBackend.rescue ?: return null
        val found = runCatching { readers.softFrames(context, uri, rescue = true) }.getOrNull() ?: return null
        Log.w("AMVideo", "the system decoder failed on $uri, FFamp decodes it: $failed")
        runCatching { codec?.release() }
        codec = null
        desktop = found
        return found
    }

    suspend fun read(wanted: List<Long>, consume: suspend (Long, Yuv) -> Unit): Int {
        desktop?.let { return it.read(wanted, consume) }
        if (wanted.isEmpty()) return 0
        val decoder = checkNotNull(codec) { "The video reader is closed" }
        var done = 0
        return systemOrFfamp({ decoded(decoder, wanted) { at, yuv -> consume(at, yuv); done++ } }, ::rescued) { done + it.read(wanted.drop(done), consume) }
    }

    private suspend fun decoded(decoder: MediaCodec, wanted: List<Long>, consume: suspend (Long, Yuv) -> Unit): Int {
        if (!positioned || wanted.first() <= lastPts || wanted.first() - lastPts > SKIP_US) {
            if (queued) decoder.flush()
            extractor.seekTo(wanted.first(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            queued = false
            inputEnded = false
            positioned = true
        }
        var next = 0
        var waiting = System.nanoTime()
        while (next < wanted.size) {
            coroutineContext.ensureActive()
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(2_000)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queued = true
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 5_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) readColor(decoder.outputFormat)
            if (index < 0) {
                exportCheck(System.nanoTime() - waiting < TimeUnit.SECONDS.toNanos(10)) { txt(R.string.media_error_decoder_stalled) }
                continue
            }
            waiting = System.nanoTime()
            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            try {
                val pts = info.presentationTimeUs
                if (info.size > 0 && wanted[next] <= pts) {
                    lastPts = pts
                    val image = decoder.getOutputImage(index) ?: throw ExportError(txt(R.string.media_error_read_frames))
                    image.use {
                        exportCheck(image.format == ImageFormat.YUV_420_888) { txt(R.string.media_error_pixel_format) }
                        val yuv = image.yuv()
                        while (next < wanted.size && wanted[next] <= pts) consume(wanted[next++], yuv)
                    }
                }
            } finally {
                decoder.releaseOutputBuffer(index, false)
            }
            if (ended) {
                lastPts = Long.MAX_VALUE
                break
            }
        }
        return next
    }

    override fun close() {
        desktop?.close()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (::extractor.isInitialized) extractor.release()
    }

    private companion object {
        const val SKIP_US = 2_000_000L
    }
}

private fun Image.yuv(): Yuv {
    val (y, u, v) = planes
    val crop = cropRect
    return Yuv(y.buffer, y.rowStride, y.pixelStride, u.buffer, v.buffer, u.rowStride, u.pixelStride, crop.left, crop.top, crop.width(), crop.height())
}
