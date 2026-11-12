package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarSlideTest {
    private fun at(x: Float, tools: Boolean = true, rtl: Boolean = false) = BarSlide.tab(x, 456f, 56f, tools, rtl)

    @Test fun eachQuarterMapsToItsTabInBarOrder() {
        assertEquals(0, at(50f))
        assertEquals(3, at(150f))
        assertEquals(1, at(306f))
        assertEquals(2, at(406f))
    }

    @Test fun thePlusInTheMiddleIsNeverATarget() {
        assertNull(at(200f))
        assertNull(at(228f))
        assertNull(at(256f))
    }

    @Test fun slidingPastTheEndsKeepsTheEdgeTab() {
        assertEquals(0, at(-80f))
        assertEquals(2, at(900f))
    }

    @Test fun withoutToolsTheLastSlotIsEmpty() {
        assertNull(at(406f, tools = false))
        assertEquals(1, at(306f, tools = false))
    }

    @Test fun rightToLeftMirrorsTheBar() {
        assertEquals(0, at(406f, rtl = true))
        assertEquals(3, at(306f, rtl = true))
        assertEquals(1, at(150f, rtl = true))
        assertEquals(2, at(50f, rtl = true))
    }

    @Test fun aBarTooNarrowForTabsPicksNothing() {
        assertNull(BarSlide.tab(10f, 40f, 56f, tools = true, rtl = false))
    }
}
