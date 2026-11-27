package app.absentmotion

import app.absentmotion.platform.Peripherals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralsTest {
    @Test fun phonesStayPortraitUntilAKeyboardAndAMouseAreBothConnected() {
        assertFalse(Peripherals.landscapeFree(multiWindow = false, tablet = false, keyboard = false, mouse = false))
        assertFalse(Peripherals.landscapeFree(multiWindow = false, tablet = false, keyboard = true, mouse = false))
        assertFalse(Peripherals.landscapeFree(multiWindow = false, tablet = false, keyboard = false, mouse = true))
        assertTrue(Peripherals.landscapeFree(multiWindow = false, tablet = false, keyboard = true, mouse = true))
        assertTrue(Peripherals.landscapeFree(multiWindow = false, tablet = true, keyboard = false, mouse = false))
        assertTrue(Peripherals.landscapeFree(multiWindow = true, tablet = false, keyboard = false, mouse = false))
    }
}
