package app.absentmotion.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideLayoutTest {
    @Test fun tabletsStartAtSixHundredDpSmallestWidth() {
        assertFalse(isTablet(360))
        assertFalse(isTablet(411))
        assertFalse(isTablet(599))
        assertTrue(isTablet(600))
        assertTrue(isTablet(800))
    }

    @Test fun phonesNeverGetTheWideEditor() {
        assertFalse(wideEditor(411, 411))
        assertFalse(wideEditor(411, 914))
        assertFalse(wideEditor(599, 1200))
    }

    @Test fun tabletsUseTheWideEditorWhenThereIsRoomForPreviewAndPanel() {
        assertTrue(wideEditor(600, 960))
        assertTrue(wideEditor(800, 1280))
        assertTrue(wideEditor(673, 841))
        assertTrue(wideEditor(900, 900))
    }

    @Test fun narrowTabletWindowsKeepThePhoneLayout() {
        assertFalse(wideEditor(600, 600))
        assertFalse(wideEditor(800, 800))
        assertFalse(wideEditor(673, 839))
    }
}
