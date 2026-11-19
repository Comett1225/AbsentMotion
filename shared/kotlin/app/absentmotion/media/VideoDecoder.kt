package app.absentmotion.media

import app.absentmotion.platform.alias
import app.absentmotion.platform.hardware
import app.absentmotion.platform.intOr
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit
import app.absentmotion.library.openableUri
import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.i18n.txt

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

internal fun decoderNames(format: MediaFormat, anySize: Boolean = false): List<String> {
    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val width = format.intOr(MediaFormat.KEY_WIDTH, 0)
    val height = format.intOr(MediaFormat.KEY_HEIGHT, 0)
    val others = list.codecInfos.filter { info ->
        !info.isEncoder && !info.alias && mime in info.supportedTypes && info.getCapabilitiesForType(mime).let { caps ->
            !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) &&
                (anySize || caps.videoCapabilities?.isSizeSupported(width, height) != false)
        }
    }.sortedByDescending { it.hardware }.map { it.name }
    return (listOfNotNull(list.findDecoderForFormat(format)) + others).distinct()
}

internal fun openDecoder(format: MediaFormat, surface: android.view.Surface?): MediaCodec {
    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
    val width = format.intOr(MediaFormat.KEY_WIDTH, 0)
    val height = format.intOr(MediaFormat.KEY_HEIGHT, 0)
    val names = decoderNames(format).ifEmpty { decoderNames(format, anySize = true) }
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
    throw last ?: ExportError(txt(R.string.media_error_no_decoder))
}

private const val MAX_IMAGES = 3
private const val DECODER_WAITS = 10
private const val DECODER_WAIT_MS = 300L

internal fun codecBusy(e: Throwable): Boolean = e is MediaCodec.CodecException &&
    (e.isTransient || e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE || e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED)

internal class VideoDecoder(private val context: Context, private val uri: String) : Closeable {
    private val extractor = openDemuxer(context, Uri.parse(openableUri(uri)), "video/")
    private var ffamp = MediaBackend.ffamp(context, Uri.parse(openableUri(uri)), "video/")
    private var soft: DesktopFrameInput? = null
    private var codec: MediaCodec? = null
    private var frames: DecodedFrames? = null
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var current: DecodedFrame? = null
    private var currentPts = Long.MIN_VALUE
    private var positioned = false
    private var skipped = false
    private var decodedTo = Long.MIN_VALUE
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
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            format = extractor.getTrackFormat(track)
            readColor(format)
            extractor.selectTrack(track)
            timestamps = VideoSources.timestamps(context, uri)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            frames = decodedFrames(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), MAX_IMAGES)
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            hdr = format.requestSdr()
            soft = ffamp?.softFrames(context, uri)
            if (soft == null) codec = systemOrFfamp({ openDecoder(format, frames!!.surface) }, ::rescued) { null }
        } catch (e: Exception) { close(); throw e }
    }

    private fun rescued(failed: Exception): DesktopFrameInput? {
        val readers = MediaBackend.rescue ?: return null
        val found = runCatching { readers.softFrames(context, uri, rescue = true) }.getOrNull() ?: return null
        Log.w("AMVideo", "the system decoder failed on $uri, FFamp decodes it: $failed")
        runCatching { codec?.release() }
        codec = null
        ffamp = readers
        soft = found
        return found
    }

    fun wantedPts(timeUs: Long): Long = timestamps.ptsAt(timeUs)

    fun frame(timeUs: Long, exact: Boolean, obsolete: () -> Boolean): DecodedFrame? {
        val wanted = wantedPts(timeUs)
        if (wanted == currentPts) return current
        soft?.let { return softFrame(it, wanted, exact, obsolete) }
        val decoder = codec ?: return current
        return systemOrFfamp({ decoded(decoder, wanted, exact, obsolete) }, ::rescued) { softFrame(it, wanted, exact, obsolete) }
    }

    private fun decoded(decoder: MediaCodec, wanted: Long, exact: Boolean, obsolete: () -> Boolean): DecodedFrame? {
        val at = if (skipped) decodedTo else currentPts
        if (!positioned || (if (skipped) wanted <= at else wanted < at) || wanted - at > 1_000_000 || outputEnded) reposition(decoder, wanted)
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
                if (info.size > 0) decodedTo = maxOf(decodedTo, info.presentationTimeUs)
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
                skipped = true
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
            decoder.configure(format, frames!!.surface, null, 0)
            decoder.start()
        }
        drainImages()
        extractor.seekTo(wanted, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false; outputEnded = false; queued = false; positioned = true; skipped = false
        decodedTo = extractor.sampleTime - 1
        seeks++
    }

    private fun show(decoder: MediaCodec, index: Int, pts: Long, wanted: Long, exact: Boolean): DecodedFrame? {
        decoder.releaseOutputBuffer(index, true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var image: DecodedFrame? = null
        while (image == null) {
            val next = frames?.poll(deadline - System.nanoTime()) ?: break
            if (kotlin.math.abs(next.timestamp / 1000 - pts) <= 1000) image = next else next.close()
        }
        if (image == null) { positioned = false; return if (exact) null else current }
        current?.close()
        current = image
        skipped = false
        currentPts = if (pts > wanted || pts < wanted && outputEnded) wanted else pts
        return image
    }

    private fun softFrame(source: DesktopFrameInput, wanted: Long, exact: Boolean, obsolete: () -> Boolean): DecodedFrame? {
        var made: DecodedFrame? = null
        kotlinx.coroutines.runBlocking(StopReading(obsolete)) { source.read(listOf(wanted)) { _, yuv -> made = ffamp!!.softFrame(yuv, wanted, source.colorStandard, source.fullRange) } }
        val image = made ?: return if (exact || obsolete()) null else current
        if (currentPts == Long.MIN_VALUE || wanted < currentPts || wanted - currentPts > 1_000_000) seeks++
        current?.close()
        current = image
        currentPts = wanted
        return image
    }

    private fun drainImages() { frames?.drain() }

    override fun close() {
        current?.close(); current = null
        soft?.close(); soft = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        frames?.close(); frames = null
        extractor.release()
    }
}
