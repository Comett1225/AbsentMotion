package app.absentmotion.pc

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskAppState
import app.absentmotion.export.ExportRun
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.FollowSystemTheme
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.ThemeFadeHost
import app.absentmotion.ui.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ThemeFollowTest {
    private fun fresh(name: String): DeskAppState {
        System.setProperty("am.data", File("build/$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.systemDark", "false")
        return DeskAppState()
    }

    @org.junit.After fun restore() {
        System.setProperty("am.systemDark", "false")
        Am.palette = Palette.AbsentMotion
    }

    @Test fun aNewInstallStartsOnSystemDefaultAndFollowsTheDevice() = runComposeUiTest {
        val settings = fresh("theme-follow").settings
        assertEquals(Themes.SYSTEM, settings.theme)
        assertEquals(Themes.SYSTEM, settings.themes.first().first)
        assertEquals(Palette.AbsentMotion, Am.palette)
        setContent { ThemeFadeHost { FollowSystemTheme(settings) } }
        System.setProperty("am.systemDark", "true")
        mainClock.advanceTimeBy(4000)
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        assertEquals(Palette.Dark, Am.palette)
        System.setProperty("am.systemDark", "false")
        mainClock.advanceTimeBy(4000)
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        assertEquals(Palette.AbsentMotion, Am.palette)
    }

    @Test fun theDeviceThemeWaitsForARunningExport() = runComposeUiTest {
        val app = fresh("theme-busy")
        val settings = app.settings
        setContent { ThemeFadeHost { FollowSystemTheme(settings) } }
        ExportRun.begin(app.context, R.string.export_running) {}
        try {
            System.setProperty("am.systemDark", "true")
            mainClock.advanceTimeBy(10000)
            waitForIdle()
            assertEquals(Palette.AbsentMotion, Am.palette)
        } finally {
            ExportRun.end(true)
        }
        mainClock.advanceTimeBy(2000)
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        assertEquals(Palette.Dark, Am.palette)
    }

    @Test fun aChosenThemeIgnoresTheDevice() = runComposeUiTest {
        val settings = fresh("theme-fixed").settings
        settings.theme = Themes.DEFAULT
        setContent { ThemeFadeHost { FollowSystemTheme(settings) } }
        System.setProperty("am.systemDark", "true")
        mainClock.advanceTimeBy(5000)
        waitForIdle()
        assertEquals(Palette.AbsentMotion, Am.palette)
    }
}
