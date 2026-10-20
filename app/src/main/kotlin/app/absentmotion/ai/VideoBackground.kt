package app.absentmotion.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import app.absentmotion.editor.grouped
import app.absentmotion.editor.mediaHalf
import app.absentmotion.media.FrameReader
import app.absentmotion.media.Yuv
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MediaFillMode
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.floats
import app.absentmotion.model.formatFloats
import app.absentmotion.model.fpsText
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal val MatteSides = listOf(360, 540, 720)
internal const val MAX_MATTE_SECONDS = 300
internal const val LONG_MATTE_SECONDS = 60

internal fun matteSize(width: Int, height: Int, side: Int): Pair<Int, Int> {
    val k = min(1.0, side.toDouble() / max(1, min(width, height)))
    fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
    return even(width * k) to even(height * k)
}

internal fun matteBitrate(width: Int, height: Int): Int = (width.toLong() * height * 4).coerceIn(500_000, 6_000_000).toInt()

internal fun Layer.matteSeconds(media: MediaDeclaration): Double {
    val fill = mediaFill ?: return 0.0
    val end = if (fill.outTime == Int.MAX_VALUE) media.duration / 1000.0 else fill.outTime.toDouble()
    return ((end - fill.inTime) / 1000).coerceAtLeast(0.0)
}

internal fun matteBytes(seconds: Double, width: Int, height: Int): Long = (matteBitrate(width, height) / 8.0 * seconds).roundToLong() + 1_000_000

internal fun matteStamps(stamps: LongArray, inMs: Int, outMs: Int): List<Long> {
    if (stamps.isEmpty()) return emptyList()
    val first = stamps.ptsAt(inMs * 1000L)
    val last = if (outMs == Int.MAX_VALUE) Long.MAX_VALUE else outMs * 1000L
    return stamps.filter { it in first..last }
}

internal fun matteLuma(alpha: Int): Int = 16 + (alpha * 219 + 127) / 255

internal fun smoothMatte(previous: ByteArray, current: ByteArray, next: ByteArray): ByteArray = ByteArray(current.size) { i ->
    val p = previous[i].toInt() and 0xFF
    val c = current[i].toInt() and 0xFF
    val n = next[i].toInt() and 0xFF
    (if (abs(p - n) < STABLE_SPREAD) (p + 2 * c + n + 2) / 4 else c).toByte()
}

private const val STABLE_SPREAD = 64

internal class MatteSmoother {
    private var before: ByteArray? = null
    private var pending: Pair<Long, ByteArray>? = null

    fun push(pts: Long, matte: ByteArray): Pair<Long, ByteArray>? {
        val held = pending
        pending = pts to matte
        if (held == null) return null
        val out = smoothMatte(before ?: held.second, held.second, matte)
        before = held.second
        return held.first to out
    }

    fun flush(): Pair<Long, ByteArray>? {
        val (pts, matte) = pending ?: return null
        pending = null
        return pts to smoothMatte(before ?: matte, matte, matte)
    }
}

internal fun Yuv.argb(outWidth: Int, outHeight: Int, standard: Int = 0, fullRange: Boolean = false): IntArray {
    val kr = if (standard == 1) 0.2126f else if (standard == 6) 0.2627f else 0.299f
    val kb = if (standard == 1) 0.0722f else if (standard == 6) 0.0593f else 0.114f
    val kg = 1 - kr - kb
    val ys = if (fullRange) 1f else 255f / 219
    val cs = if (fullRange) 1f else 255f / 224
    val yo = if (fullRange) 0f else 16f
    val out = IntArray(outWidth * outHeight)
    for (j in 0 until outHeight) {
        val sy = ((j + 0.5f) * height / outHeight).toInt().coerceIn(0, height - 1) + top
        val cy = sy / 2
        for (i in 0 until outWidth) {
            val sx = ((i + 0.5f) * width / outWidth).toInt().coerceIn(0, width - 1) + left
            val cx = sx / 2
            val luma = ((y.get(sy * yRow + sx * yPixel).toInt() and 0xFF) - yo) * ys
            val cb = ((u.get(cy * uvRow + cx * uvPixel).toInt() and 0xFF) - 128) * cs
            val cr = ((v.get(cy * uvRow + cx * uvPixel).toInt() and 0xFF) - 128) * cs
            val r = luma + 2 * (1 - kr) * cr
            val b = luma + 2 * (1 - kb) * cb
            val g = luma - (2 * kr * (1 - kr) * cr + 2 * kb * (1 - kb) * cb) / kg
            out[j * outWidth + i] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
    return out
}

private fun channel(v: Float): Int = v.roundToInt().coerceIn(0, 255)

internal fun Scene.withVideoMatte(original: Layer, matte: MediaDeclaration, frameWidth: Int, frameHeight: Int, matteId: Long, groupId: Long): Scene? {
    if (original.shape.isEmpty() || original.mediaFill?.video != true || layer(original.id) == null) return null
    val mode = original.mediaFill?.mode ?: MediaFillMode.Fill
    val fallback = if (original.shape == ".stamp") 150f else 100f
    fun half(value: String?): String {
        val size = floats(value, floatArrayOf(fallback, fallback))
        return formatFloats(mediaHalf(abs(size[0]), abs(size[1]), frameWidth, frameHeight, mode))
    }
    val size = original.node.children.firstOrNull { it.name == "property" && it["name"] == "size" }
    val matteSize = XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2"), size?.children?.filter { it.name == "kf" }?.map { it.with("v", half(it["v"])) }.orEmpty())
        .let { if (it.children.isEmpty()) it.with("value", half(size?.get("value"))) else it }
    val attrs = linkedMapOf("id" to "$matteId", "label" to "${original.label.ifEmpty { "Video" }} mask")
    for (key in listOf("startTime", "endTime", "inTime", "outTime", "speed", "loop", "parent")) original.node[key]?.let { attrs[key] = it }
    attrs += listOf("s" to ".rect", "fillType" to "media", "fillVideo" to matte.uri, "mediaFillMode" to "stretch")
    val key = XmlNode("effect", linkedMapOf("id" to "absent.lumakey", "hidden" to "false"), listOf(
        XmlNode("property", linkedMapOf("name" to "threshold", "type" to "float", "value" to "0.03")),
        XmlNode("property", linkedMapOf("name" to "softness", "type" to "float", "value" to "0.94")),
    ))
    val node = XmlNode("shape", attrs, listOfNotNull(
        original.node.child("transform"), original.node.child("speedMap"), matteSize, XmlNode("gain", linkedMapOf("value" to "0")),
    ) + original.effects.filter { it["id"] == "absent.timequant" } + key)
    val children = node.let { added -> this.node.children.flatMap { if (it.name in Scene.LAYER_TAGS && Layer(it).id == original.id) listOf(it, added) else listOf(it) } }
    val withMatte = Scene(this.node.copy(children = children)).withMedia(matte)
    return withMatte.grouped(setOf(original.id, matteId), "mask", groupId, "${original.label.ifEmpty { "Video" }} cut out")
}

internal class MatteEncoder private constructor(private val codec: MediaCodec, private val muxer: MediaMuxer, val width: Int, val height: Int) : Closeable {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var finished = false

    fun write(pts: Long, matte: ByteArray) {
        check(matte.size == width * height) { "The mask has an unexpected size" }
        val index = input()
        val size = codec.getInputBuffer(index)!!.capacity()
        val image = checkNotNull(codec.getInputImage(index)) { "The video encoder gave no input image" }
        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer
        for (row in 0 until height) {
            val base = row * yPlane.rowStride
            for (col in 0 until width) y.put(base + col * yPlane.pixelStride, matteLuma(matte[row * width + col].toInt() and 0xFF).toByte())
        }
        for (plane in listOf(uPlane, vPlane)) {
            val buffer = plane.buffer
            for (row in 0 until (height + 1) / 2) {
                val base = row * plane.rowStride
                for (col in 0 until (width + 1) / 2) buffer.put(base + col * plane.pixelStride, 128.toByte())
            }
        }
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
        error("The video encoder stopped responding")
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
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        check(track >= 0) { "The video encoder sent frames before its format" }
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                !end -> return
                System.nanoTime() > deadline -> error("The video encoder did not finish")
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (finished && track >= 0) muxer.stop()
        runCatching { muxer.release() }
    }

    companion object {
        fun open(file: File, width: Int, height: Int, fps: Int): MatteEncoder {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for ((w, h) in listOf(width to height, (width + 15) / 16 * 16 to (height + 15) / 16 * 16)) {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, matteBitrate(w, h))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                val name = list.findEncoderForFormat(format) ?: continue
                val codec = MediaCodec.createByCodecName(name)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    return MatteEncoder(codec, MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), w, h)
                } catch (e: Exception) {
                    codec.release()
                    if (w != width || h != height) throw e
                }
            }
            error("This phone cannot encode the mask video")
        }
    }
}

internal class VideoMatte(val media: AiMedia, val frameWidth: Int, val frameHeight: Int)

internal object VideoBackground {
    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, side: Int, progress: AiProgress): VideoMatte {
        val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { "Select a video layer" }
        check(media.width > 0 && media.height > 0) { "This video has no size information" }
        val seconds = layer.matteSeconds(media)
        check(seconds <= MAX_MATTE_SECONDS) { "Clips longer than ${MAX_MATTE_SECONDS / 60} minutes are not supported yet. Trim or split the clip first." }
        val (width, height) = matteSize(media.width, media.height, side)
        val file = aiFile(context, "mp4")
        val needed = matteBytes(seconds, width, height) * 2 + FREE_MARGIN
        check(file.parentFile!!.usableSpace >= needed) { "Not enough free storage: this needs about ${needed / 1_000_000} MB." }
        val fps = (media.fps ?: 30f).roundToInt().coerceIn(1, 240)
        var success = false
        try {
            progress(0f, "Loading background removal model…")
            var encoded = width to height
            val (frameWidth, frameHeight) = BackgroundRemoval.withFrameMatte(context) { matte ->
                FrameReader(context, fill.uri).use { reader ->
                    val stamps = matteStamps(reader.timestamps, fill.inTime, fill.outTime)
                    check(stamps.isNotEmpty()) { "This clip has no video frames to process" }
                    var frame = media.width to media.height
                    MatteEncoder.open(file, width, height, fps).use { encoder ->
                        encoded = encoder.width to encoder.height
                        if (stamps.first() > 0) encoder.write(0L, ByteArray(encoder.width * encoder.height))
                        val smoother = MatteSmoother()
                        val started = System.nanoTime()
                        var done = 0
                        for (batch in stamps.chunked(BATCH)) {
                            reader.read(batch) { pts, yuv ->
                                frame = yuv.width to yuv.height
                                val alpha = matte(yuv.argb(encoder.width, encoder.height, reader.colorStandard, reader.fullRange), encoder.width, encoder.height)
                                check(alpha.size == encoder.width * encoder.height) { "The foreground mask has an unexpected size" }
                                smoother.push(pts, alpha)?.let { (p, m) -> encoder.write(p, m) }
                                done++
                                progress(0.02f + 0.96f * done / stamps.size, status(done, stamps.size, System.nanoTime() - started))
                            }
                        }
                        smoother.flush()?.let { (p, m) -> encoder.write(p, m) }
                        progress(0.99f, "Saving the mask video…")
                        encoder.finish()
                    }
                    frame
                }
            }
            return VideoMatte(aiMedia(context, file, "video/mp4", mapOf(
                "width" to "${encoded.first}", "height" to "${encoded.second}", "orientation" to "0", "duration" to "${media.duration}", "fps" to fpsText(fps.toFloat()),
            )), frameWidth, frameHeight).also { success = true }
        } finally {
            if (!success) file.delete()
        }
    }

    private fun status(done: Int, total: Int, elapsedNs: Long): String {
        val text = "Removing background: frame $done of $total"
        if (done < 3) return "$text…"
        val left = elapsedNs / 1e9 / done * (total - done)
        return "$text, " + when {
            left < 60 -> "less than a minute left"
            else -> "about ${(left / 60).roundToInt()} min left"
        }
    }

    private const val BATCH = 16
    private const val FREE_MARGIN = 50_000_000L
}
