package app.absentmotion.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    @Test fun androidNineAsksOnceAndRunsTheActionAfterTheAnswer() {
        var asked = 0
        var ran = 0
        val access = StorageAccess(28, { false }, { asked++ })
        assertFalse(access.ready { ran++ })
        assertEquals(1, asked)
        assertEquals(0, ran)
        access.answered()
        assertEquals(1, ran)
        access.answered()
        assertEquals(1, ran)
        assertTrue(access.ready { ran++ })
        assertEquals(1, asked)
    }

    @Test fun nothingIsAskedWhenThePermissionIsHeldOrTheSystemNeedsNone() {
        var asked = 0
        assertTrue(StorageAccess(28, { true }, { asked++ }).ready { })
        assertTrue(StorageAccess(29, { false }, { asked++ }).ready { })
        assertTrue(StorageAccess(36, { false }, { asked++ }).ready { })
        assertEquals(0, asked)
    }
}
