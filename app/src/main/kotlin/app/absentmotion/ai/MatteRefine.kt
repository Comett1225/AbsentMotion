package app.absentmotion.ai

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SOLID = 128
private const val SOFT = 24
private const val OUTSIDE = 6
private const val INSIDE = 249
private const val BAND = 2f
private const val DEPTH = 6f
private const val GROW_STEP = 0.3f
private const val SOFT_STEP = 0.4f
private const val SPREAD_ZERO = BAND / 2 + 1
private const val PICK_PIXELS = 2_000_000
internal const val MAX_TAPS = 8

internal typealias Tap = Pair<Float, Float>

internal fun withMatte(argb: Int, matte: Byte): Int =
    (argb and 0x00FFFFFF) or ((((argb ushr 24) * (matte.toInt() and 0xFF) + 127) / 255) shl 24)

internal fun matteParts(alpha: ByteArray, width: Int, height: Int, labels: IntArray, queue: IntArray): Int {
    val n = width * height
    labels.fill(0, 0, n)
    var count = 0
    for (start in 0 until n) {
        if (labels[start] != 0 || (alpha[start].toInt() and 0xFF) < SOLID) continue
        labels[start] = ++count
        var head = 0
        var tail = 0
        queue[tail++] = start
        while (head < tail) {
            val p = queue[head++]
            val x = p % width
            val y = p / width
            for (ny in max(0, y - 1)..min(height - 1, y + 1)) for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                val q = ny * width + nx
                if (labels[q] == 0 && (alpha[q].toInt() and 0xFF) >= SOLID) {
                    labels[q] = count
                    queue[tail++] = q
                }
            }
        }
    }
    var head = 0
    var tail = 0
    for (p in 0 until n) if (labels[p] != 0) queue[tail++] = p
    while (head < tail) {
        val p = queue[head++]
        val x = p % width
        val y = p / width
        for (ny in max(0, y - 1)..min(height - 1, y + 1)) for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
            val q = ny * width + nx
            if (labels[q] == 0 && (alpha[q].toInt() and 0xFF) >= SOFT) {
                labels[q] = labels[p]
                queue[tail++] = q
            }
        }
    }
    return count
}

internal fun pickedParts(labels: IntArray, count: Int, width: Int, height: Int, taps: List<Tap>): BooleanArray {
    val chosen = BooleanArray(count + 1)
    val reach = max(4, (min(width, height) * 0.06f).roundToInt())
    for ((u, v) in taps) {
        val x = (u * width).toInt().coerceIn(0, width - 1)
        val y = (v * height).toInt().coerceIn(0, height - 1)
        var best = labels[y * width + x]
        var nearest = reach * reach + 1
        if (best == 0) for (ny in max(0, y - reach)..min(height - 1, y + reach)) for (nx in max(0, x - reach)..min(width - 1, x + reach)) {
            val label = labels[ny * width + nx]
            val d = (nx - x) * (nx - x) + (ny - y) * (ny - y)
            if (label != 0 && d < nearest) {
                nearest = d
                best = label
            }
        }
        if (best != 0) chosen[best] = true
    }
    return chosen
}

internal fun followedParts(labels: IntArray, count: Int, before: BooleanArray): BooleanArray {
    val size = IntArray(count + 1)
    val shared = IntArray(count + 1)
    for (p in before.indices) {
        val label = labels[p]
        size[label]++
        if (before[p]) shared[label]++
    }
    var most = 0
    for (label in 1..count) if (shared[label] > shared[most] || most == 0) most = label
    return BooleanArray(count + 1) { it != 0 && shared[it] > 0 && (it == most || shared[it] * 5 >= size[it]) }
}

internal class PartFollower(private val taps: List<Tap>) {
    private var labels = IntArray(0)
    private var queue = IntArray(0)
    private var kept: BooleanArray? = null

    fun next(alpha: ByteArray, width: Int, height: Int): ByteArray {
        val n = width * height
        if (labels.size != n) {
            labels = IntArray(n)
            queue = IntArray(n)
            kept = null
        }
        val count = matteParts(alpha, width, height, labels, queue)
        val before = kept
        val chosen = if (before == null) pickedParts(labels, count, width, height, taps) else followedParts(labels, count, before)
        val out = ByteArray(n)
        var any = false
        for (p in 0 until n) if (chosen[labels[p]]) {
            out[p] = alpha[p]
            any = true
        }
        if (any) kept = (before ?: BooleanArray(n)).also { for (p in 0 until n) it[p] = chosen[labels[p]] }
        return out
    }
}

internal fun clearUnpicked(alpha: ByteArray, width: Int, height: Int, taps: List<Tap>) {
    val f = max(1, ceil(sqrt(width.toDouble() * height / PICK_PIXELS)).toInt())
    val w = (width + f - 1) / f
    val h = (height + f - 1) / f
    val small = if (f == 1) alpha else ByteArray(w * h).also { pooled ->
        for (y in 0 until height) {
            val row = y / f * w
            for (x in 0 until width) {
                val a = alpha[y * width + x].toInt() and 0xFF
                if (a > (pooled[row + x / f].toInt() and 0xFF)) pooled[row + x / f] = a.toByte()
            }
        }
    }
    val labels = IntArray(w * h)
    val chosen = pickedParts(labels, matteParts(small, w, h, labels, IntArray(w * h)), w, h, taps)
    for (y in 0 until height) {
        val row = y / f * w
        for (x in 0 until width) if (!chosen[labels[row + x / f]]) alpha[y * width + x] = 0
    }
}

internal fun matteCode(depth: Float): Float = (0.5f + depth / (2 * DEPTH)).coerceIn(0f, 1f)

internal fun matteDepth(code: Float): Float = (code - 0.5f) * 2 * DEPTH

private fun spread(alpha: ByteArray, width: Int, height: Int, inside: Boolean, d: ByteArray) {
    for (p in 0 until width * height) {
        val a = alpha[p].toInt() and 0xFF
        val far = if (inside) a >= INSIDE else a <= OUTSIDE
        val opposite = if (inside) a <= OUTSIDE else a >= INSIDE
        val depth = if (inside) a / 255f - 0.5f else 0.5f - a / 255f
        d[p] = (if (far) 255 else if (opposite) ((SPREAD_ZERO - 0.5f) * 4).roundToInt() else ((depth * BAND + SPREAD_ZERO) * 4).roundToInt()).toByte()
    }
    for (y in 0 until height) for (x in 0 until width) {
        val p = y * width + x
        var v = d[p].toInt() and 0xFF
        if (v == 0) continue
        if (x > 0) v = min(v, (d[p - 1].toInt() and 0xFF) + 4)
        if (y > 0) {
            v = min(v, (d[p - width].toInt() and 0xFF) + 4)
            if (x > 0) v = min(v, (d[p - width - 1].toInt() and 0xFF) + 6)
            if (x < width - 1) v = min(v, (d[p - width + 1].toInt() and 0xFF) + 6)
        }
        d[p] = v.toByte()
    }
    for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
        val p = y * width + x
        var v = d[p].toInt() and 0xFF
        if (v == 0) continue
        if (x < width - 1) v = min(v, (d[p + 1].toInt() and 0xFF) + 4)
        if (y < height - 1) {
            v = min(v, (d[p + width].toInt() and 0xFF) + 4)
            if (x < width - 1) v = min(v, (d[p + width + 1].toInt() and 0xFF) + 6)
            if (x > 0) v = min(v, (d[p + width - 1].toInt() and 0xFF) + 6)
        }
        d[p] = v.toByte()
    }
}

private fun level(v: Float): Byte = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()

private inline fun edgeMap(alpha: ByteArray, width: Int, height: Int, value: (Float) -> Float): ByteArray {
    val n = width * height
    val out = ByteArray(n)
    val d = ByteArray(n)
    spread(alpha, width, height, false, d)
    for (p in 0 until n) {
        val a = alpha[p].toInt() and 0xFF
        if (a <= OUTSIDE) out[p] = level(value(SPREAD_ZERO - (d[p].toInt() and 0xFF) / 4f))
        else if (a < INSIDE) out[p] = level(value((a / 255f - 0.5f) * BAND))
    }
    spread(alpha, width, height, true, d)
    for (p in 0 until n) if ((alpha[p].toInt() and 0xFF) >= INSIDE) out[p] = level(value((d[p].toInt() and 0xFF) / 4f - SPREAD_ZERO))
    return out
}

internal fun encodeMatte(alpha: ByteArray, width: Int, height: Int): ByteArray = edgeMap(alpha, width, height, ::matteCode)

private fun smoothstep(from: Float, to: Float, v: Float): Float {
    val x = ((v - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

internal fun bakeEdge(alpha: ByteArray, width: Int, height: Int, edge: MatteEdge, unit: Float): ByteArray {
    val (from, to) = edge.span(unit)
    return edgeMap(alpha, width, height) { smoothstep(from, to, it) }
}

internal fun pictureUnit(width: Int, height: Int): Float = (min(width, height) / 540f).coerceIn(1f, 5.5f)

internal data class MatteEdge(val softness: Int = 0, val grow: Int = 0) {
    fun span(unit: Float = 1f): Pair<Float, Float> {
        val width = BAND + softness.coerceIn(0, MAX_SOFTNESS) * SOFT_STEP * unit
        val centre = -grow.coerceIn(-MAX_GROW, MAX_GROW) * GROW_STEP * unit
        return centre - width / 2 to centre + width / 2
    }

    fun key(): Pair<Float, Float> {
        val (from, to) = span()
        return matteCode(from) to matteCode(to) - matteCode(from)
    }

    companion object {
        const val MAX_SOFTNESS = 10
        const val MAX_GROW = 10

        fun of(threshold: Float, softness: Float): MatteEdge {
            val from = matteDepth(threshold)
            val to = matteDepth(threshold + softness)
            return MatteEdge(((to - from - BAND) / SOFT_STEP).roundToInt().coerceIn(0, MAX_SOFTNESS),
                (-(from + to) / 2 / GROW_STEP).roundToInt().coerceIn(-MAX_GROW, MAX_GROW))
        }
    }
}

internal class Cutout(val keep: List<Tap> = emptyList(), val edge: MatteEdge = MatteEdge()) {
    val plain: Boolean get() = keep.isEmpty() && edge == MatteEdge()

    val option: String? get() = if (plain) null else "soft=${edge.softness};grow=${edge.grow};keep=" +
        keep.joinToString(",") { (u, v) -> String.format(Locale.US, "%.4f:%.4f", u, v) }

    fun refine(alpha: ByteArray, width: Int, height: Int): ByteArray {
        if (keep.isNotEmpty()) clearUnpicked(alpha, width, height, keep)
        return if (edge == MatteEdge()) alpha else bakeEdge(alpha, width, height, edge, pictureUnit(width, height))
    }

    companion object {
        fun parse(option: String?): Cutout {
            val parts = option.orEmpty().split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val keep = parts["keep"].orEmpty().split(',').mapNotNull { pair ->
                val (u, v) = pair.split(':').takeIf { it.size == 2 }?.map { it.toFloatOrNull() ?: Float.NaN } ?: return@mapNotNull null
                (u to v).takeIf { u in 0f..1f && v in 0f..1f }
            }
            return Cutout(keep.take(MAX_TAPS), MatteEdge(parts["soft"]?.toIntOrNull()?.coerceIn(0, MatteEdge.MAX_SOFTNESS) ?: 0,
                parts["grow"]?.toIntOrNull()?.coerceIn(-MatteEdge.MAX_GROW, MatteEdge.MAX_GROW) ?: 0))
        }
    }
}

internal fun frameToView(u: Float, v: Float, turn: Int): Tap = when (Math.floorMod(turn, 360)) {
    90 -> 1 - v to u
    180 -> 1 - u to 1 - v
    270 -> v to 1 - u
    else -> u to v
}

internal fun viewToFrame(x: Float, y: Float, turn: Int): Tap = when (Math.floorMod(turn, 360)) {
    90 -> y to 1 - x
    180 -> 1 - x to 1 - y
    270 -> 1 - y to x
    else -> x to y
}
