package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    private val mb = 1L shl 20

    @Test fun neededSpaceIsTheEstimatePlusTenPercentAndAMargin() {
        assertEquals(110 * mb + MinFreeBytes, bytesNeeded(100 * mb))
        assertEquals(MinFreeBytes, bytesNeeded(-5))
    }

    @Test fun statusNamesTheShortfall() {
        val short = storageStatus(1000 * mb, 500 * mb) as StorageStatus.NotEnough
        assertTrue(short.message, short.message.contains("1.1GB") && short.message.contains("500.0MB"))
        assertTrue(storageStatus(100 * mb, 600 * mb) is StorageStatus.Low)
        assertEquals(StorageStatus.Enough, storageStatus(100 * mb, 4096 * mb))
    }
}
