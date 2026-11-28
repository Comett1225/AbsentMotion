package app.absentmotion.media

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal const val MOTION_SIDE = 320

internal fun motionFactor(width: Int, height: Int): Int = maxOf(1, ceil(maxOf(width, height) / MOTION_SIDE.toDouble()).toInt())

internal class Plane(val w: Int, val h: Int, val v: FloatArray) {
    fun at(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = minOf(cx.toInt(), maxOf(w - 2, 0))
        val y0 = minOf(cy.toInt(), maxOf(h - 2, 0))
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = v[y0 * w + x0] + (v[y0 * w + x1] - v[y0 * w + x0]) * fx
        val low = v[y1 * w + x0] + (v[y1 * w + x1] - v[y1 * w + x0]) * fx
        return top + (low - top) * fy
    }

    fun soft(): Plane {
        val across = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val row = y * w
            across[row + x] = (v[row + maxOf(x - 1, 0)] + 2 * v[row + x] + v[row + minOf(x + 1, w - 1)]) * 0.25f
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = (across[maxOf(y - 1, 0) * w + x] + 2 * across[y * w + x] + across[minOf(y + 1, h - 1) * w + x]) * 0.25f
        }
        return Plane(w, h, out)
    }

    fun half(): Plane {
        val hw = maxOf(1, w / 2)
        val hh = maxOf(1, h / 2)
        val s = soft()
        return Plane(hw, hh, FloatArray(hw * hh) { s.v[minOf(2 * (it / hw), h - 1) * w + minOf(2 * (it % hw), w - 1)] })
    }
}

internal class MotionFrame(luma: LumaFrame) {
    val width = luma.width
    val height = luma.height
    val levels: List<Plane> = run {
        val first = Plane(width, height, FloatArray(width * height) { (luma.pixels[it].toInt() and 255).toFloat() }).soft()
        val list = arrayListOf(first)
        while (list.size < LEVELS && minOf(list.last().w, list.last().h) >= 2 * MIN_SIDE) list += list.last().half()
        list
    }

    val corners: List<FloatArray> by lazy {
        val p = levels[0]
        val gx = FloatArray(width * height)
        val gy = FloatArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            gx[i] = (p.v[i + 1] - p.v[i - 1]) * 0.5f
            gy[i] = (p.v[i + width] - p.v[i - width]) * 0.5f
        }
        val columns = GRID
        val rows = maxOf(4, minOf(GRID, (GRID * height / maxOf(width, 1))))
        val margin = WINDOW + 2
        val found = ArrayList<FloatArray>()
        var strongest = 0f
        for (r in 0 until rows) for (c in 0 until columns) {
            val x0 = maxOf(margin, c * width / columns)
            val x1 = minOf(width - margin, (c + 1) * width / columns)
            val y0 = maxOf(margin, r * height / rows)
            val y1 = minOf(height - margin, (r + 1) * height / rows)
            var best = 0f
            var bx = -1
            var by = -1
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    var a = 0f
                    var b = 0f
                    var d = 0f
                    for (j in -2..2) for (k in -2..2) {
                        val i = (y + j) * width + x + k
                        a += gx[i] * gx[i]
                        b += gx[i] * gy[i]
                        d += gy[i] * gy[i]
                    }
                    val least = (a + d) * 0.5f - sqrt(((a - d) * 0.5f) * ((a - d) * 0.5f) + b * b)
                    if (least > best) { best = least; bx = x; by = y }
                    x += 2
                }
                y += 2
            }
            if (bx >= 0) {
                found += floatArrayOf(bx.toFloat(), by.toFloat(), best)
                strongest = maxOf(strongest, best)
            }
        }
        found.filter { it[2] >= maxOf(MIN_CORNER, strongest * 0.01f) }
    }

    fun stepFrom(previous: MotionFrame): FloatArray {
        val from = ArrayList<FloatArray>()
        val to = ArrayList<FloatArray>()
        for (corner in previous.corners) {
            val moved = follow(previous, corner[0], corner[1]) ?: continue
            from += floatArrayOf(corner[0], corner[1])
            to += moved
        }
        val cx = (previous.width - 1) * 0.5f
        val cy = (previous.height - 1) * 0.5f
        val step = rigid(from, to, cx, cy) ?: return FloatArray(3)
        return floatArrayOf(step[0] / previous.width, step[1] / previous.width, step[2])
    }

    private fun follow(previous: MotionFrame, x: Float, y: Float): FloatArray? {
        val top = minOf(levels.size, previous.levels.size) - 1
        var dx = 0f
        var dy = 0f
        val size = 2 * WINDOW + 1
        val t = FloatArray(size * size)
        val tx = FloatArray(size * size)
        val ty = FloatArray(size * size)
        for (level in top downTo 0) {
            if (level != top) { dx *= 2; dy *= 2 }
            val a = previous.levels[level]
            val b = levels[level]
            val scale = (1 shl level).toFloat()
            val px = x / scale
            val py = y / scale
            var gxx = 0f
            var gxy = 0f
            var gyy = 0f
            for (j in -WINDOW..WINDOW) for (i in -WINDOW..WINDOW) {
                val k = (j + WINDOW) * size + i + WINDOW
                t[k] = a.at(px + i, py + j)
                tx[k] = (a.at(px + i + 1, py + j) - a.at(px + i - 1, py + j)) * 0.5f
                ty[k] = (a.at(px + i, py + j + 1) - a.at(px + i, py + j - 1)) * 0.5f
                gxx += tx[k] * tx[k]
                gxy += tx[k] * ty[k]
                gyy += ty[k] * ty[k]
            }
            val det = gxx * gyy - gxy * gxy
            if (!(det > 1e-3f)) return null
            for (iteration in 0 until ITERATIONS) {
                var ex = 0f
                var ey = 0f
                for (j in -WINDOW..WINDOW) for (i in -WINDOW..WINDOW) {
                    val k = (j + WINDOW) * size + i + WINDOW
                    val e = t[k] - b.at(px + i + dx, py + j + dy)
                    ex += e * tx[k]
                    ey += e * ty[k]
                }
                val sx = (gyy * ex - gxy * ey) / det
                val sy = (gxx * ey - gxy * ex) / det
                dx += sx
                dy += sy
                if (abs(sx) + abs(sy) < 0.01f) break
            }
            if (abs(dx) > b.w || abs(dy) > b.h) return null
        }
        val nx = x + dx
        val ny = y + dy
        if (nx < 1 || ny < 1 || nx > width - 2 || ny > height - 2) return null
        var error = 0f
        val a = previous.levels[0]
        val b = levels[0]
        for (j in -WINDOW..WINDOW) for (i in -WINDOW..WINDOW) error += abs(a.at(x + i, y + j) - b.at(nx + i, ny + j))
        return if (error / (size * size) > MAX_ERROR) null else floatArrayOf(nx, ny)
    }

    companion object {
        const val LEVELS = 4
        const val MIN_SIDE = 24
        const val GRID = 16
        const val WINDOW = 6
        const val ITERATIONS = 12
        const val MIN_CORNER = 40f
        const val MAX_ERROR = 14f
    }
}

internal fun rigid(from: List<FloatArray>, to: List<FloatArray>, cx: Float, cy: Float): FloatArray? {
    if (from.size < MIN_POINTS) return null
    val weight = FloatArray(from.size) { 1f }
    var step = FloatArray(3)
    repeat(5) {
        var total = 0f
        var fx = 0f
        var fy = 0f
        var gx = 0f
        var gy = 0f
        for (i in from.indices) {
            total += weight[i]
            fx += weight[i] * from[i][0]
            fy += weight[i] * from[i][1]
            gx += weight[i] * to[i][0]
            gy += weight[i] * to[i][1]
        }
        if (total < MIN_POINTS) return null
        fx /= total; fy /= total; gx /= total; gy /= total
        var cross = 0f
        var dot = 0f
        for (i in from.indices) {
            val ax = from[i][0] - fx
            val ay = from[i][1] - fy
            val bx = to[i][0] - gx
            val by = to[i][1] - gy
            cross += weight[i] * (ax * by - ay * bx)
            dot += weight[i] * (ax * bx + ay * by)
        }
        val angle = atan2(cross, dot)
        val c = cos(angle)
        val s = sin(angle)
        val ux = gx - cx - (c * (fx - cx) - s * (fy - cy))
        val uy = gy - cy - (s * (fx - cx) + c * (fy - cy))
        step = floatArrayOf(ux, uy, angle)
        val misses = FloatArray(from.size) { i ->
            val px = c * (from[i][0] - cx) - s * (from[i][1] - cy) + cx + ux
            val py = s * (from[i][0] - cx) + c * (from[i][1] - cy) + cy + uy
            hypot(px - to[i][0], py - to[i][1])
        }
        val limit = maxOf(0.75f, 2.5f * misses.sorted()[misses.size / 2])
        for (i in from.indices) weight[i] = if (misses[i] <= limit) 1f else 0f
    }
    return step
}

private const val MIN_POINTS = 6
