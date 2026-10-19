package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.panels.rulerSnap
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class RulerRegressionTest {
    @Test fun settlingPreservesTheChosenTickAndCentersTheNeedle() {
        for (spacing in listOf(1, 3, 5, 11, 16)) for (i in -500..500) {
            val p = i / 10f
            val tick = (p / spacing).roundToInt()
            assertEquals(tick * spacing.toFloat(), rulerSnap(p, spacing), 0.001f)
        }
    }
}
