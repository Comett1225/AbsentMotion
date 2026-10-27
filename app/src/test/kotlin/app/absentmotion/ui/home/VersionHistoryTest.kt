package app.absentmotion.ui.home

import app.absentmotion.R
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionHistoryTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun phonePortraitFillsTheWidthWithAMarginAndKeepsAHeightCap() {
        assertEquals(328f, VersionPanel.width(360f), 0.01f)
        assertEquals(720f, VersionPanel.height(900f), 0.01f)
        assertEquals(552f, VersionPanel.height(600f), 0.01f)
    }

    @Test fun tabletsKeepAReadableColumn() {
        assertEquals(560f, VersionPanel.width(1280f), 0.01f)
        assertEquals(560f, VersionPanel.width(800f), 0.01f)
        assertEquals(420f, VersionPanel.width(1280f, VersionPanel.CONFIRM_WIDTH), 0.01f)
        assertEquals(720f, VersionPanel.height(1600f), 0.01f)
    }

    @Test fun shortLandscapeScreensUseAlmostTheWholeHeight() {
        assertEquals(336f, VersionPanel.height(360f), 0.01f)
        assertEquals(560f, VersionPanel.width(800f), 0.01f)
    }

    @Test fun tinyAndUnboundedWindowsNeverGoNegativeOrInfinite() {
        assertEquals(0f, VersionPanel.width(20f), 0.01f)
        assertEquals(0f, VersionPanel.height(10f), 0.01f)
        assertEquals(560f, VersionPanel.width(Float.POSITIVE_INFINITY), 0.01f)
        assertEquals(720f, VersionPanel.height(Float.POSITIVE_INFINITY), 0.01f)
    }

    @Test fun thumbnailsGrowOnlyWhereThereIsRoom() {
        assertEquals(64f, VersionPanel.thumb(328f), 0.01f)
        assertEquals(72f, VersionPanel.thumb(560f), 0.01f)
    }

    @Test fun theRestoreButtonMovesBelowTheTextWhenLargeFontsLeaveNoRoom() {
        assertFalse(VersionPanel.stacked(328f, 1f))
        assertFalse(VersionPanel.stacked(560f, 1.3f))
        assertTrue(VersionPanel.stacked(328f, 1.3f))
        assertTrue(VersionPanel.stacked(328f, 2f))
        assertTrue(VersionPanel.stacked(240f, 1f))
        assertFalse(VersionPanel.stacked(328f, 0.8f))
    }

    @Test fun versionLabelsSayTodayYesterdayOrTheDate() {
        val now = at(2026, 10, 26, 18, 0)
        assertEquals(R.string.safety_version_today, versionLabel(at(2026, 10, 26, 9, 5), now).id)
        assertEquals(listOf<Any>("09:05"), versionLabel(at(2026, 10, 26, 9, 5), now).args)
        assertEquals(R.string.safety_version_yesterday, versionLabel(at(2026, 10, 25, 23, 59), now).id)
        val older = versionLabel(at(2026, 10, 20, 14, 32), now)
        assertEquals(R.string.safety_version_on_date, older.id)
        assertEquals("14:32", older.args[1])
    }

    @Test fun closedTextUsesTheSameMomentRules() {
        val now = at(2026, 10, 26, 18, 0)
        assertEquals(R.string.crash_closed_today, closedText(at(2026, 10, 26, 1, 0), now).id)
        assertEquals(R.string.crash_closed_yesterday, closedText(at(2026, 10, 25, 1, 0), now).id)
        assertEquals(R.string.crash_closed_on_date, closedText(at(2026, 9, 1, 1, 0), now).id)
    }
}
