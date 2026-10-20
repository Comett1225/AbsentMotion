package app.absentmotion.model

import app.absentmotion.engine.NativeEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class Keyframe(val t: Float, val v: String, val e: String? = null)

data class PropRef(val kind: Kind, val name: String, val effectIndex: Int = -1) {
    enum class Kind { Transform, Child, Property, Effect }

    companion object {
        val Location = PropRef(Kind.Transform, "location")
        val Scale = PropRef(Kind.Transform, "scale")
        val Rotation = PropRef(Kind.Transform, "rotation")
        val Skew = PropRef(Kind.Transform, "skew")
        val Tilt = PropRef(Kind.Transform, "tilt")
        val Opacity = PropRef(Kind.Transform, "opacity")
        val FillColor = PropRef(Kind.Child, "fillColor")
        fun effect(index: Int, name: String) = PropRef(Kind.Effect, name, index)
        fun shape(name: String) = PropRef(Kind.Property, name)
    }
}

data class Easing(val type: String, val p: List<Float> = emptyList(), val prefixes: List<String> = emptyList()) {
    override fun toString(): String = (prefixes + type + p.map { it.toString() }).joinToString(" ")

    fun at(u: Float): Float = NativeEngine.ease(toString(), u)

    fun with(index: Int, value: Float) = copy(p = p.toMutableList().also { it[index] = value })

    val isLinear: Boolean get() = type == "linear" || (type == "cubicBezier" && p == listOf(0f, 0f, 1f, 1f))

    companion object {
        val Linear = Easing("linear")

        fun parse(text: String?): Easing {
            val parts = text?.trim()?.split(Regex("\\s+")) ?: return Linear
            val prefixes = parts.takeWhile { it == "local" || it == "reverse" }
            val type = parts.getOrNull(prefixes.size) ?: return Linear
            val count = when (type) {
                "linear" -> 0
                "cubicBezier", "elastic" -> 4
                "cyclic" -> 5
                "random" -> if (parts.size - prefixes.size - 1 < 6) 3 else 6
                "bounce", "step", "elasticStep" -> 2
                else -> return Linear
            }
            val values = parts.drop(prefixes.size + 1).map { it.toFloatOrNull() ?: return Linear }
            if (values.size < count) return Linear
            return Easing(type, values.take(count), prefixes)
        }
    }
}

val XmlNode.keyframes: List<Keyframe>
    get() = children.filter { it.name == "kf" }.map { Keyframe(it["t"]?.toFloatOrNull() ?: 0f, it["v"] ?: "", it["e"]) }.sortedBy { it.t }

val XmlNode.isKeyframed: Boolean get() = children.any { it.name == "kf" }

fun XmlNode.withKeyframes(keys: List<Keyframe>): XmlNode = copy(
    attrs = attrs - "value",
    children = children.filterNot { it.name == "kf" } + keys.sortedBy { it.t }.map { k ->
        val attrs = linkedMapOf("t" to String.format(Locale.US, "%.6f", k.t), "v" to k.v)
        if (k.e != null) attrs["e"] = k.e
        XmlNode("kf", attrs)
    },
)

fun XmlNode.valueAt(t: Float): String? {
    this["value"]?.let { return it }
    val keys = keyframes
    if (keys.isEmpty()) return null
    if (t <= keys.first().t) return keys.first().v
    if (t >= keys.last().t) return keys.last().v
    val next = keys.indexOfFirst { it.t > t }
    val a = keys[next - 1]
    val b = keys[next]
    val u = (t - a.t) / (b.t - a.t)
    return blend(a.v, b.v, if (b.e.isNullOrEmpty()) u else NativeEngine.ease(b.e, u))
}

fun XmlNode.withValueAt(t: Float, value: String, snap: Float): XmlNode {
    if (!isKeyframed) return with("value", value)
    val keys = keyframes
    val here = keys.indexOfFirst { abs(it.t - t) <= snap }
    return withKeyframes(if (here >= 0) keys.toMutableList().also { it[here] = it[here].copy(v = value) } else keys + Keyframe(t, value))
}

fun XmlNode.keyframeAt(t: Float, snap: Float): Int = keyframes.indexOfFirst { abs(it.t - t) <= snap }

fun XmlNode.withKeyframeToggled(t: Float, snap: Float): XmlNode {
    val keys = keyframes
    val here = keyframeAt(t, snap)
    return when {
        here < 0 -> withKeyframes(keys + Keyframe(t, valueAt(t) ?: return this))
        keys.size == 1 -> copy(children = children.filterNot { it.name == "kf" }).with("value", keys[0].v)
        else -> withKeyframes(keys - keys[here])
    }
}

fun XmlNode.stretchAt(t: Float, snap: Float): Int {
    val keys = keyframes
    val next = keys.indexOfFirst { it.t > t + snap }
    return if (next > 0 && keys[next - 1].t < t - snap) next else -1
}

fun XmlNode.retimed(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): XmlNode {
    if (name == "speedMap" || name == "scene") return this
    if (name == "kf") {
        val t = this["t"]?.toFloatOrNull() ?: return this
        val ms = oldStart + t * (oldEnd - oldStart)
        return with("t", String.format(Locale.US, "%.6f", (ms - newStart) / (newEnd - newStart).coerceAtLeast(1)))
    }
    return if (children.isEmpty()) this else copy(children = children.map { it.retimed(oldStart, oldEnd, newStart, newEnd) })
}

private fun blend(a: String, b: String, u: Float): String {
    if (a.startsWith("#") && b.startsWith("#")) {
        val x = parseColor(a) ?: return a
        val y = parseColor(b) ?: return a
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val from = (x ushr shift) and 0xff
            val to = (y ushr shift) and 0xff
            out = out or ((from + (to - from) * u).roundToInt().coerceIn(0, 255) shl shift)
        }
        return formatColor(out)
    }
    val x = a.split(',').map { it.trim().toFloatOrNull() }
    val y = b.split(',').map { it.trim().toFloatOrNull() }
    if (x.size != y.size || x.any { it == null } || y.any { it == null }) return if (u < 1f) a else b
    return formatFloats(FloatArray(x.size) { x[it]!! + (y[it]!! - x[it]!!) * u })
}

data class EasingPreset(val label: String, val easing: Easing)

object EasingCatalog {
    private const val BEZIER = "CUBIC BEZIER EASING"
    val families: List<List<EasingPreset>> = listOf(
        listOf(
            EasingPreset(BEZIER, Easing.Linear),
            EasingPreset(BEZIER, Easing("cubicBezier", listOf(0.42f, 0f, 1f, 1f))),
            EasingPreset(BEZIER, Easing("cubicBezier", listOf(0f, 0f, 0.58f, 1f))),
            EasingPreset(BEZIER, Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f))),
        ),
        listOf(
            EasingPreset("BOUNCE", Easing("bounce", listOf(0.3f, 0.8f))),
            EasingPreset("ELASTIC", Easing("elastic", listOf(0.25f, 1f, 0.5f, 1f))),
            EasingPreset("CYCLIC", Easing("cyclic", listOf(0.2857143f, 0f, 0.5f, 0f, 0f))),
            EasingPreset("RANDOM", Easing("random", listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0f))),
        ),
        listOf(
            EasingPreset("STEPS", Easing("step", listOf(0.25f, 0f))),
            EasingPreset("ELASTIC STEPS", Easing("elasticStep", listOf(0.2f, 0.5f))),
        ),
    )

    fun familyOf(easing: Easing): Int = families.indexOfFirst { f -> f.any { it.easing.type == easing.type } }.coerceAtLeast(0)

    fun presetOf(easing: Easing): EasingPreset {
        val family = families[familyOf(easing)]
        val base = easing.copy(prefixes = emptyList())
        return family.firstOrNull { matches(it.easing, base) } ?: family.firstOrNull { it.easing.type == base.type } ?: family[0]
    }

    fun matches(a: Easing, b: Easing): Boolean = (a.isLinear && b.isLinear) ||
        (a.type == b.type && a.p.size == b.p.size && a.p.zip(b.p).all { (x, y) -> abs(x - y) <= 1e-6f })
}

class EasingHandle(
    val x: Float,
    val y: Float,
    val param: Boolean = false,
    val fromX: Float? = null,
    val fromY: Float? = null,
    val lineParam: Boolean = param,
    val lineAccent: Boolean = false,
    val dashed: Boolean = false,
    val allowOvershoot: Boolean = false,
    val dampingFactor: Float = 1f,
    val moved: (x: Float, y: Float) -> Easing,
)

private const val BEZIER_LOW = -2f
private const val BEZIER_HIGH = 3f

private const val SHORTEST = 0.02f

private fun Easing.param(i: Int, default: Float, lo: Float, hi: Float): Float =
    (p.getOrNull(i)?.takeIf { it.isFinite() } ?: default).coerceIn(lo, hi)

private fun legal(v: Float, lo: Float, hi: Float, keep: Float): Float = if (v.isNaN()) keep else v.coerceIn(lo, hi)

fun Easing.handles(): List<EasingHandle> {
    if (prefixes.count { it == "reverse" } % 2 == 1) {
        return copy(prefixes = emptyList()).handles().map { h ->
            EasingHandle(
                1 - h.x, 1 - h.y, h.param, h.fromX?.let { 1 - it }, h.fromY?.let { 1 - it }, h.lineParam, h.lineAccent,
                h.dashed, h.allowOvershoot, h.dampingFactor,
            ) { x, y -> h.moved(1 - x, 1 - y).copy(prefixes = prefixes) }
        }
    }
    return when (type) {
        "linear", "cubicBezier" -> bezierHandles()
        "bounce" -> bounceHandles()
        "elastic" -> elasticHandles()
        "cyclic" -> cyclicHandles()
        "random" -> randomHandles()
        "step" -> stepHandles()
        "elasticStep" -> elasticStepHandles()
        else -> emptyList()
    }
}

private fun Easing.bezierHandles(): List<EasingHandle> {
    val (x1, y1, x2, y2) = if (type == "linear") listOf(0f, 0f, 1f, 1f) else listOf(
        param(0, 0f, 0f, 1f), param(1, 0f, BEZIER_LOW, BEZIER_HIGH), param(2, 1f, 0f, 1f), param(3, 1f, BEZIER_LOW, BEZIER_HIGH),
    )
    fun curve(a: Float, b: Float, c: Float, d: Float) = Easing("cubicBezier", listOf(a, b, c, d), prefixes)
    return listOf(
        EasingHandle(x1, y1, fromX = 0f, fromY = 0f) { x, y ->
            curve(legal(x, 0f, 1f, x1), legal(y, BEZIER_LOW, BEZIER_HIGH, y1), x2, y2)
        },
        EasingHandle(x2, y2, fromX = 1f, fromY = 1f) { x, y ->
            curve(x1, y1, legal(x, 0f, 1f, x2), legal(y, BEZIER_LOW, BEZIER_HIGH, y2))
        },
    )
}

private fun Easing.bounceHandles(): List<EasingHandle> {
    val length = param(0, 0.3f, SHORTEST, 1f)
    val bounciness = param(1, 0.8f, 0f, 0.95f)
    return listOf(
        EasingHandle(length * (1 + bounciness) / 2, 1 - bounciness, param = true, fromX = length / 2, fromY = 1f) { x, y ->
            val b = legal(1 - y, 0f, 0.95f, bounciness)
            copy(p = listOf(legal(2 * x / (1 + b), SHORTEST, 1f, length), b))
        },
    )
}

private fun Easing.elasticHandles(): List<EasingHandle> {
    val half = param(0, 0.25f, SHORTEST, 1 - SHORTEST)
    val unused = p.getOrNull(1)?.takeIf { it.isFinite() } ?: 1f
    val decay = param(2, 0.5f, 0f, 1f)
    val strength = param(3, 1f, 0f, 1f)
    fun left(a: Float, c: Float) = (1 - a).pow(1 + 15 * c * c)
    fun curve(a: Float, c: Float, d: Float) = copy(p = listOf(a, unused, c, d))
    val tail = (1 + half) / 2
    return listOf(
        EasingHandle(half, 1 + strength * left(half, decay), param = true, fromX = half, fromY = 1f, allowOvershoot = true) { x, y ->
            val a = legal(x, SHORTEST, 1 - SHORTEST, half)
            val room = left(a, decay)
            curve(a, decay, if (room < 1e-4f) strength else legal((y - 1) / room, 0f, 1f, strength))
        },
        EasingHandle(tail, 1.5f - decay / 2, param = true, fromX = tail, fromY = 1f, allowOvershoot = true) { _, y ->
            curve(half, legal(3 - 2 * y, 0f, 1f, decay), strength)
        },
    )
}

private fun Easing.cyclicHandles(): List<EasingHandle> {
    val period = param(0, 0.2857143f, SHORTEST, 1f)
    val sharpness = param(1, 0f, 0f, 1f)
    val crest = param(2, 0.5f, SHORTEST, 1 - SHORTEST)
    val floor = param(3, 0f, 0f, 1f)
    val unused = p.getOrNull(4)?.takeIf { it.isFinite() } ?: 0f
    fun curve(a: Float = period, b: Float = sharpness, c: Float = crest, d: Float = floor) = copy(p = listOf(a, b, c, d, unused))
    val top = crest * period
    return listOf(
        EasingHandle(period, floor * period, param = true) { x, _ -> curve(a = legal(x, SHORTEST, 1f, period)) },
        EasingHandle(top, 1f, param = true) { x, _ -> curve(c = legal(x / period, SHORTEST, 1 - SHORTEST, crest)) },
        EasingHandle(top, sharpness / 2, param = true, fromX = top, fromY = 0f) { _, y -> curve(b = legal(2 * y, 0f, 1f, sharpness)) },
        EasingHandle(1f, floor, param = true, fromX = 0f, fromY = 0f) { _, y -> curve(d = legal(y, 0f, 1f, floor)) },
    )
}

private fun Easing.randomHandles(): List<EasingHandle> {
    val frequency = param(0, 0.5f, 0f, 1f)
    val magnitude = param(1, 0.5f, 0f, 1f)
    val seed = param(2, 0.5f, 0f, 1f)
    val attenuation = param(3, 0.5f, 0f, 1f)
    val warp = param(4, 0.5f, 0f, 1f)
    val tilt = param(5, 0f, 0f, 1f)
    fun curve(f: Float = frequency, m: Float = magnitude, s: Float = seed, att: Float = attenuation, w: Float = warp, t: Float = tilt) =
        copy(p = listOf(f, m, s, att, w, t))
    val bent = 0.5f.pow(1 + 10 * abs(warp - 0.5f)).let { if (warp < 0.5f) it else 1 - it }
    fun warpAt(y: Float): Float {
        val v = y.coerceIn(1 / 64f, 63 / 64f)
        val k = if (v < 0.5f) log2(1 / v) else log2(1 / (1 - v))
        return (if (v < 0.5f) 0.5f - (k - 1) / 10 else 0.5f + (k - 1) / 10).coerceIn(0f, 1f)
    }
    val wobbleX = 0.85f - attenuation / 2
    val wobbleY = 0.5f + magnitude / 2
    return listOf(
        EasingHandle(0f, tilt, param = true) { _, y -> curve(t = legal(y, 0f, 1f, tilt)) },
        EasingHandle(0.5f, bent, param = true, fromX = 0.5f, fromY = 0.5f) { _, y -> curve(w = if (y.isNaN()) warp else warpAt(y)) },
        EasingHandle(wobbleX, wobbleY, param = true, fromX = wobbleX, fromY = wobbleY, dashed = true) { x, y ->
            curve(m = legal(2 * y - 1, 0f, 1f, magnitude), att = legal(1.7f - 2 * x, 0f, 1f, attenuation))
        },
        EasingHandle(frequency, 1f, param = true) { x, _ -> curve(f = legal(x, 0f, 1f, frequency)) },
        EasingHandle(seed, 0f, param = true) { x, _ -> curve(s = legal(x, 0f, 1f, seed)) },
    )
}

private fun Easing.stepHandles(): List<EasingHandle> {
    val length = param(0, 0.25f, SHORTEST, 1f)
    val smoothing = param(1, 0f, 0f, 1f)
    return listOf(
        EasingHandle(length, length, param = true) { x, y ->
            val along = if (x.isNaN()) y else if (y.isNaN()) x else (x + y) / 2
            copy(p = listOf(legal(along, SHORTEST, 1f, length), smoothing))
        },
        EasingHandle(length * (1 - smoothing), 0f, param = true, fromX = length, fromY = 0f) { x, _ ->
            copy(p = listOf(length, legal(1 - x / length, 0f, 1f, smoothing)))
        },
    )
}

private fun Easing.elasticStepHandles(): List<EasingHandle> {
    val length = param(0, 0.2f, SHORTEST, 1f)
    val overshoot = param(1, 0.5f, 0f, 1f)
    val crest = min(1f, length * (1.5f - 0.45f * overshoot))
    return listOf(
        EasingHandle(length, 0f, param = true) { x, _ -> copy(p = listOf(legal(x, SHORTEST, 1f, length), overshoot)) },
        EasingHandle(crest, length + overshoot / 4, param = true, fromX = crest, fromY = length, allowOvershoot = true, dampingFactor = 0.5f) { _, y ->
            copy(p = listOf(length, legal(4 * (y - length), 0f, 1f, overshoot)))
        },
    )
}

fun Easing.reversed(): Easing? = when {
    isLinear -> null
    "reverse" in prefixes -> copy(prefixes = prefixes.toMutableList().apply { removeAt(lastIndexOf("reverse")) })
    type == "cubicBezier" -> {
        val q = List(4) { p.getOrNull(it) ?: if (it < 2) 0f else 1f }
        copy(p = listOf(1 - q[2], 1 - q[3], 1 - q[0], 1 - q[1])).takeUnless { EasingCatalog.matches(it, this) }
    }
    else -> copy(prefixes = prefixes + "reverse")
}
