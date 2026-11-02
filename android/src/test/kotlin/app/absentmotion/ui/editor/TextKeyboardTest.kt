package app.absentmotion.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardTest {
    @Test fun theKeyboardShownForTheFocusedTextFieldIsWanted() {
        assertTrue(imeWanted(shown = false, visible = true, browsing = false, fieldFocused = true))
        assertTrue(imeWanted(shown = true, visible = true, browsing = false, fieldFocused = true))
    }

    @Test fun backWhileTheFieldIsFocusedMeansTheKeyboardIsNotWanted() {
        assertFalse(imeWanted(shown = true, visible = false, browsing = false, fieldFocused = true))
    }

    @Test fun focusTakenByAnotherControlKeepsTheWishEitherWay() {
        assertTrue(imeWanted(shown = true, visible = false, browsing = false, fieldFocused = false))
        assertFalse(imeWanted(shown = false, visible = true, browsing = false, fieldFocused = false))
    }

    @Test fun theFontBrowserSearchKeyboardNeverCountsForTheTextField() {
        assertFalse(imeWanted(shown = false, visible = true, browsing = true, fieldFocused = false))
        assertFalse(imeWanted(shown = false, visible = true, browsing = true, fieldFocused = true))
        assertTrue(imeWanted(shown = true, visible = false, browsing = true, fieldFocused = true))
    }

    @Test fun aFocusedFieldWithoutAScreenKeyboardDoesNotWantOne() {
        assertFalse(imeWanted(shown = false, visible = false, browsing = false, fieldFocused = true))
    }
}
