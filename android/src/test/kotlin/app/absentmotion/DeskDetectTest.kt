package app.absentmotion

import app.absentmotion.platform.DeskDetect
import app.absentmotion.platform.DisplayFacts
import app.absentmotion.ui.adaptive.DeskLayout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskDetectTest {
    class DexConfig {
        @JvmField var semDesktopModeEnabled = 0
        companion object { @JvmField val SEM_DESKTOP_MODE_ENABLED = 1 }
    }

    class PlainConfig

    @After
    fun reset() {
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
    fun missingReflectionIsOff() {
        assertFalse(DeskDetect.dexOn(PlainConfig::class.java, PlainConfig()))
        assertFalse(DeskDetect.wanted(DisplayFacts(dex = DeskDetect.dexOn(PlainConfig::class.java, PlainConfig()), samsung = true)))
    }

    @Test
    fun dexOnAndOff() {
        assertTrue(DeskDetect.wanted(DisplayFacts(dex = true)))
        assertTrue(DeskDetect.wanted(DisplayFacts(dex = true, samsung = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(dex = false, samsung = true)))
    }

    @Test
    fun deskModeAndOtherDisplaysCountOnlyOnSamsung() {
        assertTrue(DeskDetect.wanted(DisplayFacts(samsung = true, deskMode = true)))
        assertTrue(DeskDetect.wanted(DisplayFacts(samsung = true, otherDisplay = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(deskMode = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(otherDisplay = true)))
        assertFalse(DeskDetect.wanted(DisplayFacts(deskMode = true, otherDisplay = true)))
    }

    @Test
    fun phonesTabletsAndFreeformWindowsAreNotDesk() {
        assertFalse(DeskDetect.wanted(DisplayFacts()))
        assertFalse(DeskDetect.wanted(DisplayFacts(samsung = true)))
    }

    @Test
    fun onlyWindowsAsBigAsTheComputerMinimumGetTheDeskLayout() {
        assertTrue(DeskDetect.roomy(820, 560))
        assertTrue(DeskDetect.roomy(1920, 1032))
        assertFalse(DeskDetect.roomy(380, 400))
        assertFalse(DeskDetect.roomy(640, 1000))
        assertFalse(DeskDetect.roomy(1280, 500))
    }

    @Test
    fun manufacturerMatchIgnoresCase() {
        assertTrue(DeskDetect.samsung("samsung"))
        assertTrue(DeskDetect.samsung("Samsung"))
        assertTrue(DeskDetect.samsung("SAMSUNG"))
        assertFalse(DeskDetect.samsung("Google"))
        assertFalse(DeskDetect.samsung(null))
    }

    @Test
    fun layoutFlagFollowsDetection() {
        assertFalse(DeskLayout.on)
        DeskLayout.detected = true
        assertTrue(DeskLayout.on)
        DeskLayout.detected = false
        assertFalse(DeskLayout.on)
    }
}
