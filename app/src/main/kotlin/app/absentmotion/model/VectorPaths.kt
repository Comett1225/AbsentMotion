package app.absentmotion.model

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    val length: Float get() = hypot(x, y)
    fun distanceTo(o: Pt) = (this - o).length
}

data class Knot(val p: Pt, val inHandle: Pt? = null, val outHandle: Pt? = null) {
    fun movedBy(d: Pt) = Knot(p + d, inHandle?.plus(d), outHandle?.plus(d))
    val sharp: Boolean get() = inHandle == null && outHandle == null
}

data class Contour(val knots: List<Knot>, val closed: Boolean, val exclude: Boolean = false)

private const val FLATTEN_STEPS = 16

fun Contour.signedArea(): Double {
    val n = knots.size
    if (n < 2) return 0.0
    var sum = 0.0
    var prev = knots[0].p
    fun edge(to: Pt) {
        sum += prev.x.toDouble() * to.y - to.x.toDouble() * prev.y
        prev = to
    }
    for (i in 0 until if (closed) n else n - 1) {
        val a = knots[i]
        val b = knots[(i + 1) % n]
        if (a.outHandle == null && b.inHandle == null) {
            edge(b.p)
            continue
        }
        val c1 = a.outHandle ?: a.p
        val c2 = b.inHandle ?: b.p
        for (k in 1..FLATTEN_STEPS) {
            val t = k / FLATTEN_STEPS.toFloat()
            val u = 1 - t
            edge(a.p * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b.p * (t * t * t))
        }
    }
    edge(knots[0].p)
    return sum / 2
}

fun List<Contour>.withHoles(evenOdd: Boolean = false): List<Contour> {
    if (size < 2) return this
    val first = first().signedArea()
    return mapIndexed { i, c -> if (i == 0) c else c.copy(exclude = c.exclude || evenOdd || c.signedArea() * first < 0) }
}

val Layer.contours: List<Contour>? get() = node.child("path")?.let { shapeAt(it, t, exact = false) } ?: emptyList()

private fun XmlNode.sources(): List<XmlNode> = listOf(this) + children.filter { it.name == "contour" }

private fun shapeAt(path: XmlNode, t: Float, exact: Boolean): List<Contour> {
    val animated = path.isAnimatedPath
    val out = ArrayList<Contour>()
    for (source in path.sources()) {
        val knots = source.children.filter { it.name == "knot" }
        if (knots.isNotEmpty()) {
            out += Contour(
                knots.map { k ->
                    val p = k.vec("p", t, exact) ?: Pt(0f, 0f)
                    fun handle(name: String) = k.vec(name, t, exact)?.takeUnless { animated && it.x == 0f && it.y == 0f }?.plus(p)
                    Knot(p, handle("in"), handle("out"))
                },
                source["closed"] == "true", source["exclude"] == "true",
            )
        } else {
            source["d"]?.let { d ->
                val parts = parseSvgPath(d).withHoles(path["fillRule"] == "evenodd")
                out += if (source["exclude"] == "true") parts.map { it.copy(exclude = true) } else parts
            }
        }
    }
    return out
}

private fun XmlNode.hasKeys() = children.any { part -> part.children.any { it.name == "kf" } }

private val XmlNode.isAnimatedPath: Boolean get() = sources().any { s -> s.children.any { it.name == "knot" && it.hasKeys() } }

private fun XmlNode.vec(name: String, t: Float, exact: Boolean): Pt? {
    val part = child(name) ?: return null
    val text = if (exact) part.keyframes.firstOrNull { abs(it.t - t) <= KEY_EPSILON }?.v ?: part.valueAt(t) else part.valueAt(t)
    return text?.split(',')?.mapNotNull { it.trim().finiteFloat() }?.takeIf { it.size >= 2 }?.let { Pt(it[0], it[1]) }
}

private const val KEY_EPSILON = 1e-6f

data class ShapeKey(val t: Float, val contours: List<Contour>, val e: String? = null)

val Layer.isShapeAnimated: Boolean get() = node.child("path")?.isAnimatedPath == true

val Layer.shapeKeys: List<ShapeKey> get() {
    val path = node.child("path")?.takeIf { it.isAnimatedPath } ?: return emptyList()
    val keys = path.sources().flatMap { s -> s.children.filter { it.name == "knot" } }.flatMap { k -> k.children.flatMap { it.keyframes } }
    val times = keys.map { it.t }.sorted().fold(ArrayList<Float>()) { list, time -> list.also { if (it.isEmpty() || time - it.last() > KEY_EPSILON) it += time } }
    return times.map { time -> ShapeKey(time, shapeAt(path, time, exact = true), keys.firstOrNull { abs(it.t - time) <= KEY_EPSILON && it.e != null }?.e) }
}

fun Layer.withShapeKeys(keys: List<ShapeKey>): Layer {
    val sorted = keys.sortedBy { it.t }
    val first = sorted.firstOrNull()?.contours ?: return this
    val kept = first.indices.filter { first[it].knots.isNotEmpty() }
    if (kept.isEmpty()) return withContours(emptyList())
    fun animated(name: String, values: List<Pt>) = XmlNode(name).withKeyframes(sorted.mapIndexed { i, key ->
        Keyframe(key.t, formatFloats(floatArrayOf(values[i].x, values[i].y)), key.e?.takeIf { i > 0 })
    })
    fun contour(name: String, index: Int, attrs: LinkedHashMap<String, String>): XmlNode {
        val shape = first[index]
        attrs["closed"] = "${shape.closed}"
        if (shape.exclude) attrs["exclude"] = "true"
        return XmlNode(name, attrs, shape.knots.indices.map { i ->
            val knots = sorted.map { key -> key.contours.getOrNull(index)?.knots?.getOrNull(i) ?: shape.knots[i] }
            XmlNode("knot", children = listOfNotNull(
                animated("p", knots.map { it.p }),
                if (knots.any { it.inHandle != null }) animated("in", knots.map { (it.inHandle ?: it.p) - it.p }) else null,
                if (knots.any { it.outHandle != null }) animated("out", knots.map { (it.outHandle ?: it.p) - it.p }) else null,
            ))
        })
    }
    val path = if (kept.size == 1) contour("path", kept[0], linkedMapOf())
        else XmlNode("path", children = kept.map { contour("contour", it, linkedMapOf()) })
    return copy(node = node.withChild("path", path.withFillRule(node.child("path")?.get("fillRule"))))
}

fun Layer.shapeKeyIndex(): Int = shapeKeys.indexOfFirst { it.t in snap }

fun Layer.withContoursAt(contours: List<Contour>): Layer {
    val keys = shapeKeys
    if (keys.isEmpty()) return withContours(contours)
    val here = keys.indexOfFirst { it.t in snap }
    return withShapeKeys(if (here >= 0) keys.mapIndexed { i, k -> if (i == here) k.copy(contours = contours) else k } else keys + ShapeKey(t, contours))
}

fun Layer.withShapeKeyToggled(): Layer {
    val keys = shapeKeys
    val now = contours ?: return this
    if (keys.isEmpty()) return withShapeKeys(listOf(ShapeKey(t, now)))
    val here = keys.indexOfFirst { it.t in snap }
    return when {
        here < 0 -> withShapeKeys(keys + ShapeKey(t, now))
        keys.size == 1 -> withContours(keys[0].contours)
        else -> withShapeKeys(keys.filterIndexed { i, _ -> i != here })
    }
}

fun Layer.withEveryShape(change: (List<Contour>) -> List<Contour>): Layer {
    val keys = shapeKeys
    return if (keys.isEmpty()) withContours(change(contours ?: emptyList())) else withShapeKeys(keys.map { it.copy(contours = change(it.contours)) })
}

fun Layer.withContours(contours: List<Contour>): Layer {
    val real = contours.filter { it.knots.isNotEmpty() }
    fun part(name: String, c: Contour, attrs: LinkedHashMap<String, String>): XmlNode =
        if (c.knots.size == 1 || !c.closed && (c.knots.first().inHandle != null || c.knots.last().outHandle != null)) {
            XmlNode(name, attrs.also { it["closed"] = "false" }, c.knots.map { it.toNode() })
        } else XmlNode(name, attrs.also { it["d"] = c.toSvg() })
    val path = when {
        real.isEmpty() -> XmlNode("path", linkedMapOf("d" to ""))
        real.size == 1 -> part("path", real[0], linkedMapOf())
        else -> XmlNode("path", children = real.map { c -> part("contour", c, linkedMapOf<String, String>().also { if (c.exclude) it["exclude"] = "true" }) })
    }
    return copy(node = node.withChild("path", path.withFillRule(node.child("path")?.get("fillRule"))))
}

private fun XmlNode.withFillRule(rule: String?) = if (rule == null) this else with("fillRule", rule)

private fun Knot.toNode() = XmlNode("knot", children = listOfNotNull(vecNode("p", p), inHandle?.let { vecNode("in", it - p) }, outHandle?.let { vecNode("out", it - p) }))

private fun vecNode(name: String, v: Pt) = XmlNode(name, mapOf("value" to formatFloats(floatArrayOf(v.x, v.y))))

private fun num(v: Float): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').let { if (it == "-0" || it.isEmpty()) "0" else it }
private fun Pt.svg() = "${num(x)} ${num(y)}"

fun Contour.toSvg(): String {
    if (knots.isEmpty()) return ""
    val sb = StringBuilder("M ").append(knots[0].p.svg())
    val segments = if (closed) knots.size else knots.size - 1
    for (i in 0 until segments) {
        val a = knots[i]
        val b = knots[(i + 1) % knots.size]
        if (a.outHandle == null && b.inHandle == null) sb.append("L ").append(b.p.svg())
        else sb.append("C ").append((a.outHandle ?: a.p).svg()).append(", ").append((b.inHandle ?: b.p).svg()).append(", ").append(b.p.svg())
    }
    if (closed) sb.append("Z")
    return sb.toString()
}

fun parseSvgPath(d: String): List<Contour> {
    val tokens = Regex("[MLHVCSQTAZmlhvcsqtaz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toMutableList()
    val out = ArrayList<Contour>()
    var knots = ArrayList<Knot>()
    var i = 0
    var command = ' '
    var previous = ' '
    var control = Pt(0f, 0f)
    var pen = Pt(0f, 0f)
    var start = pen
    fun number(): Float = tokens.getOrNull(i++)?.finiteFloat() ?: 0f
    fun flag(): Boolean {
        val t = tokens.getOrNull(i) ?: return false
        if (t.length > 1 && (t[0] == '0' || t[0] == '1')) tokens[i] = t.substring(1) else i++
        return t[0] == '1'
    }
    fun point(from: Pt) = Pt(number(), number()) + from
    fun add(c1: Pt?, c2: Pt?, p: Pt) {
        if (knots.isEmpty()) knots.add(Knot(pen))
        val last = knots.last()
        if (c1 != null && c1 != last.p) knots[knots.lastIndex] = last.copy(outHandle = c1)
        knots.add(Knot(p, inHandle = c2?.takeIf { it != p }))
        pen = p
    }
    fun quad(q: Pt, p: Pt) = add(pen + (q - pen) * (2f / 3f), p + (q - p) * (2f / 3f), p)
    fun conic(c: Pt, p: Pt, w: Float) {
        if (w == 1f) return quad(c, p)
        val scale = 1 / (1 + w)
        val mid = (pen + c * (2 * w) + p) * (scale * 0.5f)
        val second = (c * w + p) * scale
        quad((pen + c * w) * scale, mid)
        quad(second, p)
    }
    fun finish(closed: Boolean) {
        if (knots.isEmpty()) return
        if (closed && knots.size > 2 && knots.last().p == knots.first().p && knots.last().outHandle == null) {
            val last = knots.removeAt(knots.lastIndex)
            if (knots[0].inHandle == null) knots[0] = knots[0].copy(inHandle = last.inHandle)
        }
        out += Contour(knots, closed)
        knots = ArrayList()
    }
    while (i < tokens.size) {
        val t = tokens[i]
        if (t[0].isLetter()) { command = t[0]; i++ } else if (command == ' ') { i++; continue }
        val from = if (command.isLowerCase()) pen else Pt(0f, 0f)
        val op = command.uppercaseChar()
        when (op) {
            'M' -> { finish(false); pen = point(from); start = pen; knots.add(Knot(pen)); command = if (command == 'm') 'l' else 'L' }
            'L' -> add(null, null, point(from))
            'H' -> add(null, null, Pt(number() + from.x, pen.y))
            'V' -> add(null, null, Pt(pen.x, number() + from.y))
            'C', 'S' -> {
                val c1 = if (op == 'C') point(from) else if (previous == 'C' || previous == 'S') pen * 2f - control else pen
                val c2 = point(from)
                add(c1, c2, point(from))
                control = c2
            }
            'Q', 'T' -> {
                val q = if (op == 'Q') point(from) else if (previous == 'Q' || previous == 'T') pen * 2f - control else pen
                quad(q, point(from))
                control = q
            }
            'A' -> {
                val rx = number(); val ry = number(); val angle = number()
                val large = flag(); val sweep = flag()
                arcConics(pen, rx, ry, angle, large, sweep, point(from))?.forEach { (c, p, w) -> if (w > 0f) conic(c, p, w) else add(null, null, p) }
            }
            'Z' -> { finish(true); pen = start; command = ' ' }
            else -> i++
        }
        previous = op
    }
    finish(false)
    return out
}

private fun arcConics(from: Pt, rx0: Float, ry0: Float, angle: Float, large: Boolean, sweep: Boolean, to: Pt): List<Triple<Pt, Pt, Float>>? {
    val line = listOf(Triple(to, to, 0f))
    if (rx0 == 0f || ry0 == 0f || from == to) return line
    fun snap(v: Float) = if (abs(v) <= 1f / 4096) 0f else v
    fun rotate(v: Pt, degrees: Float): Pt {
        val r = Math.toRadians(degrees.toDouble()).toFloat()
        val sin = snap(sin(r)); val cos = snap(cos(r))
        return Pt(cos * v.x - sin * v.y, sin * v.x + cos * v.y)
    }
    var rx = abs(rx0); var ry = abs(ry0)
    val half = rotate((from - to) * 0.5f, -angle)
    val radii = half.x * half.x / (rx * rx) + half.y * half.y / (ry * ry)
    if (radii > 1) { rx *= sqrt(radii); ry *= sqrt(radii) }
    fun toUnit(p: Pt) = rotate(p, -angle).let { Pt(it.x / rx, it.y / ry) }
    fun fromUnit(p: Pt) = rotate(Pt(p.x * rx, p.y * ry), angle)
    var u0 = toUnit(from); var u1 = toUnit(to)
    val delta = u1 - u0
    var factor = sqrt(max(1 / (delta.x * delta.x + delta.y * delta.y) - 0.25f, 0f))
    if (!sweep != large) factor = -factor
    val centre = (u0 + u1) * 0.5f + Pt(-delta.y * factor, delta.x * factor)
    u0 -= centre; u1 -= centre
    val theta1 = atan2(u0.y, u0.x)
    var arc = atan2(u1.y, u1.x) - theta1
    if (arc < 0 && sweep) arc += 2 * PI.toFloat() else if (arc > 0 && !sweep) arc -= 2 * PI.toFloat()
    if (abs(arc) < PI.toFloat() / 1_000_000) return line
    val segments = ceil(abs(arc / (2 * PI.toFloat() / 3))).toInt()
    val width = arc / segments
    val t = tan(0.5f * width)
    if (!t.isFinite()) return null
    val w = sqrt(0.5f + cos(width) * 0.5f)
    fun whole(v: Float) = v == floor(v)
    val integers = abs(PI.toFloat() / 2 - abs(width)) <= 1f / 4096 && whole(rx) && whole(ry) && whole(to.x) && whole(to.y)
    fun round(p: Pt) = if (integers) Pt(Math.round(p.x).toFloat(), Math.round(p.y).toFloat()) else p
    var end = theta1
    return (1..segments).map { k ->
        end += width
        val sin = snap(sin(end)); val cos = snap(cos(end))
        val onCircle = Pt(cos, sin) + centre
        Triple(round(fromUnit(onCircle + Pt(t * sin, -t * cos))), if (k == segments) to else round(fromUnit(onCircle)), w)
    }
}
