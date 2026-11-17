package app.absentmotion.ai

import app.absentmotion.ui.editor.effects.compareFractions
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhanceCompareTest {
    @Test fun dividerShowsOriginalOnTheLeftAndResultOnTheRight() {
        assertEquals(0f to 1f, compareFractions(0f))
        assertEquals(0.5f to 0.5f, compareFractions(0.5f))
        assertEquals(1f to 0f, compareFractions(1f))
        assertEquals(0f to 1f, compareFractions(-1f))
        assertEquals(1f to 0f, compareFractions(2f))
    }
}
