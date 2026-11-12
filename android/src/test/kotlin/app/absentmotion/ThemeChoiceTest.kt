package app.absentmotion

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.absentmotion.platform.HapticRoute
import app.absentmotion.platform.hapticIsClick
import app.absentmotion.platform.hapticRoute
import app.absentmotion.ui.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeChoiceTest {
    @Test fun systemDefaultFollowsTheDevice() {
        assertEquals("Dark", Themes.resolve(Themes.SYSTEM, true))
        assertEquals(Themes.DEFAULT, Themes.resolve(Themes.SYSTEM, false))
    }

    @Test fun aChosenThemeIgnoresTheDevice() {
        for (id in listOf(Themes.DEFAULT, "Dark", "Daylight", Themes.CUSTOM)) {
            assertEquals(id, Themes.resolve(id, true))
            assertEquals(id, Themes.resolve(id, false))
        }
    }

    @Test fun onlyNewInstallsStartOnSystemDefault() {
        assertEquals(Themes.SYSTEM, Themes.firstChoice(null, 0, false))
        assertEquals(Themes.DEFAULT, Themes.firstChoice(null, 2, false))
        assertEquals(Themes.DEFAULT, Themes.firstChoice(null, 1, false))
        assertEquals(Themes.DEFAULT, Themes.firstChoice(null, 0, true))
        assertEquals("Dark", Themes.firstChoice("Dark", 0, false))
        assertEquals("Daylight", Themes.firstChoice("Daylight", 2, true))
    }

    @Test fun systemTouchFeedbackKeepsTheNormalPath() {
        assertEquals(HapticRoute.View, hapticRoute(appVibration = true, systemTouch = true))
        assertEquals(HapticRoute.View, hapticRoute(appVibration = false, systemTouch = true))
    }

    @Test fun vibratorIsUsedOnlyWhenTheAppSettingIsOnAndTheSystemOneIsOff() {
        assertEquals(HapticRoute.Vibrator, hapticRoute(appVibration = true, systemTouch = false))
        assertEquals(HapticRoute.None, hapticRoute(appVibration = false, systemTouch = false))
    }

    @Test fun longPressIsAClickAndTicksAreTicks() {
        assertTrue(hapticIsClick(HapticFeedbackType.LongPress))
        assertFalse(hapticIsClick(HapticFeedbackType.SegmentTick))
        assertFalse(hapticIsClick(HapticFeedbackType.SegmentFrequentTick))
        assertFalse(hapticIsClick(HapticFeedbackType.TextHandleMove))
    }
}
