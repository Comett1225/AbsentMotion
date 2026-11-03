package app.absentmotion.compat

import app.absentmotion.model.Keyframe
import app.absentmotion.model.XmlNode
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import app.absentmotion.model.withKeyframes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal class Orientation(private val quat: XmlNode?, private val spin: XmlNode?, private val base: DoubleArray) {
    private val quatKeys = quat?.takeIf { it.isKeyframed }?.keyframes
    private val spinKeys = spin?.takeIf { it.isKeyframed }?.keyframes

    private fun quatAt(t: Float): DoubleArray {
        quat ?: return IDENTITY
        val keys = quatKeys ?: return quaternion(quat.valueAt(0f)) ?: IDENTITY
        val (a, b, u) = segment(keys, t)
        val qa = quaternion(a.v) ?: IDENTITY
        return if (b == null) qa else slerp(qa, quaternion(b.v) ?: IDENTITY, easeOf(b.e)?.invoke(u.toFloat())?.toDouble() ?: u)
    }

    private fun spinAt(t: Float): DoubleArray {
        spin ?: return DoubleArray(3)
        val keys = spinKeys ?: return numbers(spin.valueAt(0f))
        val (a, b, u) = segment(keys, t)
        val va = numbers(a.v)
        if (b == null) return va
        val vb = numbers(b.v)
        val k = easeOf(b.e)?.invoke(u.toFloat())?.toDouble() ?: u
        return DoubleArray(3) { va[it] + (vb[it] - va[it]) * k }
    }

    private fun segment(keys: List<Keyframe>, t: Float): Triple<Keyframe, Keyframe?, Double> {
        if (t <= keys.first().t) return Triple(keys.first(), null, 0.0)
        if (t >= keys.last().t) return Triple(keys.last(), null, 0.0)
        val next = keys.indexOfFirst { it.t > t }
        val a = keys[next - 1]
        val b = keys[next]
        return Triple(a, b, ((t - a.t) / (b.t - a.t)).toDouble())
    }

    private fun naive(t: Float): DoubleArray = spinAt(t).let { doubleArrayOf(-it[0], it[1], -it[2]) }

    private fun solutions(t: Float): List<DoubleArray> {
        val s = spinAt(t)
        val m = times(times(flip(matrix(quatAt(t))), flip(spinMatrix(s[0], s[1], s[2]))), oursMatrix(base[0], base[1], base[2]))
        return decompose(m)
    }

    private fun pick(t: Float, reference: DoubleArray): DoubleArray {
        var best = reference
        var distance = Double.MAX_VALUE
        for (candidate in solutions(t)) {
            val near = DoubleArray(3) { nearest(candidate[it], reference[it]) }
            val d = (0..2).sumOf { (near[it] - reference[it]) * (near[it] - reference[it]) }
            if (d < distance) {
                distance = d
                best = near
            }
        }
        return best
    }

    private fun shift(t: Float, previous: DoubleArray?, previousNaive: DoubleArray?): DoubleArray {
        val n = naive(t)
        return if (previous == null || previousNaive == null) n else DoubleArray(3) { n[it] + previous[it] - previousNaive[it] }
    }

    fun properties(ids: List<String>): List<XmlNode> {
        val times = ((quatKeys.orEmpty() + spinKeys.orEmpty()).map { it.t }).distinct().sorted()
        if (times.isEmpty()) {
            val v = pick(0f, naive(0f))
            return ids.mapIndexed { i, id -> property(id, formatFloats(floatArrayOf(v[i].toFloat()))) }
        }
        val easing = { t: Float -> (quatKeys.orEmpty().firstOrNull { it.t == t } ?: spinKeys.orEmpty().firstOrNull { it.t == t })?.e }
        val out = ArrayList<Pair<Float, DoubleArray>>()
        val kept = HashSet<Float>()
        var previous: DoubleArray? = null
        var previousNaive: DoubleArray? = null
        for ((index, t) in times.withIndex()) {
            val v = pick(t, shift(t, previous, previousNaive))
            if (index > 0) {
                val from = times[index - 1]
                val e = easing(t)
                val ease = easeOf(e)
                if (ease != null && out.last().second.let { exceeds(from, it, t, v, ease(0.5f).toDouble()) }) {
                    refine(from, out.last().second, t, v, 0, out)
                } else {
                    kept.add(t)
                }
            }
            out.add(t to v)
            previous = v
            previousNaive = naive(t)
        }
        return ids.mapIndexed { i, id ->
            val keys = out.map { (t, v) -> Keyframe(t, formatFloats(floatArrayOf(v[i].toFloat())), if (t in kept) easing(t) else null) }
            XmlNode("property", linkedMapOf("name" to id, "type" to "float")).withKeyframes(keys)
        }
    }

    private fun exceeds(a: Float, va: DoubleArray, b: Float, vb: DoubleArray, fraction: Double): Boolean {
        val middle = (a + b) / 2
        val exact = pick(middle, DoubleArray(3) { va[it] + (vb[it] - va[it]) * fraction })
        return (0..2).any { abs(exact[it] - (va[it] + (vb[it] - va[it]) * fraction)) > TOLERANCE }
    }

    private fun refine(a: Float, va: DoubleArray, b: Float, vb: DoubleArray, depth: Int, out: MutableList<Pair<Float, DoubleArray>>) {
        val middle = (a + b) / 2
        val exact = pick(middle, DoubleArray(3) { (va[it] + vb[it]) / 2 })
        val far = (0..2).any { abs(exact[it] - (va[it] + vb[it]) / 2) > TOLERANCE }
        if (!far || depth >= MAX_DEPTH) return
        refine(a, va, middle, exact, depth + 1, out)
        out.add(middle to exact)
        refine(middle, exact, b, vb, depth + 1, out)
    }

    companion object {
        private const val TOLERANCE = 0.75
        private const val MAX_DEPTH = 4
        private val IDENTITY = doubleArrayOf(0.0, 0.0, 0.0, 1.0)

        private fun property(id: String, value: String) = XmlNode("property", linkedMapOf("name" to id, "type" to "float", "value" to value))

        fun quaternion(text: String?): DoubleArray? {
            val v = text?.split(',')?.map { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null } ?: return null
            if (v.size != 4) return null
            val n = sqrt(v.sumOf { it * it })
            return if (n < 1e-9) null else DoubleArray(4) { v[it] / n }
        }

        private fun numbers(text: String?): DoubleArray {
            val v = text?.split(',')?.map { it.trim().finiteFloat()?.toDouble() ?: 0.0 }.orEmpty()
            return DoubleArray(3) { v.getOrElse(it) { 0.0 } }
        }

        fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
            val dot = (0..3).sumOf { a[it] * b[it] }
            val sign = if (dot < 0) -1.0 else 1.0
            val angle = acos(abs(dot).coerceAtMost(1.0))
            val s = sin(angle)
            val wa = if (s < 1e-6) 1 - t else sin((1 - t) * angle) / s
            val wb = (if (s < 1e-6) t else sin(t * angle) / s) * sign
            val q = DoubleArray(4) { wa * a[it] + wb * b[it] }
            val n = sqrt(q.sumOf { it * it })
            return DoubleArray(4) { q[it] / n }
        }

        private fun nearest(angle: Double, reference: Double) = angle + 360 * ((reference - angle) / 360).roundToInt()

        private fun matrix(q: DoubleArray): DoubleArray {
            val (x, y, z, w) = q
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
            )
        }

        private fun times(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

        private fun flip(m: DoubleArray) = DoubleArray(9) { if ((it / 3 == 1) != (it % 3 == 1)) -m[it] else m[it] }

        private fun turnX(degrees: Double): DoubleArray = (degrees * PI / 180).let { doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(it), -sin(it), 0.0, sin(it), cos(it)) }

        private fun turnY(degrees: Double): DoubleArray = (degrees * PI / 180).let { doubleArrayOf(cos(it), 0.0, sin(it), 0.0, 1.0, 0.0, -sin(it), 0.0, cos(it)) }

        private fun turnZ(degrees: Double): DoubleArray = (degrees * PI / 180).let { doubleArrayOf(cos(it), -sin(it), 0.0, sin(it), cos(it), 0.0, 0.0, 0.0, 1.0) }

        private fun spinMatrix(x: Double, y: Double, z: Double) = times(times(turnZ(-z), turnX(-x)), turnY(-y))

        private fun oursMatrix(x: Double, y: Double, z: Double) = times(times(turnZ(-z), turnY(-y)), turnX(-x))

        private fun decompose(m: DoubleArray): List<DoubleArray> {
            val middle = asin((-m[6]).coerceIn(-1.0, 1.0))
            return listOf(middle, PI - middle).map { b ->
                val c = cos(b)
                val (x, z) = if (abs(c) > 1e-6) atan2(m[7] / c, m[8] / c) to atan2(m[3] / c, m[0] / c) else 0.0 to atan2(-m[1], m[4])
                doubleArrayOf(-x, -b, -z).map { it * 180 / PI }.toDoubleArray()
            }
        }

        fun easeOf(text: String?): ((Float) -> Float)? {
            val parts = text?.trim()?.split(Regex("\\s+")).orEmpty().filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts[0] == "linear") return { it }
            if (parts[0] != "cubicBezier" || parts.size != 5) return null
            val p = parts.drop(1).map { it.finiteFloat() ?: return null }
            return { u -> bezier(p, u) }
        }

        private fun bezier(p: List<Float>, u: Float): Float {
            fun axis(a: Float, b: Float, s: Float) = 3 * a * (1 - s) * (1 - s) * s + 3 * b * (1 - s) * s * s + s * s * s
            var lo = 0f
            var hi = 1f
            repeat(24) {
                val mid = (lo + hi) / 2
                if (axis(p[0], p[2], mid) < u) lo = mid else hi = mid
            }
            return axis(p[1], p[3], (lo + hi) / 2)
        }
    }
}
