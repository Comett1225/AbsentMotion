package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSheetHeightTest {
    @Test fun portraitKeepsTheEditorVisibleAbove() {
        assertEquals(700f * 0.7f, captionSheetHeight(360f, 700f, false), 0.01f)
        assertEquals(560f * 0.7f, captionSheetHeight(360f, 560f, false), 0.01f)
    }

    @Test fun tallSheetsAreCapped() {
        assertEquals(640f, captionSheetHeight(800f, 1280f, false), 0f)
    }

    @Test fun shortLandscapeKeepsAUsableFloorBelowTheSafeHeight() {
        val height = captionSheetHeight(780f, 360f, false)
        assertEquals(320f, height, 0.01f)
        assertTrue(height >= 320f)
        assertTrue(height < 360f)
    }

    @Test fun extremelySmallAreasFillTheSafeHeight() {
        assertEquals(280f, captionSheetHeight(600f, 280f, false), 0f)
        assertEquals(200f, captionSheetHeight(300f, 200f, false), 0f)
    }

    @Test fun fontBrowserExpandsWithoutPassingTheSafeHeight() {
        assertEquals(700f * 0.92f, captionSheetHeight(360f, 700f, true), 0.01f)
        assertEquals(200f, captionSheetHeight(300f, 200f, true), 0f)
    }

    @Test fun degenerateSizesGiveNothing() {
        assertEquals(0f, captionSheetHeight(360f, 0f, false), 0f)
        assertEquals(0f, captionSheetHeight(360f, Float.NaN, false), 0f)
    }
}
