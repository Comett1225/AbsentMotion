package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyAskTest {
    private class Phone(val sdk: Int, var granted: Boolean = false) {
        var asked = false
        var dialogs = 0
        val ask = NotifyAsk(sdk, { granted }, { asked }, { asked = true }, { dialogs++ })
    }

    @Test fun theNotificationPermissionIsAskedOnceAtTheFirstExportAndTheExportGoesOnEitherWay() {
        val phone = Phone(33)
        var started = 0
        assertFalse(phone.ask.ready { started++ })
        assertEquals(1, phone.dialogs)
        phone.ask.answered()
        assertEquals(1, started)
        assertTrue(phone.ask.ready { started++ })
        phone.ask.answered()
        assertEquals(1, phone.dialogs)
        assertEquals(1, started)
    }

    @Test fun olderAndroidAndAGrantedPermissionNeverAsk() {
        val old = Phone(32)
        assertTrue(old.ask.ready {})
        val granted = Phone(36, granted = true)
        assertTrue(granted.ask.ready {})
        assertEquals(0, old.dialogs + granted.dialogs)
        assertFalse(old.asked || granted.asked)
    }
}
