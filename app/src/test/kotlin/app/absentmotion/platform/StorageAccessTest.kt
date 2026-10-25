package app.absentmotion.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    @Test fun androidNineAsksAndRunsTheActionOnlyWhenAllowed() {
        var asked = 0
        var ran = 0
        var explained = 0
        var held = false
        val access = StorageAccess(28, { held }, { asked++ }, { explained++ })
        assertFalse(access.ready { ran++ })
        assertEquals(1, asked)
        access.answered(true)
        assertEquals(1, ran)
        access.answered(true)
        assertEquals(1, ran)
        held = true
        assertTrue(access.ready { ran++ })
        assertEquals(1, asked)
        assertEquals(0, explained)
    }

    @Test fun aDeniedPermissionExplainsInsteadOfRunningAndIsAskedAgainNextTime() {
        var asked = 0
        var ran = 0
        var explained = 0
        val access = StorageAccess(28, { false }, { asked++ }, { explained++ })
        assertFalse(access.ready { ran++ })
        access.answered(false)
        assertEquals(0, ran)
        assertEquals(1, explained)
        assertFalse(access.ready { ran++ })
        assertEquals(2, asked)
        access.answered(false)
        assertEquals(0, ran)
        assertEquals(2, explained)
    }

    @Test fun nothingIsAskedWhenThePermissionIsHeldOrTheSystemNeedsNone() {
        var asked = 0
        assertTrue(StorageAccess(28, { true }, { asked++ }).ready { })
        assertTrue(StorageAccess(29, { false }, { asked++ }).ready { })
        assertTrue(StorageAccess(36, { false }, { asked++ }).ready { })
        assertEquals(0, asked)
    }
}
