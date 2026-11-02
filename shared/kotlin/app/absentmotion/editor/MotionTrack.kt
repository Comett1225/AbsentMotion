package app.absentmotion.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.media.LumaFrame
import app.absentmotion.media.PointTracker
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaFillMode
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.videoTimeUs
import app.absentmotion.model.withKeyframes
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal data class Sim(val a: Double = 1.0, val b: Double = 0.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    fun map(p: Pt): Pt = Pt((a * p.x - b * p.y + tx).toFloat(), (b * p.x + a * p.y + ty).toFloat())
    operator fun times(o: Sim) = Sim(a * o.a - b * o.b, a * o.b + b * o.a, a * o.tx - b * o.ty + tx, b * o.tx + a * o.ty + ty)
    val scale: Double get() = hypot(a, b)
    val degrees: Double get() = Math.toDegrees(atan2(b, a))

    companion object {
        fun between(from: List<Pt>, to: List<Pt>): Sim {
            if (from.size < 2) return Sim(tx = (to[0].x - from[0].x).toDouble(), ty = (to[0].y - from[0].y).toDouble())
            val px = (from[1].x - from[0].x).toDouble(); val py = (from[1].y - from[0].y).toDouble()
            val qx = (to[1].x - to[0].x).toDouble(); val qy = (to[1].y - to[0].y).toDouble()
            val d = px * px + py * py
            if (d < 1e-9) return Sim(tx = (to[0].x - from[0].x).toDouble(), ty = (to[0].y - from[0].y).toDouble())
            val a = (qx * px + qy * py) / d
            val b = (qy * px - qx * py) / d
            return Sim(a, b, to[0].x - (a * from[0].x - b * from[0].y), to[0].y - (b * from[0].x + a * from[0].y))
        }
    }
}

internal data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    fun map(p: Pt): Pt = Pt((a * p.x + c * p.y + tx).toFloat(), (b * p.x + d * p.y + ty).toFloat())
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty,
    )

    fun inverse(): Affine? {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return null
        return Affine(d / det, -b / det, -c / det, a / det, (c * ty - d * tx) / det, (b * tx - a * ty) / det)
    }

    companion object {
        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double) = Affine(x, 0.0, 0.0, y, 0.0, 0.0)
        fun rotate(degrees: Double): Affine {
            val r = Math.toRadians(degrees)
            return Affine(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
        }
        fun skew(kx: Double, ky: Double) = Affine(1.0, ky, kx, 1.0, 0.0, 0.0)
    }
}

enum class TrackRange { Forward, Backward, Whole }

internal fun Layer.shapeBox(): FloatArray? {
    if (shape.isNotEmpty()) {
        val fallback = if (shape == ".stamp") 150f else 100f
        val size = prop("size")
        val w = abs(size?.getOrNull(0) ?: fallback)
        val h = abs(size?.getOrNull(1) ?: fallback)
        return floatArrayOf(-w, -h, w, h)
    }
    if (kind != "shape") return null
    val points = contours?.flatMap { c -> c.knots.map { it.p } }.orEmpty()
    if (points.isEmpty()) return null
    return floatArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

internal fun mediaHalf(halfWidth: Float, halfHeight: Float, frameWidth: Int, frameHeight: Int, mode: MediaFillMode): FloatArray {
    if (mode == MediaFillMode.Stretch) return floatArrayOf(halfWidth, halfHeight)
    val s = if (mode == MediaFillMode.Fit) min(halfWidth / frameWidth, halfHeight / frameHeight) else max(halfWidth / frameWidth, halfHeight / frameHeight)
    return floatArrayOf(frameWidth * s, frameHeight * s)
}

internal fun Layer.mediaRect(frameWidth: Int, frameHeight: Int): FloatArray? {
    val box = shapeBox() ?: return null
    if (frameWidth <= 0 || frameHeight <= 0) return null
    val (w, h) = mediaHalf((box[2] - box[0]) / 2, (box[3] - box[1]) / 2, frameWidth, frameHeight, mediaFill?.mode ?: MediaFillMode.Fill)
    val cx = (box[0] + box[2]) / 2
    val cy = (box[1] + box[3]) / 2
    return floatArrayOf(cx - w, cy - h, cx + w, cy + h)
}

internal fun Scene.layerAffine(layer: Layer, timeMs: Int): Affine {
    val p = place(layer, timeMs)
    val skew = layer.at(timeMs, fps).transform("skew", 0f, 0f)
    return Affine.translate(p.x.toDouble(), p.y.toDouble()) * Affine.rotate(p.rot.toDouble()) *
        Affine.skew(tan(skew[0].toDouble()), tan(skew[1].toDouble())) * Affine.scale(p.sx.toDouble(), p.sy.toDouble()) *
        Affine.translate(-p.px.toDouble(), -p.py.toDouble())
}

internal fun Scene.videoToScene(layer: Layer, frame: Int, frameWidth: Int, frameHeight: Int): Affine? {
    val ms = frameStart(frame)
    val rect = layer.at(ms, fps).mediaRect(frameWidth, frameHeight) ?: return null
    val sx = (rect[2] - rect[0]).toDouble() / frameWidth
    val sy = (rect[3] - rect[1]).toDouble() / frameHeight
    return layerAffine(layer, ms) * Affine(sx, 0.0, 0.0, sy, rect[0] + 0.5 * sx, rect[1] + 0.5 * sy)
}

internal fun Scene.trackRuns(layer: Layer, from: Int, range: TrackRange): List<List<Int>> {
    val first = firstShownFrame(layer.startTime)
    val last = lastShownFrame(layer.endTime)
    if (first > last) return emptyList()
    val start = from.coerceIn(first, last)
    val forward = (start..last).toList()
    val backward = (start downTo first).toList()
    return when (range) {
        TrackRange.Forward -> listOf(forward)
        TrackRange.Backward -> listOf(backward)
        TrackRange.Whole -> listOf(forward, backward)
    }.filter { it.size > 1 }
}

internal fun Scene.framePts(layer: Layer, frames: List<Int>, stamps: LongArray): List<Long> =
    frames.map { stamps.ptsAt(layer.videoTimeUs(frameStart(it), this)) }

internal fun sourceOrder(pts: List<Long>, stamps: LongArray, maxGap: Int = MAX_FILL): List<Long> {
    val out = ArrayList<Long>()
    for (p in pts) {
        val last = out.lastOrNull()
        if (last == p) continue
        if (last != null) {
            val i = stamps.binarySearch(last)
            val j = stamps.binarySearch(p)
            if (i >= 0 && j >= 0 && abs(j - i) in 2..maxGap) {
                val step = if (j > i) 1 else -1
                var k = i + step
                while (k != j) { out += stamps[k]; k += step }
            }
        }
        out += p
    }
    return out
}

internal class TrackStep(val frame: Int, val points: List<Pt>)

internal class TrackOutcome(val steps: List<TrackStep>, val lostAt: Int?, val lostPoints: List<Pt>)

internal fun LumaFrame.fromMedia(p: Pt, mediaWidth: Int, mediaHeight: Int): Pt =
    Pt((p.x + 0.5f) * sourceWidth / mediaWidth / factor - 0.5f, (p.y + 0.5f) * sourceHeight / mediaHeight / factor - 0.5f)

internal fun LumaFrame.toMedia(p: Pt, mediaWidth: Int, mediaHeight: Int): Pt =
    Pt((p.x + 0.5f) * factor * mediaWidth / sourceWidth - 0.5f, (p.y + 0.5f) * factor * mediaHeight / sourceHeight - 0.5f)

internal suspend fun trackFrames(
    frames: List<Int>, pts: List<Long>, stamps: LongArray, start: List<Pt>, mediaWidth: Int, mediaHeight: Int,
    read: suspend (List<Long>, suspend (Long, LumaFrame) -> Unit) -> Unit,
    tracker: (List<Pt>) -> PointTracker,
    found: MutableMap<Long, List<Pt>> = HashMap(),
    progress: suspend (TrackStep, Float) -> Unit = { _, _ -> },
): TrackOutcome {
    val order = sourceOrder(pts, stamps)
    val firstFrame = HashMap<Long, Int>()
    frames.indices.forEach { firstFrame.putIfAbsent(pts[it], frames[it]) }
    var lostPoints = start
    var active: PointTracker? = null
    try {
        var done = 0
        run@ for (batch in order.chunked(BATCH)) {
            val lumas = HashMap<Long, LumaFrame>()
            read(batch.distinct().sorted()) { p, luma -> lumas[p] = luma }
            for (p in batch) {
                val luma = lumas[p] ?: break@run
                val current = active ?: tracker(start.map { luma.fromMedia(it, mediaWidth, mediaHeight) }).also { active = it }
                val result = current.track(luma)
                val points = result.points.map { luma.toMedia(it, mediaWidth, mediaHeight) }
                if (result.lost) { lostPoints = points; break@run }
                found[p] = points
                done++
                firstFrame[p]?.let { progress(TrackStep(it, points), done.toFloat() / order.size) }
            }
        }
    } finally {
        active?.close()
    }
    return trackedSteps(frames, pts, found, lostPoints)
}

internal fun trackedSteps(frames: List<Int>, pts: List<Long>, found: Map<Long, List<Pt>>, lostPoints: List<Pt> = emptyList()): TrackOutcome {
    val steps = ArrayList<TrackStep>()
    for (i in frames.indices) {
        val points = found[pts[i]] ?: return TrackOutcome(steps, frames[i], lostPoints)
        steps += TrackStep(frames[i], points)
    }
    return TrackOutcome(steps, null, lostPoints)
}

internal fun Scene.motionOf(layer: Layer, steps: List<TrackStep>, mediaWidth: Int, mediaHeight: Int, base: Sim): Map<Int, Sim> {
    val reference = steps.firstOrNull() ?: return emptyMap()
    val toScene = videoToScene(layer, reference.frame, mediaWidth, mediaHeight) ?: return emptyMap()
    val from = reference.points.map(toScene::map)
    return steps.mapNotNull { step ->
        val at = videoToScene(layer, step.frame, mediaWidth, mediaHeight) ?: return@mapNotNull null
        step.frame to Sim.between(from, step.points.map(at::map)) * base
    }.toMap()
}

internal fun simplifyKeys(times: FloatArray, values: List<FloatArray>, tolerance: Float): List<Int> {
    val n = times.size
    if (n <= 2) return (0 until n).toList()
    val keep = BooleanArray(n).also { it[0] = true; it[n - 1] = true }
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to n - 1) }
    while (stack.isNotEmpty()) {
        val (lo, hi) = stack.removeLast()
        var worst = -1
        var error = tolerance
        for (i in lo + 1 until hi) {
            val u = (times[i] - times[lo]) / (times[hi] - times[lo])
            for (c in values[i].indices) {
                val e = abs(values[i][c] - (values[lo][c] + (values[hi][c] - values[lo][c]) * u))
                if (e > error) { error = e; worst = i }
            }
        }
        if (worst > 0) {
            keep[worst] = true
            stack.addLast(lo to worst)
            stack.addLast(worst to hi)
        }
    }
    return (0 until n).filter { keep[it] }
}

internal fun unwrapDegrees(values: List<Double>): List<Double> {
    val out = ArrayList<Double>(values.size)
    for (v in values) {
        val previous = out.lastOrNull()
        out += if (previous == null) v else v + 360.0 * Math.round((previous - v) / 360.0)
    }
    return out
}

internal class TrackApply(val targetId: Long, val origin: Place, val rotateScale: Boolean)

internal const val LOCATION_TOLERANCE = 0.5f
internal const val ROTATION_TOLERANCE = 0.1f
internal const val SCALE_TOLERANCE = 0.001f

internal fun Scene.withTrack(apply: TrackApply, motion: Map<Int, Sim>): Scene {
    val target = layer(apply.targetId) ?: return this
    val length = (target.endTime - target.startTime).coerceAtLeast(1)
    val tracked = motion.keys.sorted().filter { frameStart(it) in target.startTime..target.endTime }
    if (tracked.isEmpty()) return this
    val parent = chain(target).getOrNull(1)
    val turns = unwrapDegrees(tracked.map { motion.getValue(it).degrees })
    val placed = tracked.mapIndexedNotNull { i, f ->
        val s = motion.getValue(f)
        val o = apply.origin
        val at = s.map(Pt(o.x, o.y))
        val k = if (apply.rotateScale) s.scale.toFloat() else 1f
        val world = Place(at.x, at.y, o.px, o.py, o.sx * k, o.sy * k, if (apply.rotateScale) o.rot + turns[i].toFloat() else o.rot, o.z)
        (if (parent == null) world else decompose(world, place(parent, frameStart(f))))?.let { f to it }
    }
    if (placed.isEmpty()) return this
    val frames = placed.map { it.first }
    val local = placed.map { it.second }
    val times = FloatArray(frames.size) { (frameStart(frames[it]) - target.startTime).toFloat() / length }
    val parts = buildList {
        add(Triple("location", LOCATION_TOLERANCE, local.map { it.part("location") }))
        if (apply.rotateScale) {
            add(Triple("rotation", ROTATION_TOLERANCE, local.map { it.part("rotation") }))
            add(Triple("scale", SCALE_TOLERANCE, local.map { it.part("scale") }))
        }
    }
    val snap = 250f / fps / length
    return update(target.id) { layer ->
        parts.fold(layer) { acc, (name, tolerance, values) ->
            val kept = simplifyKeys(times, values, tolerance)
            val transform = acc.node.child("transform") ?: XmlNode("transform")
            val element = transform.child(name) ?: XmlNode(name)
            val outside = element.keyframes.filter { it.t < times.first() - snap || it.t > times.last() + snap }
            val keys = outside + kept.map { Keyframe(times[it], formatFloats(values[it])) }
            acc.copy(node = acc.node.withChild("transform", transform.withChild(name, element.withKeyframes(keys))))
        }
    }
}

internal fun trackNull(id: Long, video: Layer, at: Pt, label: String): Layer = Layer(
    XmlNode("nullobj", linkedMapOf("id" to "$id", "label" to label, "startTime" to "${video.startTime}", "endTime" to "${video.endTime}"), listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(at.x, at.y, 0f)))))),
    )),
)

internal fun Scene.trackTargets(video: Layer): List<Layer> {
    val above = chain(video).map { it.id }.toSet()
    return layers.filter { it.id !in above && it.kind != "audio" && it.kind != "camera" }
}

internal class TrackRun(val frames: List<Int>, val points: List<Pt>, val base: Sim)

class TrackMode(layerId: Long) : DrawingMode(layerId, created = false) {
    val points = mutableStateListOf<Pt>()
    var range by mutableStateOf(TrackRange.Forward)
    var target by mutableStateOf<Long?>(null)
    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf("")
    var live by mutableStateOf<List<Pt>?>(null)
    var lostFrame by mutableStateOf<Int?>(null)
    internal var job: Job? = null
    internal val motion = HashMap<Int, Sim>()
    internal var apply: TrackApply? = null
    internal var pending: List<TrackRun> = emptyList()

    override val handlesCode: Int get() = -11
}

internal const val MAX_FILL = 30
internal const val BATCH = 24
