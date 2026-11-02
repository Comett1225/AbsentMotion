package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PinchZoomTest {
    @Test fun thePointBetweenTheFingersStaysUnderThem() {
        for (pan in listOf(Offset.Zero, Offset(120f, -40f))) for (about in listOf(Offset.Zero, Offset(-300f, 210f), Offset(55f, 5f))) for (factor in listOf(0.5f, 1f, 1.1f, 3f)) {
            val scale = 0.8f
            val before = (about + pan) * scale
            val after = (about + pinchedPan(pan, about, factor)) * (scale * factor)
            assertEquals(before.x, after.x, 1e-3f)
            assertEquals(before.y, after.y, 1e-3f)
        }
    }
}
