package app.absentmotion.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.hardware.HardwareBuffer
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private lateinit var format: MediaFormat
    var colorStandard = 0
        private set
    var colorRange = 0
        private set
    var timestamps: LongArray
        private set

    init {
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            format = extractor.getTrackFormat(track)
            readColor(format)
            extractor.selectTrack(track)
            timestamps = VideoSources.timestamps(context, uri)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            imageThread = HandlerThread("am-video-images").apply { start() }
            reader = ImageReader.newInstance(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
                ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).apply {
                setOnImageAvailableListener({ source -> source.acquireNextImage()?.let { images.offer(it) } }, Handler(imageThread!!.looper))
            }
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            codec = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format))
            codec!!.configure(format, reader!!.surface, null, 0)
            codec!!.start()
        } catch (e: Exception) { close(); throw e }
    }

    fun wantedPts(timeUs: Long): Long = timestamps.ptsAt(timeUs)

    fun frame(timeUs: Long, obsolete: () -> Boolean): Image? {
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
                    return show(decoder, index, info.presentationTimeUs, wanted)
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
            }
            if (held < 0) return current
            return show(decoder, held, heldPts, wanted).also { held = -1 }
        } finally {
            if (held >= 0) decoder.releaseOutputBuffer(held, false)
        }
    }

    private fun readColor(format: MediaFormat) {
        colorStandard = videoColorStandard(format.getInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard))
        colorRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)
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
    }

    private fun show(decoder: MediaCodec, index: Int, pts: Long, wanted: Long): Image? {
        decoder.releaseOutputBuffer(index, true)
        val image = images.poll(2, TimeUnit.SECONDS) ?: run { positioned = false; return current }
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
