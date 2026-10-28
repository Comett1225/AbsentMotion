package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset

internal object HandleHit {
    const val NEAR_DP = 20f
    const val FAR_DP = 30f

    fun reach(corners: List<Offset>, density: Float): Float {
        val shortSide = (0..3).minOf { (corners[(it + 1) % 4] - corners[it]).getDistance() }
        return (shortSide * 0.3f).coerceIn(NEAR_DP * density, FAR_DP * density)
    }

    fun at(handles: List<Offset>, touch: Offset, reach: Float): Int {
        val distances = handles.map { (it - touch).getDistance() }
        val nearest = distances.indices.minByOrNull { distances[it] } ?: return -1
        return if (distances[nearest] < reach) nearest else -1
    }
}
