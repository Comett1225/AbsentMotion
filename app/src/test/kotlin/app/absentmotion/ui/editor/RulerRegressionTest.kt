package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.panels.rulerAccessibleTick
import app.absentmotion.ui.editor.panels.rulerSnap
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RulerRegressionTest {
    @Test fun aScreenReaderStepsTheRulerByATwentiethOfItsRange() {
        assertEquals(55, rulerAccessibleTick(50, 0, 100, 1))
        assertEquals(45, rulerAccessibleTick(50, 0, 100, -1))
        assertEquals(100, rulerAccessibleTick(98, 0, 100, 1))
        assertEquals(0, rulerAccessibleTick(2, 0, 100, -1))
        assertEquals(4, rulerAccessibleTick(3, -10, 10, 1))
        assertEquals(-300, rulerAccessibleTick(-300, -300, 300, -1))
        assertEquals(1, rulerAccessibleTick(0, 0, 1, 1))
    }

    @Test fun settlingPicksTheNearestTick() {
        assertEquals(5f, rulerSnap(4.9f, 5), 0f)
        assertEquals(5f, rulerSnap(7.4f, 5), 0f)
        assertEquals(0f, rulerSnap(2.4f, 5), 0f)
        assertEquals(-5f, rulerSnap(-3f, 5), 0f)
        assertEquals(48f, rulerSnap(50f, 16), 0f)
        assertEquals(33f, rulerSnap(32.4f, 11), 0f)
    }

    @Test fun settlingAlwaysLandsOnATickWithinHalfASpacing() {
        for (spacing in listOf(1, 3, 5, 11, 16)) for (i in -500..500) {
            val p = i / 10f
            val snapped = rulerSnap(p, spacing)
            assertEquals("$p at $spacing is on a tick", 0f, snapped % spacing, 0.001f)
            assertTrue("$p at $spacing moved ${abs(snapped - p)}", abs(snapped - p) <= spacing / 2f + 0.001f)
        }
    }
}
