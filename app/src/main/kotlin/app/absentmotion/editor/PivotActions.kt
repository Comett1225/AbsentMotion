package app.absentmotion.editor

import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Easing
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.floats
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.steppedMs
import app.absentmotion.model.valueAt
import app.absentmotion.model.withEffect
import app.absentmotion.model.withKeyframes
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal const val PIVOT_LIMIT = 100_000f

private const val BAKE_LIMIT = 96
private const val BAKE_TOLERANCE = 1.0
private const val BAKE_REACH = 100f
private val MOTION_EFFECTS = setOf("absent.swing", "absent.spin", "absent.pulsesize")

private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

private fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> m[3 * i] * v[0] + m[3 * i + 1] * v[1] + m[3 * i + 2] * v[2] }

private fun turn(axis: Int, degrees: Double): DoubleArray {
    val r = Math.toRadians(degrees)
    val c = cos(r)
    val s = sin(r)
    return when (axis) {
        0 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
        1 -> doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
        else -> doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }
}

private class Stance(val rot: Double, val sx: Double, val sy: Double, val tilt: DoubleArray?, val link: DoubleArray?) {
    val turned: DoubleArray get() = tilt?.let { mul(turn(2, rot), it) } ?: turn(2, rot)

    fun undo(v: DoubleArray): DoubleArray? {
        if (sx == 0.0 || sy == 0.0) return null
        val back = DoubleArray(3) { i -> turned[i] * v[0] + turned[3 + i] * v[1] + turned[6 + i] * v[2] }
        return doubleArrayOf(back[0] / sx, back[1] / sy, back[2])
    }
}

private fun wave(cycles: Double, kind: Float): Double {
    val f = cycles - floor(cycles)
    if (kind >= 0.5f) return 1 - 4 * abs((f + 0.25) % 1.0 - 0.5)
    return sin(2 * PI * f)
}

private fun Layer.integral(effect: XmlNode, name: String, seconds: Double, fallback: Float): Double {
    val p = effect.effectProperty(name) ?: return fallback * seconds
    val length = (endTime - startTime) / 1000.0
    val keys = p.keyframes
    fun at(u: Float) = floats(p.valueAt(u), floatArrayOf(fallback))[0].toDouble()
    if (keys.isEmpty() || length <= 0) return at(0f) * seconds
    fun f(s: Double) = at((s / length).toFloat())
    val marks = mutableListOf(0.0, seconds)
    fun mark(s: Double) { if (s > 0 && s < seconds) marks += s }
    keys.forEachIndexed { k, key ->
        mark(key.t * length)
        val easing = Easing.parse(key.e)
        val step = easing.p.getOrNull(0)?.toDouble() ?: 0.0
        if (k == 0 || easing.type != "step" || !(step > 0)) return@forEachIndexed
        val from = keys[k - 1].t * length
        val span = key.t * length - from
        val rise = (easing.p.getOrNull(1) ?: 0f).toDouble().coerceIn(0.0, 1.0) * step
        val reversed = easing.prefixes.count { it == "reverse" } % 2 == 1
        var n = 1
        while (n <= 256 && n * step - rise < 1) {
            for (u in doubleArrayOf(n * step - rise, n * step)) if (u > 0 && u < 1) mark(from + span * (if (reversed) 1 - u else u))
            n++
        }
    }
    marks.sort()
    val inside = length * 1e-6
    var sum = 0.0
    for (i in 0 until marks.size - 1) {
        val a = marks[i]
        val h = (marks[i + 1] - a) / 8
        if (!(h > 0)) continue
        val margin = min(inside, h)
        var part = f(a + margin) + f(marks[i + 1] - margin)
        for (k in 1..7) part += (if (k % 2 == 1) 4 else 2) * f(a + k * h)
        sum += part * h / 3
    }
    return sum
}

private fun Layer.tOf(ms: Double) = ((ms - startTime) / (endTime - startTime).coerceAtLeast(1)).toFloat()

private fun Layer.msOf(t: Float) = startTime + t.toDouble() * (endTime - startTime)

private fun Layer.atMs(ms: Double) = copy(t = tOf(ms))

private val Layer.running: Boolean get() = effects.any { it["id"] in MOTION_EFFECTS && it["hidden"] != "true" }

private fun Layer.motionTimes(): List<Double> {
    val transform = node.child("transform")
    val animated = listOf("rotation", "scale", "skew", "tilt").mapNotNull { transform?.child(it) } +
        effects.filter { it["id"] in MOTION_EFFECTS && it["hidden"] != "true" }.flatMap { it.children }
    return animated.flatMap { it.keyframes }.map { msOf(it.t) }
}

private fun Layer.motion(ms: Double): Pair<Double, Double> {
    val stepped = steppedMs(ms)
    val t = if (endTime > startTime) ((stepped - startTime) / (endTime - startTime)).toFloat() else 0f
    val seconds = max(0.0, (stepped - startTime) / 1000)
    var rotation = 0.0
    var scale = 1.0
    for (e in effects) {
        if (e["hidden"] == "true") continue
        fun param(name: String, fallback: Float) = floats(e.effectProperty(name)?.valueAt(t), floatArrayOf(fallback))[0].toDouble()
        fun cycle() = wave(integral(e, "frequency", seconds, 2f) + param("phase", 0f) / 2, param("wave", 0f).toFloat())
        when (e["id"]) {
            "absent.swing" -> {
                val a1 = param("angle1", -30f)
                val a2 = param("angle2", 30f)
                rotation += (a1 + a2) / 2 + (a2 - a1) / 2 * cycle()
            }
            "absent.spin" -> rotation += (6 * integral(e, "rpm", seconds, 60f)) % 360.0
            "absent.pulsesize" -> {
                val lo = param("shrink", 0.9f)
                val hi = param("grow", 1.1f)
                scale *= max(0.0, (lo + hi) / 2 + (hi - lo) / 2 * cycle())
            }
        }
    }
    return rotation to scale
}

private fun Scene.stance(layer: Layer, ms: Double, depth: Int = 0): Stance {
    val at = layer.atMs(ms)
    val (spin, pulse) = layer.motion(ms)
    val s = at.scale
    val tilt = at.tilt
    val rot = at.transform("rotation", 0f)[0] + spin
    val own = if (tilt[0] != 0f || tilt[1] != 0f) mul(turn(1, -tilt[1].toDouble()), turn(0, -tilt[0].toDouble())) else null
    val parent = chain(layer).getOrNull(1)?.takeIf { depth < 16 }?.let { stance(it, ms, depth + 1) }
        ?: return Stance(rot, s[0] * pulse, s[1] * pulse, own, null)
    val tilted = parent.tilt?.let { mul(mul(mul(turn(2, -rot), it), turn(2, rot)), own ?: IDENTITY) } ?: own
    val link = mul(parent.turned, doubleArrayOf(parent.sx, 0.0, 0.0, 0.0, parent.sy, 0.0, 0.0, 0.0, 1.0))
    return Stance(rot + parent.rot, s[0] * pulse * parent.sx, s[1] * pulse * parent.sy, tilted, link)
}

private fun Stance.mapped(layer: Layer, dx: Float, dy: Float): DoubleArray {
    val skew = if (layer.kind == "model") floatArrayOf(0f, 0f) else layer.transform("skew", 0f, 0f)
    val x = dx * sx
    val y = dy * sy
    return apply(turned, doubleArrayOf(x + tan(skew[0].toDouble()) * y, tan(skew[1].toDouble()) * x + y, 0.0))
}

private fun Stance.solve(v: DoubleArray): DoubleArray? {
    val m = link ?: return v
    val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    if (abs(det) < 1e-12) return null
    fun col(i: Int) = doubleArrayOf(m[i], m[3 + i], m[6 + i])
    fun det3(a: DoubleArray, b: DoubleArray, c: DoubleArray) =
        a[0] * (b[1] * c[2] - c[1] * b[2]) - b[0] * (a[1] * c[2] - c[1] * a[2]) + c[0] * (a[1] * b[2] - b[1] * a[2])
    return doubleArrayOf(det3(v, col(1), col(2)) / det, det3(col(0), v, col(2)) / det, det3(col(0), col(1), v) / det)
}

private fun bezier(a: Double, b: Double, s: Double) = 3 * (1 - s) * (1 - s) * s * a + 3 * (1 - s) * s * s * b + s * s * s

private fun Easing.bezierAt(u: Double): Double {
    val x1 = p[0].toDouble().coerceIn(0.0, 1.0)
    val x2 = p[2].toDouble().coerceIn(0.0, 1.0)
    var lo = 0.0
    var hi = 1.0
    repeat(60) { val s = (lo + hi) / 2; if (bezier(x1, x2, s) < u) lo = s else hi = s }
    return (lo + hi) / 2
}

private val Easing.plain: Boolean get() = prefixes.isEmpty() && (type == "linear" || type == "cubicBezier")

private fun ease(text: String?, u: Double): Double {
    if (text.isNullOrEmpty()) return u
    val e = Easing.parse(text)
    if (!e.plain) return NativeEngine.ease(text, u.toFloat()).toDouble()
    if (e.type == "linear") return u
    return bezier(e.p[1].toDouble(), e.p[3].toDouble(), e.bezierAt(u))
}

private fun part(text: String?, u0: Double, u1: Double): Easing? {
    val e = Easing.parse(text)
    if (e.prefixes.isEmpty() && e.type == "linear") return Easing.Linear
    if (u0 <= 0 && u1 >= 1) return e
    if (!e.plain) return null
    val xs = doubleArrayOf(0.0, e.p[0].toDouble().coerceIn(0.0, 1.0), e.p[2].toDouble().coerceIn(0.0, 1.0), 1.0)
    val ys = doubleArrayOf(0.0, e.p[1].toDouble(), e.p[3].toDouble(), 1.0)
    val s0 = if (u0 <= 0) 0.0 else e.bezierAt(u0)
    val s1 = if (u1 >= 1) 1.0 else e.bezierAt(u1)
    fun blossom(c: DoubleArray, a: Double, b: Double, d: Double): Double {
        val one = DoubleArray(3) { c[it] + (c[it + 1] - c[it]) * a }
        val two = DoubleArray(2) { one[it] + (one[it + 1] - one[it]) * b }
        return two[0] + (two[1] - two[0]) * d
    }
    fun points(c: DoubleArray) = doubleArrayOf(blossom(c, s0, s0, s0), blossom(c, s0, s0, s1), blossom(c, s0, s1, s1), blossom(c, s1, s1, s1))
    val x = points(xs)
    val y = points(ys)
    val w = x[3] - x[0]
    val h = y[3] - y[0]
    if (abs(w) < 1e-12 || abs(h) < 1e-9) return Easing.Linear
    return Easing("cubicBezier", listOf(((x[1] - x[0]) / w).coerceIn(0.0, 1.0), (y[1] - y[0]) / h, ((x[2] - x[0]) / w).coerceIn(0.0, 1.0), (y[2] - y[0]) / h).map { it.toFloat() })
}

private val Easing.text: String? get() = takeUnless { it.isLinear }?.toString()

private class Hat(val keys: List<Keyframe>, val j: Int) {
    val marks: List<Float> = listOfNotNull(keys.getOrNull(j - 1)?.t, keys[j].t, keys.getOrNull(j + 1)?.t)

    fun at(t: Float): Double {
        val k = keys[j].t
        if (t <= k) {
            if (j == 0) return 1.0
            val a = keys[j - 1].t
            return if (t <= a) 0.0 else ease(keys[j].e, ((t - a) / (k - a)).toDouble())
        }
        if (j == keys.lastIndex) return 1.0
        val b = keys[j + 1].t
        return if (t >= b) 0.0 else 1 - ease(keys[j + 1].e, ((t - k) / (b - k)).toDouble())
    }

    fun piece(a: Float, b: Float): Easing? {
        val k = keys[j].t
        val (from, to, e) = when {
            j > 0 && a >= keys[j - 1].t && b <= k -> Triple(keys[j - 1].t, k, keys[j].e)
            j < keys.lastIndex && a >= k && b <= keys[j + 1].t -> Triple(k, keys[j + 1].t, keys[j + 1].e)
            else -> return null
        }
        return part(e, ((a - from) / (to - from)).toDouble(), ((b - from) / (to - from)).toDouble())
    }
}

private fun FloatArray.plus(by: DoubleArray, k: Double) = FloatArray(size) { i -> (this[i] + (by.getOrNull(i) ?: 0.0) * k).toFloat() }

private fun List<Keyframe>.split(t: Float, zeros: FloatArray): List<Keyframe> {
    if (any { abs(it.t - t) < 1e-6f }) return this
    val next = indexOfFirst { it.t > t }
    if (next < 0) return this + Keyframe(t, last().v)
    if (next == 0) return listOf(Keyframe(t, first().v)) + this
    val a = this[next - 1]
    val b = this[next]
    val u = ((t - a.t) / (b.t - a.t)).toDouble()
    val from = floats(a.v, zeros)
    val to = floats(b.v, zeros)
    val k = ease(b.e, u)
    val value = FloatArray(max(from.size, to.size)) { i -> (from.getOrElse(i) { 0f } + (to.getOrElse(i) { 0f } - from.getOrElse(i) { 0f }) * k).toFloat() }
    val left = part(b.e, 0.0, u) ?: Easing.parse(b.e)
    val right = part(b.e, u, 1.0) ?: Easing.parse(b.e)
    return toMutableList().apply {
        set(next, b.copy(e = right.text))
        add(next, Keyframe(t, formatFloats(value), left.text))
    }
}

private fun Hat.into(from: Layer, to: Layer): Hat =
    if (from.startTime == to.startTime && from.endTime == to.endTime) this else Hat(keys.map { it.copy(t = to.tOf(from.msOf(it.t))) }, j)

private fun XmlNode.withShift(by: (Float) -> DoubleArray, extra: List<Float>, hat: Hat?, zeros: FloatArray): XmlNode {
    val live = hat?.takeIf { it.keys.size > 1 }
    if (live == null && extra.isEmpty()) {
        val shift = by(0f)
        if (shift.all { it == 0.0 }) return this
        fun moved(text: String?) = formatFloats(floats(text, zeros).plus(shift, 1.0))
        return if (isKeyframed) withKeyframes(keyframes.map { it.copy(v = moved(it.v)) }) else with("value", moved(this["value"]))
    }
    val marks = (live?.marks?.map { it.coerceIn(0f, 1f) } ?: emptyList()) + extra
    var keys = if (isKeyframed) keyframes else listOf(Keyframe(marks.first(), this["value"] ?: formatFloats(zeros)))
    for (t in marks) keys = keys.split(t, zeros)
    val flat = keys.zipWithNext { a, b -> floats(a.v, zeros).contentEquals(floats(b.v, zeros)) }
    val out = keys.mapIndexed { i, key ->
        val e = if (live != null && i > 0 && flat[i - 1]) live.piece(keys[i - 1].t, key.t)?.text ?: key.e else key.e
        key.copy(v = formatFloats(floats(key.v, zeros).plus(by(key.t), live?.at(key.t) ?: 1.0)), e = e)
    }
    return withKeyframes(out)
}

private fun Layer.followsPath(scene: Scene): Boolean = effects.any { it["id"] == "absent.movealongpath" && it["hidden"] != "true" } &&
    (node["pathLayer"]?.toLongOrNull()?.let { other -> other != id && scene.layer(other) != null } == true || scene.layers.indexOfFirst { it.id == id } > 0)

private class Pull(val own: DoubleArray, val child: DoubleArray, val world: DoubleArray) {
    val all: DoubleArray get() = own + child + world

    fun watched(kids: Boolean, path: Boolean): DoubleArray = own + (if (kids) child else DoubleArray(0)) + (if (path) world.copyOf(2) else DoubleArray(0))
}

private class Plan(val anchors: List<Double>, val at: (Double) -> Pull)

private fun Layer.withLocationShift(scene: Scene, plan: Plan, hat: Hat?, pick: (Pull) -> DoubleArray, onPath: Boolean): Layer {
    val transform = node.child("transform") ?: XmlNode("transform")
    val marks = plan.anchors.map { tOf(it).coerceIn(0f, 1f) }
    val location = (transform.child("location") ?: XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(0f, 0f, 0f)))))
        .withShift({ t -> pick(plan.at(msOf(t))) }, marks, hat, floatArrayOf(0f, 0f, 0f))
    var out = copy(node = node.withChild("transform", transform.withChild("location", location)))
    if (onPath && followsPath(scene)) {
        val index = effects.indexOfFirst { it["id"] == "absent.movealongpath" && it["hidden"] != "true" }
        out = out.withEffect(index) { effect ->
            val old = effect.effectProperty("offset")
            val offset = (old ?: XmlNode("property", mapOf("name" to "offset", "type" to "vec2", "value" to "0.000000,0.000000")))
                .withShift({ t -> plan.at(msOf(t)).world.copyOf(2) }, marks, hat, floatArrayOf(0f, 0f))
            effect.copy(children = if (old == null) effect.children + offset else effect.children.map { if (it === old) offset else it })
        }
    }
    return out
}

private class PivotEdit(val layer: Layer, val hat: Hat?, val dx: Float, val dy: Float)

private fun Layer.withPivotKey(x: Float, y: Float): PivotEdit? {
    val transform = node.child("transform") ?: XmlNode("transform")
    val pivot = transform.child("pivot")
    if (pivot?.isKeyframed != true) {
        val old = this.pivot
        if (old[0] == x && old[1] == y) return null
        return PivotEdit(withValue(PropRef.Pivot, formatFloats(floatArrayOf(x, y, old[2]))), null, x - old[0], y - old[1])
    }
    val zeros = floatArrayOf(0f, 0f, 0f)
    var keys = pivot.keyframes
    var j = keys.indexOfFirst { it.t in snap }
    if (j < 0) {
        keys = keys.split(t, zeros)
        j = keys.indexOfFirst { abs(it.t - t) < 1e-6f }
    }
    val old = floats(keys[j].v, zeros)
    if (old[0] == x && old[1] == y && keys.size == pivot.keyframes.size) return null
    keys = keys.toMutableList().also { it[j] = it[j].copy(v = formatFloats(floatArrayOf(x, y, old[2]))) }
    return PivotEdit(copy(node = node.withChild("transform", transform.withChild("pivot", pivot.withKeyframes(keys)))), Hat(keys, j), x - old[0], y - old[1])
}

private fun Scene.pull(layer: Layer, ms: Double, dx: Float, dy: Float): Pull {
    val stance = stance(layer, ms)
    val d = doubleArrayOf(dx.toDouble(), dy.toDouble(), 0.0)
    val world = stance.mapped(layer.atMs(ms), dx, dy)
    val reach = DoubleArray(3) { world[it] + d[it] }
    val own = stance.solve(reach)?.let { v -> DoubleArray(3) { v[it] - d[it] } } ?: DoubleArray(3)
    val child = stance.undo(reach)?.let { v -> DoubleArray(3) { d[it] - v[it] } } ?: DoubleArray(3)
    return Pull(own, child, world)
}

private fun bake(times: List<Double>, probe: (Double) -> DoubleArray): List<Double> {
    val xs = times.distinct().sorted().toMutableList()
    if (xs.size < 2) return emptyList()
    val seen = HashMap<Double, DoubleArray>()
    fun at(x: Double) = seen.getOrPut(x) { probe(x) }
    fun miss(a: Double, b: Double): Double {
        val from = at(a)
        val to = at(b)
        val worst = doubleArrayOf(0.17, 0.5, 0.83).maxOf { u ->
            val p = at(a + (b - a) * u)
            p.indices.maxOf { abs(p[it] - (from[it] + (to[it] - from[it]) * u)) }
        }
        return if (worst.isNaN()) 0.0 else worst
    }
    val queue = PriorityQueue<Triple<Double, Double, Double>>(11, compareByDescending { it.first })
    xs.zipWithNext { a, b -> queue.add(Triple(miss(a, b), a, b)) }
    if (queue.all { it.first < 1e-9 } && xs.all { x -> at(x).indices.all { abs(at(x)[it] - at(xs[0])[it]) < 1e-9 } }) return emptyList()
    var added = 0
    while (added < BAKE_LIMIT) {
        val (error, a, b) = queue.poll() ?: break
        if (error <= BAKE_TOLERANCE) break
        val m = (a + b) / 2
        xs.add(m)
        added++
        queue.add(Triple(miss(a, m), a, m))
        queue.add(Triple(miss(m, b), m, b))
    }
    return xs.distinct().sorted()
}

private fun Scene.planFor(layer: Layer, kids: List<Layer>, edit: PivotEdit, seenMs: Int): Plan? {
    val chain = chain(layer)
    val span = listOf(layer) + kids
    val lo = span.minOf { it.startTime }.toDouble()
    val hi = max(lo, span.maxOf { it.endTime }.toDouble())
    val times = chain.flatMap { it.motionTimes() } + if (chain.any { it.running }) listOf(lo, hi) else emptyList()
    val path = layer.followsPath(this)
    val anchors = bake(times.map { it.coerceIn(lo, hi) }) { ms ->
        pull(layer, ms, BAKE_REACH, 0f).watched(kids.isNotEmpty(), path) + pull(layer, ms, 0f, BAKE_REACH).watched(kids.isNotEmpty(), path)
    }
    val steady = if (anchors.isEmpty()) pull(layer, seenMs.toDouble(), edit.dx, edit.dy) else null
    val memo = HashMap<Double, Pull>()
    val raw = { ms: Double -> steady ?: memo.getOrPut(ms) { pull(layer, ms, edit.dx, edit.dy) } }
    if ((if (steady != null) listOf(steady) else anchors.map(raw)).any { p -> p.all.any { !it.isFinite() } }) return null
    return Plan(anchors) { ms -> raw(ms).let { Pull(it.own.finite(), it.child.finite(), it.world.finite()) } }
}

private fun DoubleArray.finite() = DoubleArray(size) { if (this[it].isFinite()) this[it] else 0.0 }

internal fun Scene.withPivotMoved(id: Long, timeMs: Int, x: Float, y: Float, seenMs: Int = timeMs): Scene {
    val layer = layer(id) ?: return this
    if (!x.isFinite() || !y.isFinite()) return this
    val edit = layer.at(timeMs, fps).withPivotKey(x.coerceIn(-PIVOT_LIMIT, PIVOT_LIMIT), y.coerceIn(-PIVOT_LIMIT, PIVOT_LIMIT)) ?: return this
    if (edit.dx == 0f && edit.dy == 0f) return update(id) { Layer(edit.layer.node) }
    val kids = layers.filter { it.id != id && it.node["parent"]?.toLongOrNull() == id }
    val plan = planFor(layer, kids, edit, seenMs) ?: return this
    var next = update(id) { Layer(edit.layer.withLocationShift(this, plan, edit.hat, Pull::own, true).node) }
    for (c in kids) next = next.update(c.id) { Layer(it.withLocationShift(this, plan, edit.hat?.into(layer, it), Pull::child, false).node) }
    return next
}

fun EditorState.setPivot(x: Float, y: Float, key: Any? = null) {
    val id = selectedId ?: return
    if (scene.layer(id)?.kind == "camera") return
    edit(key) { it.withPivotMoved(id, sampleMs, x, y, timeMs) }
}

fun EditorState.resetPivot() = setPivot(0f, 0f)
