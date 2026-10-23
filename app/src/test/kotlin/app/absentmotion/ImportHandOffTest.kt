package app.absentmotion

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportHandOffTest {
    @Test fun whatIsGivenIsTakenOnceInOrder() {
        ImportHandOff.take()
        assertEquals(emptyList<String>(), ImportHandOff.take())
        ImportHandOff.give(listOf("content://a/1", "content://a/2"))
        ImportHandOff.give(listOf("content://b/3"))
        assertEquals(listOf("content://a/1", "content://a/2", "content://b/3"), ImportHandOff.take())
        assertEquals(emptyList<String>(), ImportHandOff.take())
    }

    @Test fun aShareWithoutAReadGrantStillOpensTheAppWithoutTheGrant() {
        ImportHandOff.take()
        val launches = ArrayList<Boolean>()
        ImportHandOff.start(listOf("content://a/1")) { grant ->
            launches += grant
            if (grant) throw SecurityException("no permission to content://a/1")
        }
        assertEquals(listOf(true, false), launches)
        assertEquals(listOf("content://a/1"), ImportHandOff.take())
    }

    @Test fun aShareWithAReadGrantOpensTheAppOnce() {
        ImportHandOff.take()
        val launches = ArrayList<Boolean>()
        ImportHandOff.start(listOf("content://a/2")) { launches += it }
        assertEquals(listOf(true), launches)
        assertEquals(listOf("content://a/2"), ImportHandOff.take())
    }
}
