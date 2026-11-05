package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.effects.EffectSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectSwipeTest {
    @Test fun movesInsideTheSlopAreUndecided() {
        assertNull(EffectSwipe.direction(5f, 5f, 8f))
        assertNull(EffectSwipe.direction(-8f, 0f, 8f))
    }

    @Test fun onlyClearlyHorizontalMovesAreSwipes() {
        assertEquals(-1, EffectSwipe.direction(-12f, 4f, 8f))
        assertEquals(1, EffectSwipe.direction(12f, -4f, 8f))
        assertEquals(0, EffectSwipe.direction(3f, 12f, 8f))
        assertEquals(0, EffectSwipe.direction(9f, 9f, 8f))
    }

    @Test fun dragsFromTheScreenEdgesAreLeftToTheBackGesture() {
        assertTrue(EffectSwipe.fromEdge(10f, 1080f, 24f))
        assertTrue(EffectSwipe.fromEdge(1070f, 1080f, 24f))
        assertFalse(EffectSwipe.fromEdge(540f, 1080f, 24f))
    }

    @Test fun deleteNeedsFortyPercentOrAFling() {
        val fling = 800f
        assertFalse(EffectSwipe.commits(390f, 1000f, 0f, EffectSwipe.DELETE_SHARE, fling))
        assertTrue(EffectSwipe.commits(400f, 1000f, 0f, EffectSwipe.DELETE_SHARE, fling))
        assertTrue(EffectSwipe.commits(60f, 1000f, 900f, EffectSwipe.DELETE_SHARE, fling))
        assertFalse(EffectSwipe.commits(500f, 1000f, -900f, EffectSwipe.DELETE_SHARE, fling))
    }

    @Test fun pagesFollowTheHomeOrderAndStopAtTheEnds() {
        assertEquals(1, EffectSwipe.page(0, 3, -50f, rtl = false))
        assertEquals(1, EffectSwipe.page(2, 3, 50f, rtl = false))
        assertNull(EffectSwipe.page(0, 3, 50f, rtl = false))
        assertNull(EffectSwipe.page(2, 3, -50f, rtl = false))
        assertEquals(1, EffectSwipe.page(0, 3, 50f, rtl = true))
        assertNull(EffectSwipe.page(-1, 3, -50f, rtl = false))
    }
}
