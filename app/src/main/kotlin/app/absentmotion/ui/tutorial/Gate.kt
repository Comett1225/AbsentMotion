package app.absentmotion.ui.tutorial

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

internal object GateMath {
    fun blocked(window: Area, allowed: List<Area>, denied: List<Area> = emptyList()): List<Area> {
        if (window.width <= 0f || window.height <= 0f) return emptyList()
        val open = allowed.map { it.clampedTo(window) }.filter { it.width > 0f && it.height > 0f }
        if (open.isEmpty()) return listOf(window)
        val shut = denied.map { it.clampedTo(window) }.filter { it.width > 0f && it.height > 0f }
        val edges = open + shut
        val xs = (listOf(window.left, window.right) + edges.flatMap { listOf(it.left, it.right) }).distinct().sorted()
        val ys = (listOf(window.top, window.bottom) + edges.flatMap { listOf(it.top, it.bottom) }).distinct().sorted()
        val bands = ArrayList<Area>()
        for (j in 0 until ys.size - 1) {
            val y = (ys[j] + ys[j + 1]) / 2
            var from: Float? = null
            for (i in 0 until xs.size - 1) {
                val x = (xs[i] + xs[i + 1]) / 2
                val shutHere = open.none { it.holds(x, y) } || shut.any { it.holds(x, y) }
                if (shutHere && from == null) from = xs[i]
                if (!shutHere && from != null) {
                    bands += Area(from, ys[j], xs[i], ys[j + 1])
                    from = null
                }
            }
            if (from != null) bands += Area(from, ys[j], window.right, ys[j + 1])
        }
        val merged = ArrayList<Area>()
        for (band in bands) {
            val above = merged.indexOfFirst { it.left == band.left && it.right == band.right && it.bottom == band.top }
            if (above >= 0) merged[above] = merged[above].copy(bottom = band.bottom) else merged += band
        }
        return merged
    }

    fun shut(rect: Area, blocked: List<Area>, any: Boolean): Boolean {
        if (rect.width <= 0f || rect.height <= 0f) return false
        if (!any) return !admits(blocked, rect.centreX, rect.centreY)
        val covered = blocked.sumOf { b ->
            val w = minOf(b.right, rect.right) - maxOf(b.left, rect.left)
            val h = minOf(b.bottom, rect.bottom) - maxOf(b.top, rect.top)
            if (w > 0f && h > 0f) (w * h).toDouble() else 0.0
        }
        return covered >= rect.width.toDouble() * rect.height - 0.5
    }

    fun admits(blocked: List<Area>, x: Float, y: Float): Boolean = blocked.none { it.holds(x, y) }

    private fun Area.holds(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

@Composable
internal fun InputGate(frame: Area?, blocked: List<Area>) {
    if (frame == null) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {})
        return
    }
    Layout(content = { for (area in blocked) Box(Modifier.pointerInput(Unit) {}) }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {
            measurables.forEachIndexed { i, measurable ->
                val area = blocked[i]
                val width = area.width.roundToInt().coerceAtLeast(0)
                val height = area.height.roundToInt().coerceAtLeast(0)
                measurable.measure(Constraints.fixed(width, height)).place((area.left - frame.left).roundToInt(), (area.top - frame.top).roundToInt())
            }
        }
    }
}
