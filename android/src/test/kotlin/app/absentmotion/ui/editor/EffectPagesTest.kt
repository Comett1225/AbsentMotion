package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.effects.EffectPages
import app.absentmotion.ui.editor.panels.CardMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectPagesTest {
    @Test fun shortOrSteepMovesAreNotPageSwipes() {
        assertNull(EffectPages.swiped(-40f, 0f, 48f))
        assertNull(EffectPages.swiped(-60f, 50f, 48f))
        assertEquals(-1, EffectPages.swiped(-60f, 10f, 48f))
        assertEquals(1, EffectPages.swiped(60f, -10f, 48f))
    }

    @Test fun swipingLeftOpensFavouritesAndMirrorsInRightToLeft() {
        assertTrue(EffectPages.target(-1, rtl = false))
        assertFalse(EffectPages.target(1, rtl = false))
        assertFalse(EffectPages.target(-1, rtl = true))
        assertTrue(EffectPages.target(1, rtl = true))
    }

    @Test fun holdingAndSlidingRightPicksFavouritesLeftPicksRecent() {
        assertTrue(EffectPages.scrub(false, 20f, 12f, rtl = false))
        assertFalse(EffectPages.scrub(true, -20f, 12f, rtl = false))
        assertTrue(EffectPages.scrub(true, 5f, 12f, rtl = false))
        assertFalse(EffectPages.scrub(false, -5f, 12f, rtl = false))
        assertFalse(EffectPages.scrub(true, 20f, 12f, rtl = true))
        assertTrue(EffectPages.scrub(false, -20f, 12f, rtl = true))
    }

    @Test fun theOpenCardStaysInPlaceAndOthersMoveAwayFromIt() {
        assertEquals(2, CardMotion.focus(-1, 2))
        assertEquals(2, CardMotion.focus(2, -1))
        assertEquals(-1, CardMotion.away(0, 2))
        assertEquals(1, CardMotion.away(3, 2))
        assertEquals(-1, CardMotion.away(-1, 0))
        assertEquals(1, CardMotion.away(Int.MAX_VALUE, 5))
    }
}
