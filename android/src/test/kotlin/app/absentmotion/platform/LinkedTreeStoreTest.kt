package app.absentmotion.platform

import app.absentmotion.platform.LinkedTreeStore.ChildEntry
import app.absentmotion.platform.LinkedTreeStore.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedTreeStoreTest {
    @Test fun treeListEncodesAndDecodesCleanly() {
        val raw = "\n  content://a/tree/1  \n\ncontent://b/tree/2\ncontent://a/tree/1\n"
        val decoded = LinkedTreeStore.decode(raw)
        assertEquals(listOf("content://a/tree/1", "content://b/tree/2"), decoded)
        assertEquals("content://a/tree/1\ncontent://b/tree/2", LinkedTreeStore.encode(decoded))
        assertEquals(emptyList<String>(), LinkedTreeStore.decode(null))
        assertEquals(emptyList<String>(), LinkedTreeStore.decode("   \n  "))
    }

    @Test fun addingAndRemovingTreesDeduplicatesAndPreservesOrder() {
        val first = LinkedTreeStore.withTree(emptyList(), "content://tree/a")
        val second = LinkedTreeStore.withTree(first, "content://tree/b")
        assertEquals(listOf("content://tree/b", "content://tree/a"), second)
        val readded = LinkedTreeStore.withTree(second, "content://tree/a")
        assertEquals(listOf("content://tree/a", "content://tree/b"), readded)
        assertEquals(listOf("content://tree/a", "content://tree/b"), LinkedTreeStore.withTree(readded, "  "))
        assertEquals(listOf("content://tree/b"), LinkedTreeStore.withoutTree(readded, "content://tree/a"))
    }

    @Test fun mimeAndHiddenFiltersClassifyCorrectly() {
        assertEquals(MediaKind.Video, LinkedTreeStore.classifyMime("video/mp4"))
        assertEquals(MediaKind.Image, LinkedTreeStore.classifyMime("image/png"))
        assertEquals(MediaKind.Audio, LinkedTreeStore.classifyMime("audio/mpeg"))
        assertEquals(MediaKind.Audio, LinkedTreeStore.classifyMime("application/ogg"))
        assertEquals(MediaKind.Audio, LinkedTreeStore.classifyMime("application/x-flac"))
        assertNull(LinkedTreeStore.classifyMime(LinkedTreeStore.DIRECTORY_MIME))
        assertNull(LinkedTreeStore.classifyMime("application/octet-stream"))
        assertNull(LinkedTreeStore.classifyMime(null))
        assertTrue(LinkedTreeStore.isHidden(".DS_Store"))
        assertTrue(LinkedTreeStore.isHidden(".thumbnails"))
        assertFalse(LinkedTreeStore.isHidden("clip.mp4"))
        assertTrue(LinkedTreeStore.isSvg("image/svg+xml", "icon.bin"))
        assertTrue(LinkedTreeStore.isSvg("image/png", "logo.SVG"))
        assertFalse(LinkedTreeStore.isSvg("image/png", "photo.png"))
        assertEquals(1700L, LinkedTreeStore.secondsOf(1700_999L))
        assertEquals(0L, LinkedTreeStore.secondsOf(-1L))
    }

    @Test fun traversalEnforcesDepthHiddenZeroSizeAndUnpluggedFolders() {
        val dir = LinkedTreeStore.DIRECTORY_MIME
        val tree = mapOf(
            "root" to listOf(
                ChildEntry("root/clip0.mp4", "clip0.mp4", "video/mp4", 1000L, 120L),
                ChildEntry("root/empty.mp4", "empty.mp4", "video/mp4", 1000L, 0L),
                ChildEntry("root/unknown-size.mp3", "unknown-size.mp3", "audio/mpeg", 2000L, -1L),
                ChildEntry("root/.hidden.jpg", ".hidden.jpg", "image/jpeg", 1000L, 50L),
                ChildEntry("root/.Trash", ".Trash", dir, 1000L, 0L),
                ChildEntry("root/PS5", "PS5", dir, 1000L, 0L),
            ),
            "root/.Trash" to listOf(
                ChildEntry("root/.Trash/trashed.mp4", "trashed.mp4", "video/mp4", 1000L, 50L),
            ),
            "root/PS5" to listOf(
                ChildEntry("root/PS5/CREATE", "CREATE", dir, 1000L, 0L),
            ),
            "root/PS5/CREATE" to listOf(
                ChildEntry("root/PS5/CREATE/Clips", "Clips", dir, 1000L, 0L),
            ),
            "root/PS5/CREATE/Clips" to listOf(
                ChildEntry("root/PS5/CREATE/Clips/GTA6", "GTA6", dir, 1000L, 0L),
                ChildEntry("root/PS5/CREATE/Clips/cover.png", "cover.png", "image/png", 1000L, 42L),
            ),
            "root/PS5/CREATE/Clips/GTA6" to listOf(
                ChildEntry("root/PS5/CREATE/Clips/GTA6/trailer.mp4", "trailer.mp4", "video/mp4", 3000L, 999L),
                ChildEntry("root/PS5/CREATE/Clips/GTA6/TooDeep", "TooDeep", dir, 1000L, 0L),
            ),
            "root/PS5/CREATE/Clips/GTA6/TooDeep" to listOf(
                ChildEntry("root/PS5/CREATE/Clips/GTA6/TooDeep/ignored.mp4", "ignored.mp4", "video/mp4", 3000L, 999L),
            ),
        )
        val found = LinkedTreeStore.walk("root") { tree[it] }
        assertEquals(listOf("clip0.mp4", "unknown-size.mp3", "cover.png", "trailer.mp4"), found.map { it.name })
        assertEquals(emptyList<LinkedTreeStore.ScannedEntry>(), LinkedTreeStore.walk("unplugged") { null })
    }

    @Test fun traversalStopsOnCyclesAndRespectsFileCap() {
        val dir = LinkedTreeStore.DIRECTORY_MIME
        val cyclic = mapOf(
            "root" to listOf(
                ChildEntry("sub", "sub", dir, 0L, 0L),
                ChildEntry("a.jpg", "a.jpg", "image/jpeg", 0L, 10L),
                ChildEntry("b.jpg", "b.jpg", "image/jpeg", 0L, 10L),
            ),
            "sub" to listOf(
                ChildEntry("root", "root", dir, 0L, 0L),
                ChildEntry("c.jpg", "c.jpg", "image/jpeg", 0L, 10L),
            ),
        )
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), LinkedTreeStore.walk("root") { cyclic[it] }.map { it.name })
        assertEquals(listOf("a.jpg", "b.jpg"), LinkedTreeStore.walk("root", maxFiles = 2) { cyclic[it] }.map { it.name })
    }
}
