package app.absentmotion.ui.editor

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object RowDrag {
    fun clampTop(top: Float, rowH: Float, viewTop: Float, viewBottom: Float, scroll: Float, count: Int): Float {
        val first = viewTop - scroll
        val low = max(viewTop - rowH / 2, first)
        val high = min(viewBottom - rowH / 2, first + (count - 1) * rowH)
        return top.coerceIn(low, max(low, high))
    }

    fun slot(top: Float, rowH: Float, viewTop: Float, scroll: Float, count: Int): Int =
        floor((top + rowH / 2 - viewTop + scroll) / rowH).toInt().coerceIn(0, max(0, count - 1))

    fun edgeSpeed(y: Float, viewTop: Float, viewBottom: Float, zone: Float, slowest: Float, fastest: Float): Float {
        val reach = min(zone, (viewBottom - viewTop) / 3)
        if (reach <= 0f) return 0f
        val up = viewTop + reach - y
        val down = y - (viewBottom - reach)
        val depth = max(up, down) / reach
        if (depth <= 0f) return 0f
        val eased = min(depth, 1f).let { it * it }
        val speed = slowest + (fastest - slowest) * eased
        return if (up > down) -speed else speed
    }

    fun toward(speed: Float, y: Float, startY: Float, slop: Float): Float =
        if ((speed > 0f && y > startY + slop) || (speed < 0f && y < startY - slop)) speed else 0f

    fun fingerRow(y: Float, rowH: Float, viewTop: Float, viewBottom: Float, scroll: Float, count: Int): Int =
        floor((y.coerceIn(viewTop, max(viewTop, viewBottom - 1f)) - viewTop + scroll) / rowH).toInt().coerceIn(0, max(0, count - 1))

    fun slideSelection(base: Set<Long>, rowIds: List<Long>, anchor: Int, finger: Int, add: Boolean): Set<Long> {
        val range = rowIds.subList(min(anchor, finger).coerceAtLeast(0), (max(anchor, finger) + 1).coerceAtMost(rowIds.size))
        return if (add) base + range else base - range.toSet()
    }

    fun shift(row: Int, from: Int, to: Int): Int = when {
        from < to && row in from + 1..to -> -1
        to < from && row in to until from -> 1
        else -> 0
    }
}
