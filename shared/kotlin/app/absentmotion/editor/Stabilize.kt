package app.absentmotion.editor

import android.content.Context
import app.absentmotion.media.FrameReader
import app.absentmotion.media.LumaFrame
import app.absentmotion.media.MotionFrame
import app.absentmotion.media.luma
import app.absentmotion.media.motionFactor
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.valueAt
import app.absentmotion.model.videoTimeUs
import app.absentmotion.model.withEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val STABILIZER_EFFECT = "absent.stabilizer"
private val LEVEL_SECONDS = floatArrayOf(0f, 0.25f, 0.6f, 1.5f)
private const val MAX_ZOOM = 1.6f
private const val STEADY_TOLERANCE = 0.0002f

internal class StabSource(val uri: String, val width: Int, val height: Int, val from: Long, val to: Long, val samples: List<Pair<Long, FloatArray>>) {
    fun node(): XmlNode = XmlNode("stabpath", linkedMapOf(
        "uri" to uri, "w" to "$width", "h" to "$height", "from" to "$from", "to" to "$to",
        "src" to samples.joinToString(" ") { (p, v) -> String.format(Locale.US, "%d,%.6f,%.6f,%.6f", p, v[0], v[1], v[2]) },
    ))
}

internal fun XmlNode.stabSource(): StabSource? {
    val n = child("stabpath") ?: return null
    val uri = n["uri"] ?: return null
    val w = n["w"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val h = n["h"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val samples = n["src"].orEmpty().split(' ').mapNotNull { e ->
        val p = e.split(',').takeIf { it.size == 4 } ?: return@mapNotNull null
        val v = (1..3).map { p[it].finiteFloat() ?: return@mapNotNull null }
        p[0].toLongOrNull()?.let { it to v.toFloatArray() }
    }.sortedBy { it.first }
    if (samples.isEmpty()) return null
    return StabSource(uri, w, h, n["from"]?.toLongOrNull() ?: samples.first().first, n["to"]?.toLongOrNull() ?: samples.last().first, samples)
}

internal fun stabLevel(effect: XmlNode): Int = effect.effectProperty("stabilization")?.valueAt(0f)?.finiteFloat()?.roundToInt()?.coerceIn(0, 3) ?: 0

private fun Layer.steadyFrames(scene: Scene): List<Int> = (scene.firstShownFrame(startTime)..scene.lastShownFrame(endTime)).toList()

internal fun Scene.stabKey(layer: Layer, effect: XmlNode): String = listOf(
    fps, layer.endTime - layer.startTime, layer.mediaFill?.uri, layer.node["inTime"], layer.node["outTime"], layer.node["speed"], layer.node["loop"],
    layer.node["loopIn"], layer.node.child("speedMap"), layer.node.child("timeRemap"),
    layer.effects.firstOrNull { it["id"] == "absent.timequant" && it["hidden"] != "true" }, stabLevel(effect),
).hashCode().toUInt().toString(16)


internal fun steadyFix(shaky: FloatArray, smooth: FloatArray): FloatArray {
    val turn = smooth[2] - shaky[2]
    val c = cos(turn)
    val s = sin(turn)
    return floatArrayOf(smooth[0] - (c * shaky[0] - s * shaky[1]), smooth[1] - (s * shaky[0] + c * shaky[1]), turn)
}

internal fun steadyZoom(fix: FloatArray, aspect: Float): Float {
    val c = cos(fix[2])
    val s = sin(fix[2])
    fun covered(z: Float): Boolean {
        for (sx in listOf(-0.5f, 0.5f)) for (sy in listOf(-0.5f * aspect, 0.5f * aspect)) {
            val ax = sx / z - fix[0]
            val ay = sy / z - fix[1]
            if (kotlin.math.abs(c * ax + s * ay) > 0.5f + 1e-5f || kotlin.math.abs(-s * ax + c * ay) > 0.5f * aspect + 1e-5f) return false
        }
        return true
    }
    if (covered(1f)) return 1f
    var lo = 1f
    var hi = 4f
    if (!covered(hi)) return hi
    repeat(24) {
        val mid = (lo + hi) / 2
        if (covered(mid)) hi = mid else lo = mid
    }
    return hi
}

internal fun Scene.stabPath(layer: Layer, effect: XmlNode, source: StabSource): Pair<String, Float> {
    val level = stabLevel(effect)
    val samples = source.samples
    val frames = layer.steadyFrames(this)
    if (level == 0 || frames.isEmpty() || samples.size < 2) return "" to 1f
    val span = (samples.last().first - samples.first().first).coerceAtLeast(1)
    val sigma = LEVEL_SECONDS[level] * (samples.size - 1) * 1_000_000f / span
    val raw = samples.map { it.second }
    val smooth = smoothFace(raw, FloatArray(raw.size) { sigma })
    val pts = LongArray(samples.size) { samples[it].first }
    val timing = VideoTiming(layer)
    val aspect = source.height.toFloat() / source.width
    val length = (layer.endTime - layer.startTime).coerceAtLeast(1).toFloat()
    val perFrame = frames.map { f ->
        val i = pts.binarySearch(layer.videoTimeUs(frameStart(f), this, timing)).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        steadyFix(raw[i], smooth[i])
    }
    val times = FloatArray(2 * frames.size) { (frameStart(frames[it / 2] + it % 2) - 0.5f * (it % 2) - layer.startTime) / length }
    val fixes = List(times.size) { perFrame[it / 2] }
    val zoom = perFrame.maxOf { steadyZoom(it, aspect) }.coerceIn(1f, MAX_ZOOM)
    val path = simplifyKeys(times, fixes, STEADY_TOLERANCE).joinToString(" ") { i ->
        String.format(Locale.US, "%.6f,%.6f,%.6f,%.5f", times[i], fixes[i][0], fixes[i][1], Math.toDegrees(fixes[i][2].toDouble()))
    }
    return path to zoom
}

private fun Scene.steadyNode(layer: Layer, effect: XmlNode, source: StabSource): XmlNode {
    val (path, zoom) = stabPath(layer, effect, source)
    return source.node().with("key", stabKey(layer, effect)).with("path", path).with("zoom", String.format(Locale.US, "%.5f", zoom))
}

internal enum class StabState { NotVideo, None, Partial, Ready }

internal fun Scene.stabState(layer: Layer, effect: XmlNode): StabState {
    val fill = layer.mediaFill?.takeIf { it.video } ?: return StabState.NotVideo
    val source = effect.stabSource()?.takeIf { it.uri == fill.uri } ?: return StabState.None
    val timing = VideoTiming(layer)
    val needed = layer.steadyFrames(this).map { layer.videoTimeUs(frameStart(it), this, timing) }
    if (needed.isEmpty()) return StabState.Ready
    val slack = 1_000_000L / fps
    return if (needed.min() >= source.from - slack && needed.max() <= source.to + slack) StabState.Ready else StabState.Partial
}

private fun Scene.steadied(layer: Layer, effect: XmlNode): XmlNode {
    if (effect["id"] != STABILIZER_EFFECT) return effect
    val node = effect.child("stabpath") ?: return effect
    if (layer.node["matteFrame"] != null) return effect
    if (node["uri"] != layer.mediaFill?.takeIf { it.video }?.uri) return effect.withChild("stabpath", null)
    if (node["key"] == stabKey(layer, effect)) return effect
    val source = effect.stabSource() ?: return effect.withChild("stabpath", null)
    return effect.withChild("stabpath", steadyNode(layer, effect, source))
}

internal fun Scene.withStabPathsFollowing(): Scene {
    var next = this
    for (layer in layers) {
        val effects = layer.effects
        if (effects.none { it["id"] == STABILIZER_EFFECT && it.child("stabpath") != null }) continue
        val changed = effects.map { steadied(layer, it) }
        if (changed.indices.all { changed[it] === effects[it] }) continue
        next = next.update(layer.id) { l -> changed.indices.fold(l) { acc, i -> acc.withEffect(i) { changed[i] } } }
    }
    return next
}

internal fun Scene.withStabSource(layerId: Long, index: Int, source: StabSource): Scene = update(layerId) { layer ->
    layer.withEffect(index) { effect ->
        if (effect["id"] != STABILIZER_EFFECT) effect else effect.withChild("stabpath", steadyNode(layer, effect, source))
    }
}

internal suspend fun followShake(
    pts: List<Long>, read: suspend (List<Long>, suspend (Long, LumaFrame) -> Unit) -> Unit, progress: suspend (Float) -> Unit = {},
): List<Pair<Long, FloatArray>> {
    val out = ArrayList<Pair<Long, FloatArray>>(pts.size)
    var previous: MotionFrame? = null
    var x = 0f
    var y = 0f
    var a = 0f
    for (batch in pts.chunked(BATCH)) {
        val got = HashMap<Long, LumaFrame>()
        read(batch) { p, luma -> got[p] = luma }
        for (p in batch) {
            val frame = MotionFrame(got[p] ?: continue)
            previous?.let { before ->
                val step = frame.stepFrom(before)
                val c = cos(step[2])
                val s = sin(step[2])
                val nx = c * x - s * y + step[0]
                y = s * x + c * y + step[1]
                x = nx
                a += step[2]
            }
            out += p to floatArrayOf(x, y, a)
            previous = frame
        }
        progress(out.size.toFloat() / pts.size.coerceAtLeast(1))
    }
    return out
}

internal suspend fun Scene.analyzeShake(context: Context, layerId: Long, progress: suspend (Float) -> Unit = {}): StabSource? {
    val video = layer(layerId) ?: return null
    val uri = video.mediaFill?.takeIf { it.video }?.uri ?: return null
    val media = media(uri)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
    val frames = video.steadyFrames(this)
    if (frames.isEmpty()) return null
    val timing = VideoTiming(video)
    val needed = frames.map { video.videoTimeUs(frameStart(it), this, timing) }
    return withContext(Dispatchers.IO) {
        FrameReader(context, uri).use { reader ->
            val wanted = framePts(video, frames, reader.timestamps)
            val lo = wanted.min()
            val hi = wanted.max()
            val pts = reader.timestamps.filter { it in lo..hi }.ifEmpty { wanted.distinct().sorted() }
            val samples = followShake(pts, { batch, deliver -> reader.read(batch) { p, yuv -> deliver(p, yuv.luma(motionFactor(yuv.width, yuv.height))) } }, progress)
            if (samples.size < 2) null else StabSource(uri, media.width, media.height, minOf(lo, needed.min()), maxOf(hi, needed.max()), samples)
        }
    }
}
