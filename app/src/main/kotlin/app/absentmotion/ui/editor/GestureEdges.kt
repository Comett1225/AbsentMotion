package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal object GestureEdges {
    const val ZONE_DP = 48f
    const val HALF_DP = 32f
    const val LIMIT_DP = 200f

    fun bands(handles: List<Offset>, width: Float, density: Float): List<Rect> {
        val zone = ZONE_DP * density
        val half = HALF_DP * density
        val limit = LIMIT_DP * density
        val left = handles.filter { it.x > -zone && it.x < zone }.map { Rect(0f, it.y - half, zone, it.y + half) }
        val right = handles.filter { it.x > width - zone && it.x < width + zone }.map { Rect(width - zone, it.y - half, width, it.y + half) }
        return merged(left, limit) + merged(right, limit)
    }

    private fun merged(bands: List<Rect>, limit: Float): List<Rect> {
        var out = emptyList<Rect>()
        for (band in bands.sortedBy { it.top }) {
            val last = out.lastOrNull()
            val next = if (last != null && band.top <= last.bottom) out.dropLast(1) + Rect(last.left, last.top, last.right, maxOf(last.bottom, band.bottom)) else out + band
            if (next.sumOf { it.height.toDouble() } > limit) break
            out = next
        }
        return out
    }
}
