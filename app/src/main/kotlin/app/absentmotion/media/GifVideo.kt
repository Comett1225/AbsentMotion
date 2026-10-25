package app.absentmotion.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.absentmotion.ai.aiFile
import app.absentmotion.ai.matteLuma
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class GifVideo(val media: MediaDeclaration, val matte: MediaDeclaration?, val width: Int, val height: Int, val files: List<File>)

fun gifEncodeSize(width: Int, height: Int, supported: (Int, Int) -> Boolean): Pair<Int, Int>? {
    fun even(v: Double) = maxOf(2, (v / 2).roundToInt() * 2)
    val scales = listOf(1.0) + generateSequence(0.75) { it * 0.75 }.takeWhile { minOf(width, height) * it >= 16 } + listOf(2.0, 4.0, 8.0)
    return scales.map { even(width * it) to even(height * it) }.firstOrNull { (w, h) -> supported(w, h) }
}

fun gifFrameRate(summary: GifSummary): Int = (summary.frames * 1000.0 / summary.durationMs.coerceAtLeast(1)).roundToInt().coerceIn(1, 60)

suspend fun gifVideo(context: Context, uri: Uri): GifVideo? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val job = coroutineContext.job
    fun open() = resolver.openInputStream(uri) ?: throw GifError(GifProblem.Damaged)
    val summary = summarizeGif({ job.ensureActive() }, ::open)
    if (summary.frames < 2) return@withContext null
    val fps = gifFrameRate(summary)
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val encoder = list.codecInfos.firstOrNull { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
        ?: error("no H.264 encoder")
    val caps = encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: error("no H.264 video encoder")
    val (width, height) = gifEncodeSize(summary.width, summary.height) { w, h -> caps.isSizeSupported(w, h) } ?: error("no H.264 size for ${summary.width}x${summary.height}")
    val colourFile = aiFile(context, "mp4")
    val matteFile = File(colourFile.parentFile, colourFile.nameWithoutExtension + "-mask.mp4").takeIf { summary.transparent }
    val made = listOfNotNull(colourFile, matteFile)
    var success = false
    try {
        val xs = IntArray(width) { (it * summary.width / width).coerceAtMost(summary.width - 1) }
        val ys = IntArray(height) { (it * summary.height / height).coerceAtMost(summary.height - 1) }
        val starts = gifStartsUs(summary.delaysMs)
        val total = summary.durationMs * 1000
        YuvEncoder.open(colourFile, width, height, fps, (width * height * 8).coerceIn(1_500_000, 20_000_000)).use { colour ->
            (matteFile?.let { YuvEncoder.open(it, width, height, fps, (width * height * 4).coerceIn(500_000, 6_000_000)) }).use { matte ->
                var index = 0
                open().use { stream ->
                    GifDecoder(stream).read { canvas, _ ->
                        job.ensureActive()
                        if (index < starts.size) {
                            val times = if (index == starts.size - 1) listOf(starts[index], (starts[index] + total) / 2) else listOf(starts[index])
                            for (pts in times) {
                                colour.write(pts) { image -> image.fillColour(canvas, summary.width, xs, ys) }
                                matte?.write(pts) { image -> image.fillMatte(canvas, summary.width, xs, ys) }
                            }
                        }
                        index++
                    }
                }
                colour.finish()
                matte?.finish()
            }
        }
        coroutineContext.ensureActive()
        val title = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }
            .getOrNull() ?: uri.lastPathSegment ?: "GIF"
        fun declare(file: File, label: String) = MediaDeclaration(XmlNode("media", linkedMapOf(
            "uri" to FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString(), "filename" to file.name, "title" to label,
            "type" to "video/mp4", "size" to "${file.length()}", "orientation" to "0", "duration" to "$total", "fps" to fpsText(fps.toFloat()),
            "width" to "$width", "height" to "$height", "infoUpdated" to "${System.currentTimeMillis()}", "audio" to "none",
        )))
        GifVideo(declare(colourFile, title), matteFile?.let { declare(it, title) }, width, height, made).also { success = true }
    } finally {
        if (!success) made.forEach { it.delete() }
    }
}

private fun Image.fillColour(canvas: IntArray, stride: Int, xs: IntArray, ys: IntArray) {
    val (yPlane, uPlane, vPlane) = planes
    val y = yPlane.buffer
    for (row in ys.indices) {
        val base = row * yPlane.rowStride
        val source = ys[row] * stride
        for (col in xs.indices) {
            val p = canvas[source + xs[col]]
            val r = if (p ushr 24 == 0) 0 else p shr 16 and 255
            val g = if (p ushr 24 == 0) 0 else p shr 8 and 255
            val b = if (p ushr 24 == 0) 0 else p and 255
            y.put(base + col * yPlane.pixelStride, (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte())
        }
    }
    val u = uPlane.buffer
    val v = vPlane.buffer
    for (row in 0 until (ys.size + 1) / 2) {
        for (col in 0 until (xs.size + 1) / 2) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = canvas[ys[minOf(row * 2 + dy, ys.size - 1)] * stride + xs[minOf(col * 2 + dx, xs.size - 1)]]
                if (p ushr 24 != 0) { r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255 }
            }
            r /= 4; g /= 4; b /= 4
            u.put(row * uPlane.rowStride + col * uPlane.pixelStride, (((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128).toByte())
            v.put(row * vPlane.rowStride + col * vPlane.pixelStride, (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).toByte())
        }
    }
}

private fun Image.fillMatte(canvas: IntArray, stride: Int, xs: IntArray, ys: IntArray) {
    val (yPlane, uPlane, vPlane) = planes
    val y = yPlane.buffer
    for (row in ys.indices) {
        val base = row * yPlane.rowStride
        val source = ys[row] * stride
        for (col in xs.indices) y.put(base + col * yPlane.pixelStride, matteLuma(canvas[source + xs[col]] ushr 24).toByte())
    }
    for (plane in listOf(uPlane, vPlane)) {
        val buffer = plane.buffer
        for (row in 0 until (ys.size + 1) / 2) for (col in 0 until (xs.size + 1) / 2) buffer.put(row * plane.rowStride + col * plane.pixelStride, 128.toByte())
    }
}

private class YuvEncoder(private val codec: MediaCodec, private val muxer: MediaMuxer) : Closeable {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var finished = false

    fun write(pts: Long, fill: (Image) -> Unit) {
        val index = input()
        val size = codec.getInputBuffer(index)!!.capacity()
        fill(checkNotNull(codec.getInputImage(index)))
        codec.queueInputBuffer(index, 0, size, pts, 0)
        drain(false)
    }

    fun finish() {
        codec.queueInputBuffer(input(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        finished = true
    }

    private fun input(): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueInputBuffer(5_000)
            if (index >= 0) return index
            drain(false)
        }
        error("the encoder stopped taking frames")
    }

    private fun drain(end: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                !end -> return
                System.nanoTime() > deadline -> error("the encoder did not finish")
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        try {
            if (finished && track >= 0) muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    companion object {
        fun open(file: File, width: Int, height: Int, fps: Int, bitrate: Int): YuvEncoder {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: error("no H.264 encoder for ${width}x$height")
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                return YuvEncoder(codec, MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        }
    }
}
