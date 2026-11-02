package app.absentmotion.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureLoadTest {
    private val loaded = mutableMapOf<String, Int>()
    private val decoded = mutableListOf<String>()
    private val installed = mutableListOf<String>()
    private var readable = true
    private var accepted = true
    private var version = 1

    private fun load(vararg uris: String) = runBlocking {
        MediaLibrary.loadPictures(uris.asList(), loaded, { version }, { uri -> decoded += uri; if (readable) "$uri@$version" else null }) { _, picture ->
            installed += picture
            accepted
        }
    }

    @Test fun aPictureThatFailedToDecodeIsNotInstalledAndLoadsOnTheNextTry() {
        readable = false
        load("a")
        assertTrue(installed.isEmpty())
        assertTrue(loaded.isEmpty())
        readable = true
        load("a")
        assertEquals(listOf("a@1"), installed)
        assertEquals(mapOf("a" to 1), loaded)
    }

    @Test fun aPictureTheEngineRefusedIsTriedAgain() {
        accepted = false
        load("a")
        assertTrue(loaded.isEmpty())
        accepted = true
        load("a")
        assertEquals(listOf("a@1", "a@1"), installed)
        assertEquals(mapOf("a" to 1), loaded)
    }

    @Test fun aLoadedPictureIsNotDecodedAgainUntilItsVersionChanges() {
        load("a", "b")
        load("a", "b")
        assertEquals(listOf("a", "b"), decoded)
        version = 2
        load("a", "b")
        assertEquals(listOf("a@1", "b@1", "a@2", "b@2"), installed)
    }

    @Test fun picturesAreDecodedForTheProjectSizeNotTheLargestGpuTexture() {
        assertEquals(4096, pictureLimit(16384, 1920))
        assertEquals(7680, pictureLimit(16384, 3840))
        assertEquals(16384, pictureLimit(16384, 10000))
        assertEquals(4096, pictureLimit(16384, 0))
        assertEquals(2048, pictureLimit(2048, 1920))
    }

    @Test fun aPictureIsNeverDecodedAtMoreThanOneAndAHalfTimesItsLimit() {
        for (side in listOf(100, 4095, 4096, 6000, 6200, 8191, 8192, 12_000, 16_330, 30_000)) for (limit in listOf(1000, 4096, 4899, 7680)) {
            val sample = pictureSample(side, limit)
            val decoded = side / sample
            assertEquals(Integer.bitCount(sample), 1)
            assertTrue("$side / $limit -> $sample", decoded * 2 <= limit * 3)
            assertTrue("$side / $limit -> $sample", decoded * 4 >= minOf(side, limit) * 3 - 4)
        }
    }
}
