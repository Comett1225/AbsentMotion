package app.absentmotion.ui.editor

import app.absentmotion.model.ShapeParam.Kind
import app.absentmotion.ui.editor.panels.RulerMath
import app.absentmotion.ui.editor.panels.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerMathTest {
    @Test fun aHundredDpGrowsAboutAQuarterNearFullSize() {
        assertEquals(1.25f, RulerMath.scale(1f, 100f), 0.03f)
        assertEquals(0.8f, RulerMath.scale(1f, -100f), 0.001f)
        assertEquals(0.625f, RulerMath.scale(0.5f, 100f), 0.001f)
    }

    @Test fun bigLayersGrowGently() {
        val near = RulerMath.scale(1f, 100f) / 1f
        val big = RulerMath.scale(3f, 100f) / 3f
        val huge = RulerMath.scale(10f, 100f) / 10f
        assertTrue(big < 1.1f)
        assertTrue(huge < big && big < near)
        assertEquals(RulerMath.scale(3f, 100f) - 3f, RulerMath.scale(10f, 100f) - 10f, 0.001f)
    }

    @Test fun theTotalDistanceDecidesNotTheNumberOfEvents() {
        for (base in listOf(0.2f, 1f, 2.5f, 7f)) {
            assertEquals(base, RulerMath.scale(base, 0f), base * 1e-4f)
            val there = RulerMath.scale(base, 240f)
            assertEquals(base, RulerMath.scale(there, -240f), base * 1e-3f)
            val back = RulerMath.scale(base, -240f)
            assertEquals(base, RulerMath.scale(back, 240f), base * 1e-3f)
        }
    }

    @Test fun scaleIsSmoothMonotonicAndPositive() {
        var last = 0f
        var lastStep = 0f
        for (i in -2000..2000) {
            val s = RulerMath.scale(1f, i.toFloat())
            assertTrue(s > 0f)
            assertTrue("travel $i", s > last)
            if (i > -2000) {
                val step = s - last
                if (lastStep > 0f) assertTrue("travel $i: $step vs $lastStep", step / lastStep in 0.97f..1.03f)
                lastStep = step
            }
            last = s
        }
    }

    @Test fun rulersWorkLikeATapeMeasureSwipingLeftGrows() {
        val left = RulerMath.travel(-360f, 2.625f)
        val right = RulerMath.travel(360f, 2.625f)
        assertTrue(left > 0f && right < 0f)
        assertEquals(360f / 2.625f, left, 1e-3f)
        assertTrue(RulerMath.scale(2f, left) > 2f)
        assertTrue(RulerMath.scale(2f, right) < 2f)
        assertTrue(step(Kind.Number, 25f, left, 0) > 25f)
        assertTrue(step(Kind.Angle, 0f, left, 1) > 0f)
        assertTrue(step(Kind.Point, 0f, right, 1) < 0f)
    }

    @Test fun shapeRulersMoveFromTheValueAtTheStart() {
        assertEquals(150f, step(Kind.Point, 100f, 50f, 1), 0f)
        assertEquals(125f, step(Kind.Angle, 100f, 50f, 1), 0f)
        val once = step(Kind.Number, 400f, 100f, 1)
        assertTrue(once in 480f..500f)
        assertEquals(400f, step(Kind.Number, 400f, 0f, 1), 0f)
        assertEquals(0.05f, step(Kind.Number, 0f, 1f, 1), 1e-6f)
        assertEquals(-0.05f, step(Kind.Number, 0f, -1f, 1), 1e-6f)
        assertEquals(step(Kind.Size, 400f, 200f, 1) - 400f, 2 * (once - 400f), 0.01f)
    }
}
