package app.absentmotion.model

import kotlin.math.max

const val POINT_EDIT = 1 shl 28

enum class Handle { In, Main, Out, Add }

private inline fun <T> List<T>.replaced(index: Int, change: (T) -> T): List<T> = mapIndexed { i, v -> if (i == index) change(v) else v }

fun Contour.withPointMoved(i: Int, by: Pt) = copy(knots = knots.replaced(i) { it.movedBy(by) })

fun Contour.withHandleMoved(i: Int, handle: Handle, to: Pt, angleLock: Boolean, distanceLock: Boolean): Contour = copy(
    knots = knots.replaced(i) { k ->
        val mine = to - k.p
        val other = if (handle == Handle.In) k.outHandle else k.inHandle
        val mirrored = if (!angleLock || other == null || mine.length < 1e-4f) other else {
            val length = if (distanceLock) mine.length else (other - k.p).length
            k.p - mine * (length / mine.length)
        }
        if (handle == Handle.In) k.copy(inHandle = to, outHandle = mirrored) else k.copy(outHandle = to, inHandle = mirrored)
    },
)

fun Contour.withPointCurveToggled(i: Int): Contour = copy(
    knots = knots.replaced(i) { k ->
        if (!k.sharp) return@replaced Knot(k.p)
        val n = knots.size
        val prev = knots.getOrNull(if (i > 0) i - 1 else if (closed) n - 1 else -1)?.p
        val next = knots.getOrNull(if (i < n - 1) i + 1 else if (closed) 0 else -1)?.p
        val direction = ((next ?: k.p) - (prev ?: k.p)).let { if (it.length < 1e-4f) Pt(1f, 0f) else it * (1f / it.length) }
        Knot(
            k.p,
            prev?.let { k.p - direction * (k.p.distanceTo(it) / 3f) },
            next?.let { k.p + direction * (k.p.distanceTo(it) / 3f) },
        )
    },
)

fun Contour.withPointRemoved(i: Int) = copy(knots = knots.filterIndexed { j, _ -> j != i }, closed = closed && knots.size > 3)

fun Contour.withPointInserted(i: Int, t: Float): Contour {
    val a = knots[i]
    val b = knots[(i + 1) % knots.size]
    val lerp = { p: Pt, q: Pt -> p + (q - p) * t }
    if (a.outHandle == null && b.inHandle == null) {
        return copy(knots = knots.toMutableList().also { it.add(i + 1, Knot(lerp(a.p, b.p))) })
    }
    val c1 = a.outHandle ?: a.p
    val c2 = b.inHandle ?: b.p
    val ab = lerp(a.p, c1); val bc = lerp(c1, c2); val cd = lerp(c2, b.p)
    val abc = lerp(ab, bc); val bcd = lerp(bc, cd)
    val mid = lerp(abc, bcd)
    val list = knots.toMutableList()
    list[i] = a.copy(outHandle = ab.takeIf { it != a.p })
    val bi = (i + 1) % knots.size
    list[bi] = b.copy(inHandle = cd.takeIf { it != b.p })
    list.add(if (bi == 0) knots.size else bi, Knot(mid, abc.takeIf { it != mid }, bcd.takeIf { it != mid }))
    return copy(knots = list)
}

fun Contour.withPointAppended(at: Pt, pull: Pt? = null): Contour {
    val knot = if (pull == null || pull.length < 1e-3f) Knot(at) else Knot(at, at - pull, at + pull)
    return copy(knots = knots + knot)
}

fun List<Contour>.hit(at: Pt, radius: Float, selected: Pair<Int, Int>?): Triple<Int, Int, Handle>? {
    var best: Triple<Int, Int, Handle>? = null
    var bestDistance = radius
    forEachIndexed { c, contour ->
        contour.knots.forEachIndexed { i, k ->
            val showsHandles = selected != null && selected.first == c && (max(i, selected.second) - kotlin.math.min(i, selected.second) <= 1 ||
                (contour.closed && max(i, selected.second) - kotlin.math.min(i, selected.second) == contour.knots.size - 1))
            val candidates = buildList {
                add(k.p to Handle.Main)
                if (showsHandles) { k.inHandle?.let { add(it to Handle.In) }; k.outHandle?.let { add(it to Handle.Out) } }
            }
            for ((position, handle) in candidates) {
                val d = position.distanceTo(at) - if (handle == Handle.Main) 0f else radius * 0.25f
                if (d < bestDistance) { bestDistance = d; best = Triple(c, i, handle) }
            }
        }
    }
    return best
}
