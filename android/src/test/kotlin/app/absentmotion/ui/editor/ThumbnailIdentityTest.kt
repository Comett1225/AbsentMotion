package app.absentmotion.ui.editor

import app.absentmotion.media.pictureIdentity
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailIdentityTest {
    @Test fun aSourceIdentityNeedsEveryPictureToBeInstalled() {
        val installed = mapOf("a" to "1@0", "b" to "7@0")
        assertEquals("1@0|7@0", sourceIdentity(setOf("b", "a"), installed))
        assertNull(sourceIdentity(setOf("a", "c"), installed))
        assertEquals("", sourceIdentity(emptySet(), installed))
    }

    @Test fun replacingTheFileGivesAnotherIdentityAndTheOldPicturesAreNotReused() {
        val cache = BoundedCache<Pair<String, String?>, String>(8)
        val before = sourceIdentity(setOf("a"), mapOf("a" to "1@0"))
        cache[("shape" to before)] = "old pixels"
        val replaced = sourceIdentity(setOf("a"), mapOf("a" to "2@0"))
        assertNull(cache["shape" to replaced])
        assertEquals("old pixels", cache["shape" to before])
        assertNull(cache["shape" to sourceIdentity(setOf("a"), emptyMap())])
    }

    @Test fun aResultOfAnOlderRequestIsNeverShownForTheCurrentOne() {
        val first = Stamped("first", "pixels")
        assertEquals("pixels", first.of("first"))
        assertNull(first.of("second"))
        assertNull((null as Stamped<String, String>?).of("first"))
        assertNull(Stamped("first", null as String?).of("first"))
    }

    @Test fun theCacheKeepsTheMostRecentlyUsedEntriesAndStaysBounded() {
        val cache = BoundedCache<Int, String>(3)
        (1..3).forEach { cache[it] = "v$it" }
        cache[1]
        cache[4] = "v4"
        assertNull(cache[2])
        assertEquals("v1", cache[1])
        assertEquals(3, cache.size())
        (5..40).forEach { cache[it] = "v$it" }
        assertEquals(3, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test fun reEnteringTilesAreSeededOnlyFromFramesOfTheSameVersion() {
        val frames = BoundedCache<FrameKey, String>(16)
        val tiles = listOf(1000 to 10L, 1400 to 20L, 1800 to 30L)
        frames[FrameKey("v", "v1", 10L)] = "a"
        frames[FrameKey("v", "v1", 30L)] = "c"
        frames[FrameKey("v", "v2", 20L)] = "new"
        assertEquals(listOf(1000 to "a", 1800 to "c"), cachedTiles(tiles) { frames[FrameKey("v", "v1", it)] })
        assertEquals(listOf(1400 to "new"), cachedTiles(tiles) { frames[FrameKey("v", "v2", it)] })
        assertEquals(emptyList<Pair<Int, String>>(), cachedTiles(tiles) { frames[FrameKey("other", "v1", it)] })
    }

    @Test fun anUnreadableFileOrUnknownGenerationHasNoIdentity() {
        assertEquals("1:2:3:4.5@7.2", pictureIdentity("1:2:3:4.5", 2, 7))
        assertNull(pictureIdentity(null, 2, 7))
        assertNull(pictureIdentity("1:2:3:4.5", null, 7))
        assertEquals(false, pictureIdentity("v", 1, 7) == pictureIdentity("v", 2, 7))
        assertEquals(false, pictureIdentity("v", 0, 1) == pictureIdentity("v", 0, 2))
    }

    @Test fun videoVersionsAreOnlySeededWhileTrustedAndChangesReplaceTheOldVersion() {
        val start = SourceVersions().with("a", "v1")
        assertEquals("v1", start.seed("a"))
        val paused = start.copy(trusted = false)
        assertNull(paused.seed("a"))
        assertEquals("v1", paused.versions["a"])
        val replaced = paused.with("a", "v2").copy(trusted = true)
        assertEquals("v2", replaced.seed("a"))
        assertNull(replaced.with("a", null).seed("a"))
        assertEquals(true, start.with("a", "v1") === start)
    }

    @Test fun videoVersionsStayBoundedAndDropTheOldestFirst() {
        val many = (1..10).fold(SourceVersions()) { next, k -> next.with("u$k", "v$k", limit = 4) }
        assertEquals(listOf("u7", "u8", "u9", "u10"), many.versions.keys.toList())
        assertEquals(listOf("u8", "u9", "u10", "u7"), many.with("u7", "w", limit = 4).versions.keys.toList())
    }

    @Test fun textInsideAGroupCountsAsText() {
        val text = XmlNode("text", emptyMap(), emptyList())
        assertEquals(true, XmlNode("group", emptyMap(), listOf(XmlNode("group", emptyMap(), listOf(text)))).hasText())
        assertEquals(false, XmlNode("shape", emptyMap(), emptyList()).hasText())
    }
}
