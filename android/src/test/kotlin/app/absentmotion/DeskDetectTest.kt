package app.absentmotion

import app.absentmotion.platform.DeskDetect
import app.absentmotion.platform.DisplayFacts
import app.absentmotion.ui.adaptive.DeskLayout
import app.absentmotion.ui.adaptive.LayoutChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskDetectTest {
    class DexConfig {
        @JvmField var semDesktopModeEnabled = 0
        companion object { @JvmField val SEM_DESKTOP_MODE_ENABLED = 1 }
    }

    class PlainConfig

    class WindowConfig { fun getWindowingMode() = 5 }

    class FreeformConfig { @JvmField val windowConfiguration = WindowConfig() }

    @After
    fun reset() {
        DeskLayout.choice = LayoutChoice.Auto
        DeskLayout.detected = false
    }

    @Test
    fun dexFlagFollowsTheStaticConstant() {
        val config = DexConfig()
        assertFalse(DeskDetect.dexOn(DexConfig::class.java, config))
        config.semDesktopModeEnabled = 1
        assertTrue(DeskDetect.dexOn(DexConfig::class.java, config))
    }

    @Test
    fun missingReflectionFieldsNeverThrow() {
        assertFalse(DeskDetect.dexOn(PlainConfig::class.java, PlainConfig()))
        assertFalse(DeskDetect.freeform(PlainConfig()))
        assertTrue(DeskDetect.freeform(FreeformConfig()))
    }

    @Test
    fun phoneAloneIsNotDesktop() {
        assertFalse(DeskDetect.wanted(DisplayFacts(widthDp = 400)))
    }

    @Test
    fun dexDeskModeAndExternalDisplayAreDesktop() {
        assertTrue(DeskDetect.wanted(DisplayFacts(dex = true)))
        assertTrue(DeskDetect.wanted(DisplayFacts(deskMode = true)))
        assertTrue(DeskDetect.wanted(DisplayFacts(externalDisplay = true)))
    }

    @Test
    fun freeformNeedsWidthAndMouse() {
        assertTrue(DeskDetect.wanted(DisplayFacts(freeform = true, widthDp = 900, finePointer = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(freeform = true, widthDp = 899, finePointer = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(freeform = true, widthDp = 1200, finePointer = false)))
        assertFalse(DeskDetect.wanted(DisplayFacts(freeform = false, widthDp = 1200, finePointer = true)))
    }

    @Test
    fun settingDecidesTheLayout() {
        for (detected in listOf(false, true)) {
            assertEquals(detected, DeskLayout.resolve(false, LayoutChoice.Auto, detected))
            assertTrue(DeskLayout.resolve(false, LayoutChoice.On, detected))
            assertFalse(DeskLayout.resolve(false, LayoutChoice.Off, detected))
            for (choice in LayoutChoice.entries) assertTrue(DeskLayout.resolve(true, choice, detected))
        }
    }

    @Test
    fun layoutFlagReactsToSettingAndDetection() {
        assertFalse(DeskLayout.on)
        DeskLayout.detected = true
        assertTrue(DeskLayout.on)
        DeskLayout.choice = LayoutChoice.Off
        assertFalse(DeskLayout.on)
        DeskLayout.choice = LayoutChoice.On
        DeskLayout.detected = false
        assertTrue(DeskLayout.on)
    }

    @Test
    fun savedChoiceParsesAndDefaultsToAuto() {
        assertEquals(LayoutChoice.On, LayoutChoice.of("On"))
        assertEquals(LayoutChoice.Off, LayoutChoice.of("Off"))
        assertEquals(LayoutChoice.Auto, LayoutChoice.of(null))
        assertEquals(LayoutChoice.Auto, LayoutChoice.of("nonsense"))
    }
}
