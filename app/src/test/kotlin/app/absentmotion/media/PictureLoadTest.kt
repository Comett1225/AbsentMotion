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
}
