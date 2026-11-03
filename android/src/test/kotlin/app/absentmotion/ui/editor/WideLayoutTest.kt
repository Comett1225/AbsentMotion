package app.absentmotion.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    @Test fun tabletsGetThePanelBesideThePreviewInPortraitFromSevenHundredSixtyEightDp() {
        assertTrue(tabletEditor(800, 800))
        assertTrue(tabletEditor(768, 1024))
        assertTrue(tabletEditor(600, 960))
        assertFalse(tabletEditor(600, 767))
        assertFalse(tabletEditor(411, 914))
        assertFalse(tabletEditor(599, 1280))
    }

    @Test fun sideToolsPickMoreColumnsWhenThePanelIsShort() {
        assertEquals(2, sideColumns(8, 400.dp))
        assertEquals(3, sideColumns(12, 344.dp))
        assertEquals(3, sideColumns(8, 260.dp))
        assertEquals(4, sideColumns(12, 260.dp))
        assertEquals(4, sideColumns(12, 100.dp))
        assertEquals(2, sideColumns(12, Dp.Infinity))
    }

    @Test fun theWideHomeHeaderNeedsRoomForItsTabsAndButtons() {
        assertTrue(app.absentmotion.ui.home.wideHeaderFits(800, 1f))
        assertTrue(app.absentmotion.ui.home.wideHeaderFits(1280, 1.5f))
        assertFalse(app.absentmotion.ui.home.wideHeaderFits(600, 1f))
        assertFalse(app.absentmotion.ui.home.wideHeaderFits(800, 1.5f))
    }

    @Test fun addPanelGridsKeepTwoRowsOnPhonesAndAddRowsWhenTaller() {
        val cells = AtLeastTwo(96.dp)
        fun rows(height: Int) = with(cells) { Density(1f).calculateCrossAxisCellSizes(height, 0) }
        assertEquals(listOf(78, 77), rows(155))
        assertEquals(listOf(100, 100, 100), rows(300))
        assertEquals(500, rows(500).sum())
        assertEquals(5, rows(500).size)
    }
}
