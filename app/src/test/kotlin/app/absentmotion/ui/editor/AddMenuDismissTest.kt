package app.absentmotion.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMenuDismissTest {
    private val height = 280f
    private val menu = 244f
    private val ruler = 42f

    @Test fun onlyTheStripAboveTheMenuDismisses() {
        assertTrue(TimelineMath.aboveAddMenu(0f, height, menu, ruler))
        assertTrue(TimelineMath.aboveAddMenu(20f, height, menu, ruler))
        assertTrue(TimelineMath.aboveAddMenu(height - menu - 0.5f, height, menu, ruler))
    }

    @Test fun theMenuItselfNeverDismisses() {
        assertFalse(TimelineMath.aboveAddMenu(height - menu, height, menu, ruler))
        assertFalse(TimelineMath.aboveAddMenu(height - 1f, height, menu, ruler))
    }

    @Test fun aTallTimelineOnlyDismissesFromTheRuler() {
        assertTrue(TimelineMath.aboveAddMenu(10f, 512f, menu, ruler))
        assertTrue(TimelineMath.aboveAddMenu(ruler - 0.5f, 512f, menu, ruler))
        assertFalse(TimelineMath.aboveAddMenu(ruler, 512f, menu, ruler))
        assertFalse(TimelineMath.aboveAddMenu(120f, 512f, menu, ruler))
    }

    @Test fun pointsOutsideTheTimelineDoNotDismiss() {
        assertFalse(TimelineMath.aboveAddMenu(-1f, height, menu, ruler))
        assertFalse(TimelineMath.aboveAddMenu(10f, 200f, menu, ruler))
    }
}
