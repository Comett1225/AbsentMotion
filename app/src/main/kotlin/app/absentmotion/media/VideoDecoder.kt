package app.absentmotion.media

import app.absentmotion.platform.alias
import app.absentmotion.platform.hardware
import app.absentmotion.platform.intOr
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.hardware.HardwareBuffer
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import app.absentmotion.library.openableUri

internal fun MediaCodec.configureFitting(format: MediaFormat, surface: android.view.Surface?, flags: Int = 0) {
    try {
        configure(format, surface, null, flags)
    } catch (e: MediaCodec.CodecException) {
        Log.w("AMVideo", "$name refused real-time work, asking for best effort", e)
        reset()
        format.setInteger(MediaFormat.KEY_PRIORITY, 1)
        configure(format, surface, null, flags)
    }
}

internal fun openDecoder(format: MediaFormat, surface: android.view.Surface?): MediaCodec {
    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val width = format.intOr(MediaFormat.KEY_WIDTH, 0)
    val height = format.intOr(MediaFormat.KEY_HEIGHT, 0)
    val others = list.codecInfos.filter { info ->
        !info.isEncoder && !info.alias && mime in info.supportedTypes && info.getCapabilitiesForType(mime).let { caps ->
            !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) &&
                caps.videoCapabilities?.isSizeSupported(width, height) != false
        }
    }.sortedByDescending { it.hardware }.map { it.name }
    val names = (listOfNotNull(list.findDecoderForFormat(format)) + others).distinct()
    var last: Exception? = null
    for (attempt in 0..DECODER_WAITS) {
        if (attempt > 0) Thread.sleep(DECODER_WAIT_MS)
        var busy = false
        for (name in names) {
            val codec = try { MediaCodec.createByCodecName(name) } catch (e: Exception) {
                last = e
                busy = busy || codecBusy(e)
                Log.w("AMVideo", "cannot create $name", e)
                continue
            }
            try {
                codec.configureFitting(format, surface)
                codec.start()
                return codec
            } catch (e: Exception) {
                last = e
                busy = busy || codecBusy(e)
                Log.w("AMVideo", "$name cannot decode $mime ${width}x$height", e)
                codec.release()
            }
        }
        if (!busy) break
    }
    throw last ?: IllegalArgumentException("no decoder for $mime")
}

private const val MAX_IMAGES = 3
private const val DECODER_WAITS = 10
private const val DECODER_WAIT_MS = 300L

internal fun codecBusy(e: Throwable): Boolean = e is MediaCodec.CodecException &&
    (e.isTransient || e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE || e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED)

internal class VideoDecoder(private val context: Context, private val uri: String) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private val images = LinkedBlockingQueue<Image>()
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var current: Image? = null
    private var currentPts = Long.MIN_VALUE
    private var positioned = false
    private var queued = false
    private var flushable = false
    private var format: MediaFormat
    private var hdr = false
    var colorStandard = 0
        private set
    var colorRange = 0
        private set
    var timestamps: LongArray
        private set
    var seeks = 0
        private set

    init {
        try {
            extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            format = extractor.getTrackFormat(track)
            readColor(format)
            extractor.selectTrack(track)
            timestamps = VideoSources.timestamps(context, uri)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            imageThread = HandlerThread("am-video-images").apply { start() }
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            reader = (if (Build.VERSION.SDK_INT >= 29) ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                else ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_IMAGES)).apply {
                setOnImageAvailableListener({ source ->
                    while (true) images.offer((try { source.acquireNextImage() } catch (_: IllegalStateException) { null }) ?: break)
                }, Handler(imageThread!!.looper))
            }
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            hdr = format.requestSdr()
            codec = openDecoder(format, reader!!.surface)
        } catch (e: Exception) { close(); throw e }
    }

    fun wantedPts(timeUs: Long): Long = timestamps.ptsAt(timeUs)

    fun frame(timeUs: Long, exact: Boolean, obsolete: () -> Boolean): Image? {
        val wanted = wantedPts(timeUs)
        if (wanted == currentPts) return current
        val decoder = codec ?: return current
        if (!positioned || wanted < currentPts || wanted - currentPts > 1_000_000 || outputEnded) reposition(decoder, wanted)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var held = -1
        var heldPts = 0L
        try {
            while (!outputEnded && !obsolete() && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(0)
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
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    flushable = true
                    readColor(decoder.outputFormat)
                    if (hdr) Log.i("AMVideo", "HDR video $uri decodes with transfer ${decoder.outputFormat.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)}")
                }
                if (index < 0) continue
                flushable = true
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size <= 0) {
                    decoder.releaseOutputBuffer(index, false)
                } else if (info.presentationTimeUs < wanted && !outputEnded) {
                    if (held >= 0) decoder.releaseOutputBuffer(held, false)
                    held = index
                    heldPts = info.presentationTimeUs
                } else {
                    if (held >= 0) decoder.releaseOutputBuffer(held, false)
                    held = -1
                    return show(decoder, index, info.presentationTimeUs, wanted, exact)
                }
            }
            if (obsolete()) {
                positioned = false
                return null
            }
            if (!outputEnded) {
                Log.w("AMVideo", "Decoder timed out at $wanted for $uri, showing ${if (held >= 0) heldPts else currentPts}")
                positioned = false
                flushable = false
                if (exact) return null
            }
            if (held < 0) return current
            return show(decoder, held, heldPts, wanted, exact).also { held = -1 }
        } finally {
            if (held >= 0) decoder.releaseOutputBuffer(held, false)
        }
    }

    private fun readColor(format: MediaFormat) {
        colorStandard = videoColorStandard(format.intOr(MediaFormat.KEY_COLOR_STANDARD, colorStandard))
        colorRange = format.intOr(MediaFormat.KEY_COLOR_RANGE, colorRange)
    }

    private fun reposition(decoder: MediaCodec, wanted: Long) {
        if (queued && flushable) decoder.flush()
        else if (queued) {
            decoder.stop()
            decoder.configure(format, reader!!.surface, null, 0)
            decoder.start()
        }
        drainImages()
        extractor.seekTo(wanted, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false; queued = false; positioned = true
        seeks++
    }

    private fun show(decoder: MediaCodec, index: Int, pts: Long, wanted: Long, exact: Boolean): Image? {
        decoder.releaseOutputBuffer(index, true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var image: Image? = null
        while (image == null) {
            val next = images.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            if (kotlin.math.abs(next.timestamp / 1000 - pts) <= 1000) image = next else next.close()
        }
        if (image == null) { positioned = false; return if (exact) null else current }
        current?.close()
        current = image
        currentPts = if (pts > wanted || pts < wanted && outputEnded) wanted else pts
        return image
    }

    private fun drainImages() { generateSequence { images.poll() }.forEach { it.close() } }

    override fun close() {
        current?.close(); current = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        imageThread?.quitSafely(); imageThread = null
        drainImages()
        extractor.release()
    }
}
