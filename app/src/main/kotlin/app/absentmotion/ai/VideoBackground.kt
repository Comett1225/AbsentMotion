package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
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
    if (fill.outTime == Int.MAX_VALUE && media.duration <= 0) return MAX_MATTE_SECONDS.toDouble()
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

internal val MatteFollowedEffects = setOf(
    "absent.autoshake", "absent.bend", "absent.box", "absent.bulge",
    "absent.circularripple", "absent.cross", "absent.cube", "absent.curl",
    "absent.cylinder", "absent.displacementmap", "absent.echo", "absent.ellipsoid",
    "absent.flip", "absent.fractalwarp", "absent.glass", "absent.glitch",
    "absent.gridrepeat", "absent.heart", "absent.hexagontilerotate", "absent.hexagontileshift",
    "absent.hexagontiling", "absent.hexprism", "absent.hollowbox", "absent.innerbulge",
    "absent.kaleidoscope", "absent.linearrepeat", "absent.magnifybackground", "absent.mirror",
    "absent.movealongpath", "absent.noisedisplace", "absent.octahedron", "absent.offset",
    "absent.ominoglass", "absent.oscillate", "absent.polar", "absent.polardisplacement",
    "absent.pulsesize", "absent.pyramid", "absent.radialrepeat",
    "absent.randomdisplace", "absent.randomjitter", "absent.rasterextrude", "absent.rastertransform", "absent.reorient360",
    "absent.repeat", "absent.repeatalongpath", "absent.ripple", "absent.scatterfield",
    "absent.shutterblur", "absent.spherize", "absent.spin", "absent.squeeze",
    "absent.starpolyhedron", "absent.starprism", "absent.stretch", "absent.stretchaxis",
    "absent.swing", "absent.tile", "absent.tilerotate", "absent.tiles",
    "absent.tileshift", "absent.torus", "absent.tunnel", "absent.turbulentdisplace",
    "absent.twirl", "absent.viewer360", "absent.wavewarp",
)

private val MatteTimeAttributes = listOf("startTime", "endTime", "inTime", "outTime", "speed", "loop", "loopIn", "parent")

private fun lumaKey() = XmlNode("effect", linkedMapOf("id" to "absent.lumakey", "hidden" to "false"), listOf(
    XmlNode("property", linkedMapOf("name" to "threshold", "type" to "float", "value" to "0.03")),
    XmlNode("property", linkedMapOf("name" to "softness", "type" to "float", "value" to "0.94")),
))

private fun Layer.matteSize(frameWidth: Int, frameHeight: Int): XmlNode {
    val mode = mediaFill?.mode ?: MediaFillMode.Fill
    val fallback = if (shape == ".stamp") 150f else 100f
    fun half(value: String?): String {
        val size = floats(value, floatArrayOf(fallback, fallback))
        return formatFloats(mediaHalf(abs(size[0]), abs(size[1]), frameWidth, frameHeight, mode))
    }
    val size = node.children.firstOrNull { it.name == "property" && it["name"] == "size" }
    return XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2"), size?.children?.filter { it.name == "kf" }?.map { it.with("v", half(it["v"])) }.orEmpty())
        .let { if (it.children.isEmpty()) it.with("value", half(size?.get("value"))) else it }
}

private fun XmlNode.isMatteSize() = name == "property" && this["name"] == "size"

private val MatteFollowedChildren = setOf("transform", "speedMap", "gain", "effect")

private fun XmlNode.isFollowedEffect() = this["id"] == "absent.timequant" || this["id"] in MatteFollowedEffects

internal fun Layer.followingOriginal(original: Layer): Layer {
    val frame = node["matteFrame"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it.all { v -> v > 0 } }
    val size = if (frame != null) original.matteSize(frame[0], frame[1]) else node.children.firstOrNull { it.isMatteSize() }
    val opacity = node.child("transform")?.child("opacity")
    val placement = original.node.child("transform")?.let { it.copy(children = it.children.filterNot { c -> c.name == "opacity" }) }
    val transform = if (opacity == null) placement else (placement ?: XmlNode("transform")).let { it.copy(children = it.children + opacity) }
    val moving = original.effects.filter { it.isFollowedEffect() }
    val key = effects.firstOrNull { it["id"] == "absent.lumakey" } ?: lumaKey()
    val own = effects.filter { it !== key && !it.isFollowedEffect() }
    val kept = node.children.filterNot { it.name in MatteFollowedChildren || it.isMatteSize() }
    val attrs = MatteTimeAttributes.fold(node) { acc, name -> acc.with(name, original.node[name]) }.with("noiseId", "${original.id}")
    return copy(node = attrs.copy(children = listOfNotNull(transform, original.node.child("speedMap"), size, node.child("gain")) + kept + moving + key + own))
}

internal fun Scene.withVideoMatte(original: Layer, matte: MediaDeclaration, frameWidth: Int, frameHeight: Int, matteId: Long, groupId: Long): Scene? {
    if (original.shape.isEmpty() || original.mediaFill?.video != true || layer(original.id) == null) return null
    val attrs = linkedMapOf("id" to "$matteId", "label" to AppText.get(R.string.ai_video_mask_name, original.label.ifEmpty { AppText.get(R.string.model_layer_video) }))
    attrs += listOf("s" to ".rect", "fillType" to "media", "fillVideo" to matte.uri, "mediaFillMode" to "stretch", "matteFrame" to "$frameWidth,$frameHeight")
    val node = Layer(XmlNode("shape", attrs, listOf(XmlNode("gain", linkedMapOf("value" to "0"))))).followingOriginal(original).node
    val children = this.node.children.flatMap { if (it.name in Scene.LAYER_TAGS && Layer(it).id == original.id) listOf(it, node) else listOf(it) }
    val withMatte = Scene(this.node.copy(children = children)).withMedia(matte)
    return withMatte.grouped(setOf(original.id, matteId), "mask", groupId, AppText.get(R.string.ai_video_cut_out_name, original.label.ifEmpty { AppText.get(R.string.model_layer_video) }))
}

private fun Scene.followMattes(): Scene {
    val matte = layers.getOrNull(1)?.takeIf { it.node.child("gain") != null && it.effects.any { e -> e["id"] == "absent.lumakey" } } ?: return this
    val original = layers[0].takeIf { it.kind == "shape" && it.mediaFill?.video == true } ?: return this
    val followed = matte.followingOriginal(original)
    return if (followed == matte) this else Scene(update(matte.id) { followed }.node.with("totalTime", node["totalTime"]))
}

private fun XmlNode.firstLayer(): XmlNode? = children.firstOrNull { it.name in Scene.LAYER_TAGS }

internal fun Scene.withMattesFollowing(previous: Scene? = null): Scene {
    var changed = false
    val children = node.children.map { child ->
        val group = if (child.name == "embedScene") Layer(child) else return@map child
        val inner = group.node.child("scene") ?: return@map child
        val earlier = previous?.node?.children?.firstOrNull { it.name == "embedScene" && it["id"] == child["id"] }?.child("scene")
        if (earlier == inner) return@map child
        var scene = Scene(inner).withMattesFollowing(earlier?.let(::Scene))
        if (group.aiMarks.any { it.kind == AiKind.Video } && (earlier == null || earlier.firstLayer() != inner.firstLayer())) scene = scene.followMattes()
        if (scene.node == inner) child else child.withChild("scene", scene.node).also { changed = true }
    }
    return if (changed) Scene(node.copy(children = children)) else this
}

internal class MatteEncoder private constructor(private val codec: MediaCodec, private val muxer: MediaMuxer, val width: Int, val height: Int) : Closeable {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var finished = false

    fun write(pts: Long, matte: ByteArray) {
        check(matte.size == width * height) { AppText.get(R.string.ai_mask_wrong_size) }
        val index = input()
        val size = codec.getInputBuffer(index)!!.capacity()
        val image = checkNotNull(codec.getInputImage(index)) { AppText.get(R.string.ai_encoder_no_image) }
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
        error(AppText.get(R.string.ai_encoder_not_responding))
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
                        check(track >= 0) { AppText.get(R.string.ai_encoder_frames_before_format) }
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                !end -> return
                System.nanoTime() > deadline -> error(AppText.get(R.string.ai_encoder_not_finished))
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
            error(AppText.get(R.string.ai_cannot_encode_mask))
        }
    }
}

internal class VideoMatte(val media: AiMedia, val frameWidth: Int, val frameHeight: Int)

internal object VideoBackground {
    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, side: Int, progress: AiProgress): VideoMatte {
        val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
        check(media.width > 0 && media.height > 0) { AppText.get(R.string.ai_video_no_size) }
        val seconds = layer.matteSeconds(media)
        check(seconds <= MAX_MATTE_SECONDS) { AppText.get(R.string.ai_clip_too_long, (MAX_MATTE_SECONDS / 60).toString()) }
        val (width, height) = matteSize(media.width, media.height, side)
        val file = aiFile(context, "mp4")
        val needed = matteBytes(seconds, width, height) * 2 + FREE_MARGIN
        check(file.parentFile!!.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
        val fps = (media.fps ?: 30f).roundToInt().coerceIn(1, 240)
        var success = false
        try {
            progress(0f, AppText.get(R.string.ai_loading_model))
            var encoded = width to height
            val (frameWidth, frameHeight) = BackgroundRemoval.withFrameMatte(context) { matte ->
                FrameReader(context, fill.uri).use { reader ->
                    val stamps = matteStamps(reader.timestamps, fill.inTime, fill.outTime)
                    check(stamps.isNotEmpty()) { AppText.get(R.string.ai_no_video_frames) }
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
                                check(alpha.size == encoder.width * encoder.height) { AppText.get(R.string.ai_mask_unexpected_size) }
                                smoother.push(pts, alpha)?.let { (p, m) -> encoder.write(p, m) }
                                done++
                                progress(0.02f + 0.96f * done / stamps.size, status(done, stamps.size, System.nanoTime() - started))
                            }
                        }
                        smoother.flush()?.let { (p, m) -> encoder.write(p, m) }
                        progress(0.99f, AppText.get(R.string.ai_saving_mask_video))
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
        if (done < 3) return AppText.get(R.string.ai_video_frame_start, done.toString(), total.toString())
        val left = elapsedNs / 1e9 / done * (total - done)
        return if (left < 60) AppText.get(R.string.ai_video_frame_under_minute, done.toString(), total.toString())
            else AppText.get(R.string.ai_video_frame_minutes_left, done.toString(), total.toString(), (left / 60).roundToInt().toString())
    }

    private const val BATCH = 16
    private const val FREE_MARGIN = 50_000_000L
}
