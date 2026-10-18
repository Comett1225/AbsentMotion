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
}
