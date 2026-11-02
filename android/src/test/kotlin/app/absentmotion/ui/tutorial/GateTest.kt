package app.absentmotion.ui.tutorial

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
    private val window = Area(0f, 0f, 1000f, 2000f)

    private fun blocked(allowed: List<Area>, denied: List<Area> = emptyList()) = GateMath.blocked(window, allowed, denied)

    private fun area(list: List<Area>) = list.sumOf { (it.width * it.height).toDouble() }

    private fun admits(list: List<Area>, x: Float, y: Float) = GateMath.admits(list, x, y)

    private fun sequence(list: List<Area>, path: List<Pair<Float, Float>>) = admits(list, path.first().first, path.first().second)

    @Test fun aHoleLeavesFourBlockersAndNothingOverlaps() {
        val hole = Area(200f, 800f, 700f, 1200f)
        val list = blocked(listOf(hole))
        assertEquals(4, list.size)
        assertEquals(area(listOf(window)) - area(listOf(hole)), area(list), 0.5)
        for (a in list) for (b in list) if (a !== b) assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
    }

    @Test fun onlyPointsInsideTheHoleAreAdmitted() {
        val list = blocked(listOf(Area(200f, 800f, 700f, 1200f)))
        assertTrue(admits(list, 200f, 800f))
        assertTrue(admits(list, 450f, 1000f))
        assertFalse(admits(list, 199f, 1000f))
        assertFalse(admits(list, 700f, 1000f))
        assertFalse(admits(list, 450f, 799f))
        assertFalse(admits(list, 450f, 1200f))
        assertFalse(admits(list, 5f, 5f))
    }

    @Test fun aMissingTargetBlocksTheWholeWindow() {
        assertEquals(listOf(window), blocked(emptyList()))
        assertEquals(listOf(window), blocked(listOf(Area(-50f, -50f, -10f, -10f))))
        assertEquals(listOf(window), blocked(listOf(Area(300f, 300f, 300f, 900f))))
        assertFalse(admits(blocked(emptyList()), 500f, 1000f))
    }

    @Test fun theGapBetweenTwoTargetsStaysBlocked() {
        val top = Area(0f, 100f, 1000f, 300f)
        val bottom = Area(0f, 600f, 1000f, 900f)
        val list = blocked(listOf(top, bottom))
        assertTrue(admits(list, 500f, 200f))
        assertTrue(admits(list, 500f, 700f))
        assertFalse(admits(list, 500f, 450f))
        assertFalse(admits(list, 500f, 50f))
        assertFalse(admits(list, 500f, 950f))
    }

    @Test fun touchingTargetsLeaveNoSeam() {
        val list = blocked(listOf(Area(0f, 1000f, 500f, 1200f), Area(500f, 1000f, 1000f, 1200f)))
        assertTrue(admits(list, 499.5f, 1100f))
        assertTrue(admits(list, 500f, 1100f))
        assertTrue(admits(list, 500.5f, 1100f))
        assertEquals(2, list.size)
    }

    @Test fun overlappingTargetsAreAdmittedOnce() {
        val list = blocked(listOf(Area(100f, 100f, 600f, 600f), Area(300f, 300f, 900f, 900f)))
        assertTrue(admits(list, 150f, 150f))
        assertTrue(admits(list, 450f, 450f))
        assertTrue(admits(list, 850f, 850f))
        assertFalse(admits(list, 850f, 150f))
        assertFalse(admits(list, 150f, 850f))
    }

    @Test fun deniedButtonsInsideATargetStayBlocked() {
        val preview = Area(0f, 100f, 1000f, 1000f)
        val button = Area(900f, 900f, 1000f, 1000f)
        val list = blocked(listOf(preview), listOf(button))
        assertTrue(admits(list, 500f, 500f))
        assertFalse(admits(list, 950f, 950f))
        assertTrue(admits(list, 899f, 999f))
    }

    @Test fun targetsOutsideTheWindowAreClippedToIt() {
        val list = blocked(listOf(Area(-100f, 1900f, 1200f, 2500f)))
        assertTrue(admits(list, 0f, 1999f))
        assertTrue(admits(list, 999f, 1950f))
        assertFalse(admits(list, 500f, 1899f))
        assertTrue(list.all { it.left >= 0f && it.right <= 1000f && it.top >= 0f && it.bottom <= 2000f })
    }

    @Test fun aGestureIsDecidedByWhereItsFirstFingerWentDown() {
        val list = blocked(listOf(Area(200f, 800f, 700f, 1200f)))
        assertTrue(sequence(list, listOf(300f to 900f, 600f to 1150f, 50f to 50f)))
        assertFalse(sequence(list, listOf(50f to 50f, 300f to 900f, 400f to 1000f)))
        assertTrue(sequence(list, listOf(250f to 850f, 650f to 1100f)))
    }

    @Test fun missingTargetsAreSkippedButTheRestStillWork() {
        Tutorial.place("a", this, Rect(100f, 100f, 300f, 300f))
        try {
            val found = listOf("a", "gone").flatMap(Tutorial::bounds)
            assertEquals(1, found.size)
            val list = blocked(found.map { Area(it.left, it.top, it.right, it.bottom) })
            assertTrue(admits(list, 200f, 200f))
            assertFalse(admits(list, 500f, 500f))
            assertEquals(emptyList<Rect>(), Tutorial.bounds("gone"))
            assertEquals(listOf(window), blocked(listOf("gone").flatMap(Tutorial::bounds).map { Area(it.left, it.top, it.right, it.bottom) }))
        } finally {
            Tutorial.forget("a", this)
        }
        assertEquals(emptyList<Rect>(), Tutorial.bounds("a"))
    }

    @Test fun regionsAreLookedUpLazilyAndDroppedWithTheirOwner() {
        var shift = 0f
        Tutorial.region("clip", this) { listOf(Rect(shift, 0f, shift + 10f, 10f), Rect(0f, 0f, 0f, 5f)) }
        try {
            assertEquals(listOf(Rect(0f, 0f, 10f, 10f)), Tutorial.bounds("clip"))
            shift = 40f
            assertEquals(listOf(Rect(40f, 0f, 50f, 10f)), Tutorial.bounds("clip"))
            Tutorial.forget("clip", Any())
            assertEquals(1, Tutorial.bounds("clip").size)
        } finally {
            Tutorial.forget("clip", this)
        }
        assertEquals(emptyList<Rect>(), Tutorial.bounds("clip"))
    }

    @Test fun semanticsAreHiddenOnlyForControlsOutsideTheHole() {
        val list = blocked(listOf(Area(200f, 800f, 700f, 1200f)))
        assertFalse(GateMath.shut(Area(300f, 900f, 400f, 1000f), list, false))
        assertTrue(GateMath.shut(Area(0f, 0f, 100f, 100f), list, false))
        assertTrue(GateMath.shut(Area(0f, 0f, 100f, 100f), list, true))
        assertFalse(GateMath.shut(Area(0f, 700f, 1000f, 900f), list, true))
    }

    @Test fun everythingIsHiddenWhenNothingIsAllowedAndNothingWhenFree() {
        assertTrue(GateMath.shut(Area(10f, 10f, 60f, 60f), blocked(emptyList()), false))
        assertTrue(GateMath.shut(Area(10f, 10f, 60f, 60f), blocked(emptyList()), true))
        assertFalse(GateMath.shut(Area(10f, 10f, 60f, 60f), emptyList(), true))
    }

    @Test fun deniedRectanglesHideTheirControlsInsideAnAllowedOne() {
        val list = blocked(listOf(Area(0f, 0f, 1000f, 1000f)), listOf(Area(0f, 800f, 200f, 1000f)))
        assertTrue(GateMath.shut(Area(20f, 820f, 120f, 920f), list, false))
        assertFalse(GateMath.shut(Area(500f, 400f, 600f, 500f), list, false))
    }
}
