package app.absentmotion.ui.editor

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointDragTest {
    @Test fun aPointWaitsForTheFingerToPassTheWeightAndThenTrailsItByThatMuch() {
        val weight = 12f
        val still = weightedDrag(9f, 0f, weight)
        assertEquals(0f, still.x, 0f)
        assertEquals(0f, still.y, 0f)
        val moved = weightedDrag(30f, 40f, weight)
        assertEquals(50f - weight, hypot(moved.x, moved.y), 0.001f)
        assertEquals(30f / 50f, moved.x / hypot(moved.x, moved.y), 0.001f)
    }

    @Test fun theDragGrowsWithoutAJumpWhenItStarts() {
        var last = 0f
        for (step in 0..200) {
            val d = weightedDrag(step * 0.5f, 0f, 12f).x
            assertTrue("jumped at $step", d - last < 0.51f)
            last = d
        }
    }
}
