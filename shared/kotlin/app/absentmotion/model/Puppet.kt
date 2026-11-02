package app.absentmotion.model

import kotlin.math.abs
import kotlin.math.pow

data class PuppetPin(val number: Int, val at: Pt, val rest: Pt, val mode: Int = 0, val rotation: Float = 0f, val scale: Float = 1f) {
    val name: String get() = "pin$number"
    val restName: String get() = "rest$number"
}

const val MAX_PINS = 32
private const val MERGE_DISTANCE = 0.5
private val SEPARATORS = Regex("[,\\s]+")

private fun pinNumber(name: String?): Int? =
    name?.takeIf { it.startsWith("pin") }?.drop(3)?.takeIf { d -> d.length in 1..9 && d[0] != '0' && d.all { it in '0'..'9' } }?.toInt()

private fun point(text: String?): Pt? {
    val parts = text?.trim()?.split(SEPARATORS)?.map { it.finiteFloat() } ?: return null
    val x = parts.getOrNull(0) ?: return null
    val y = parts.getOrNull(1) ?: return null
    return Pt(x, y)
}

fun XmlNode.allPuppetPins(t: Float): List<PuppetPin> = children.mapNotNull { p ->
    if (p.name != "property") return@mapNotNull null
    val number = pinNumber(p["name"]) ?: return@mapNotNull null
    val rest = effectProperty("rest$number") ?: return@mapNotNull null
    val at = point(p.valueAt(t)) ?: return@mapNotNull null
    PuppetPin(number, at, point(rest.valueAt(t)) ?: return@mapNotNull null,
        kotlin.math.round(puppetNumber("pinMode$number", t, 0f, 0f, 2f)).toInt(), puppetNumber("pinRotation$number", t, 0f, -3600f, 3600f),
        puppetNumber("pinScale$number", t, 1f, 0.05f, 10f))
}

fun XmlNode.puppetPins(t: Float): List<PuppetPin> = allPuppetPins(t).take(MAX_PINS)

fun XmlNode.withPuppetPin(at: Pt, rest: Pt): XmlNode {
    val used = children.mapNotNull { pinNumber(it["name"]) }.toSet()
    val number = generateSequence(1) { it + 1 }.first { it !in used && effectProperty("rest$it") == null }
    fun value(p: Pt) = formatFloats(floatArrayOf(p.x, p.y))
    return copy(children = children +
        XmlNode("property", linkedMapOf("name" to "rest$number", "type" to "vec2", "value" to value(rest))) +
        XmlNode("property", linkedMapOf("name" to "pin$number", "type" to "vec2", "value" to value(at))))
}

fun XmlNode.withPuppetPinAt(at: Pt, t: Float): XmlNode? =
    if (allPuppetPins(t).size >= MAX_PINS) null else withPuppetPin(at, PuppetBend(puppetPins(t), puppetFalloff(t)).source(at))

fun XmlNode.withoutPuppetPin(number: Int): XmlNode =
    copy(children = children.filterNot { it.name == "property" && it["name"] in setOf("pin$number", "rest$number", "swayPin$number", "pinMode$number", "pinRotation$number", "pinScale$number") })

fun XmlNode.puppetNumber(name: String, t: Float, fallback: Float, low: Float, high: Float): Float {
    val value = effectProperty(name)?.valueAt(t)
    return (when (value) { "true" -> 1f; "false" -> 0f; else -> value?.finiteFloat() } ?: fallback).coerceIn(low, high)
}

fun XmlNode.puppetOffset(pin: PuppetPin, t: Float, timeMs: Int): Pt {
    if (pin.mode == 2 || puppetNumber("sway", t, 0f, 0f, 1f) < 0.5f) return Pt(0f, 0f)
    if (puppetNumber("swayPins", t, 0f, 0f, 1f) >= 0.5f && puppetNumber("swayPin${pin.number}", t, 0f, 0f, 1f) < 0.5f) return Pt(0f, 0f)
    val amount = puppetNumber("swayAmount", t, 15f, 0f, 500f)
    val speed = puppetNumber("swaySpeed", t, 1f, 0f, 10f)
    val seed = puppetNumber("swaySeed", t, 0f, 0f, 1000f)
    val phase = (timeMs * 0.001 * speed % 100000.0) * 6.283185307179586 + pin.number * 2.3999632297 + seed
    val angle = if (puppetNumber("swayDirection", t, 0f, 0f, 1f) >= 0.5f) pin.number * 2.3999632297 + seed * 0.6180339887
        else (puppetNumber("swayAngle", t, 0f, -3600f, 3600f) * 0.01745329252f).toDouble()
    val d = amount * kotlin.math.sin(phase).toFloat()
    return Pt(d * kotlin.math.cos(angle).toFloat(), d * kotlin.math.sin(angle).toFloat())
}

fun XmlNode.renderPuppetPins(t: Float, timeMs: Int): List<PuppetPin> = puppetPins(t).map { p ->
    p.copy(at = if (p.mode == 2) p.rest else p.at + puppetOffset(p, t, timeMs))
}

fun puppetMeshSource(pins: List<PuppetPin>, falloff: Float, at: Pt): Pt {
    if (pins.isEmpty()) return at
    val power = falloff.coerceIn(0.1f, 4f) * 0.5 + 0.75
    var sx = 0.0; var sy = 0.0; var sum = 0.0
    for (p in pins) {
        val dx = (at.x - p.at.x).toDouble(); val dy = (at.y - p.at.y).toDouble(); val r = dx * dx + dy * dy
        if (r < 1e-12) return p.rest
        var angle = if (p.mode == 1) -p.rotation * 0.01745329252 else 0.0
        if (p.mode == 0 && p.at.distanceTo(p.rest) >= 1e-4f) {
            val q = pins.filter { it !== p && it.rest.distanceTo(p.rest) > 0.01f && it.at.distanceTo(p.at) > 0.01f }.minByOrNull { it.rest.distanceTo(p.rest) }
            if (q != null) angle = kotlin.math.atan2((q.rest.y - p.rest.y).toDouble(), (q.rest.x - p.rest.x).toDouble()) -
                kotlin.math.atan2((q.at.y - p.at.y).toDouble(), (q.at.x - p.at.x).toDouble())
        }
        val w = 1 / r.pow(power); val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        val scale = if (p.mode == 1) 1.0 / p.scale else 1.0
        sx += w * (p.rest.x + scale * (c * dx - s * dy)); sy += w * (p.rest.y + scale * (s * dx + c * dy)); sum += w
    }
    return Pt((sx / sum).toFloat(), (sy / sum).toFloat())
}

fun XmlNode.puppetFalloff(t: Float): Float = (effectProperty("falloff")?.valueAt(t)?.finiteFloat() ?: 1f).coerceIn(0.1f, 4f)

fun puppetPower(falloff: Float): Double = 0.5 + 0.5 / (1 + (if (falloff.isFinite()) falloff.toDouble() else 1.0).coerceIn(0.1, 4.0))

class PuppetBend(pins: List<PuppetPin>, falloff: Float) {
    private val power = puppetPower(falloff)
    private var centerX = 0.0
    private var centerY = 0.0
    private var ar = 1.0
    private var ai = 0.0
    private var tx = 0.0
    private var ty = 0.0
    private var x = DoubleArray(0)
    private var y = DoubleArray(0)
    private var cx = DoubleArray(0)
    private var cy = DoubleArray(0)

    init {
        class Group(val firstX: Double, val firstY: Double) {
            var x = 0.0; var y = 0.0; var restX = 0.0; var restY = 0.0; var count = 0
        }
        val groups = ArrayList<Group>()
        for (p in pins.take(MAX_PINS)) {
            val px = p.at.x.toDouble()
            val py = p.at.y.toDouble()
            val g = groups.firstOrNull { (px - it.firstX).let { dx -> (py - it.firstY).let { dy -> dx * dx + dy * dy } } < MERGE_DISTANCE * MERGE_DISTANCE }
                ?: Group(px, py).also { groups += it }
            g.x += px; g.y += py; g.restX += p.rest.x.toDouble(); g.restY += p.rest.y.toDouble(); g.count++
        }
        val n = groups.size
        if (n > 0) {
            for (g in groups) { centerX += g.x / g.count / n; centerY += g.y / g.count / n }
            val px = DoubleArray(n) { groups[it].x / groups[it].count - centerX }
            val py = DoubleArray(n) { groups[it].y / groups[it].count - centerY }
            val qx = DoubleArray(n) { groups[it].restX / groups[it].count - centerX }
            val qy = DoubleArray(n) { groups[it].restY / groups[it].count - centerY }
            for (i in 0 until n) { tx += (qx[i] - px[i]) / n; ty += (qy[i] - py[i]) / n }
            if (n > 1) solve(n, px, py, qx, qy)
        }
    }

    private fun solve(n: Int, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        val m = 2 * n + 4
        val a = DoubleArray(m * m)
        val b = DoubleArray(m)
        for (i in 0 until n) {
            for (j in 0 until n) {
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val k = if (i == j) 0.0 else (dx * dx + dy * dy).pow(power)
                a[2 * i * m + 2 * j] = k
                a[(2 * i + 1) * m + 2 * j + 1] = k
            }
            val rowX = doubleArrayOf(px[i], -py[i], 1.0, 0.0)
            val rowY = doubleArrayOf(py[i], px[i], 0.0, 1.0)
            for (k in 0 until 4) {
                a[2 * i * m + 2 * n + k] = rowX[k]; a[(2 * n + k) * m + 2 * i] = rowX[k]
                a[(2 * i + 1) * m + 2 * n + k] = rowY[k]; a[(2 * n + k) * m + 2 * i + 1] = rowY[k]
            }
            b[2 * i] = qx[i]
            b[2 * i + 1] = qy[i]
        }
        if (!solveLinear(a, b, m)) return
        x = px; y = py
        cx = DoubleArray(n) { b[2 * it] }
        cy = DoubleArray(n) { b[2 * it + 1] }
        ar = b[2 * n]; ai = b[2 * n + 1]; tx = b[2 * n + 2]; ty = b[2 * n + 3]
    }

    fun source(at: Pt): Pt {
        val vx = at.x - centerX
        val vy = at.y - centerY
        var sx = ar * vx - ai * vy + tx
        var sy = ai * vx + ar * vy + ty
        for (i in cx.indices) {
            val dx = x[i] - vx
            val dy = y[i] - vy
            val r = (dx * dx + dy * dy).pow(power)
            sx += cx[i] * r
            sy += cy[i] * r
        }
        return Pt((sx + centerX).toFloat(), (sy + centerY).toFloat())
    }
}

private fun solveLinear(a: DoubleArray, b: DoubleArray, n: Int): Boolean {
    for (c in 0 until n) {
        var best = c
        for (r in c + 1 until n) if (abs(a[r * n + c]) > abs(a[best * n + c])) best = r
        if (!(abs(a[best * n + c]) > 1e-300)) return false
        if (best != c) {
            for (k in 0 until n) a[c * n + k] = a[best * n + k].also { a[best * n + k] = a[c * n + k] }
            b[c] = b[best].also { b[best] = b[c] }
        }
        for (r in c + 1 until n) {
            val f = a[r * n + c] / a[c * n + c]
            if (f == 0.0) continue
            for (k in c until n) a[r * n + k] -= f * a[c * n + k]
            b[r] -= f * b[c]
        }
    }
    for (c in n - 1 downTo 0) {
        var s = b[c]
        for (k in c + 1 until n) s -= a[c * n + k] * b[k]
        b[c] = s / a[c * n + c]
        if (!b[c].isFinite()) return false
    }
    return true
}

fun unwarp(pins: List<PuppetPin>, falloff: Float, at: Pt): Pt = PuppetBend(pins, falloff).source(at)
