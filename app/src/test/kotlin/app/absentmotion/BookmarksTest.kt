package app.absentmotion

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.editor.hasBookmarkNear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {
    private val base = Scene.create("t", 1080, 1080, 30, 0).let {
        Scene(it.node.copy(children = listOf(XmlNode("media", mapOf("uri" to "a")), XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "999")))))
    }

    @Test
    fun toggleAddsAtTheFrameStartAndRemovesAgain() {
        val on = base.withBookmarkToggled(15)
        assertEquals(listOf(500), on.bookmarks)
        assertEquals(listOf("media", "bookmark", "shape"), on.node.children.map { it.name })
        assertEquals("500", on.node.children[1]["t"])
        assertEquals(emptyList<Int>(), on.withBookmarkToggled(15).bookmarks)
    }

    @Test
    fun toggleRemovesABookmarkAnywhereInTheFrame() {
        val file = base.withBookmarks(listOf(512, 2000))
        assertEquals(listOf(2000), file.withBookmarkToggled(15).bookmarks)
    }

    @Test
    fun mediaAddedLaterStaysBeforeTheBookmarks() {
        val next = base.withBookmarkToggled(3).withMedia(MediaDeclaration(XmlNode("media", mapOf("uri" to "b"))))
        assertEquals(listOf("media", "media", "bookmark", "shape"), next.node.children.map { it.name })
    }

    @Test
    fun aBeatNextToAnExistingMarkIsSkippedAndOneFurtherAwayIsKept() {
        val marks = listOf(100, 500, 900)
        assertTrue(hasBookmarkNear(marks, 500, 33.3))
        assertTrue(hasBookmarkNear(marks, 520, 33.3))
        assertTrue(hasBookmarkNear(marks, 480, 33.3))
        assertFalse(hasBookmarkNear(marks, 540, 33.3))
        assertFalse(hasBookmarkNear(marks, 40, 33.3))
        assertTrue(hasBookmarkNear(marks, 930, 33.3))
        assertFalse(hasBookmarkNear(emptyList(), 10, 33.3))
    }

    @Test
    fun thousandsOfBeatsOnABusyTimelineAreAddedOnceEachAndFast() {
        val busy = base.withBookmarks((0 until 3000).map { it * 40 })
        val beats = (0 until 3000).map { it * 40 + 20 }
        val start = System.nanoTime()
        val next = busy.withBookmarks(beats)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(6000, next.bookmarks.size)
        val stored = next.node.children.filter { it.name == "bookmark" }.map { it["t"]!!.toInt() }
        assertEquals(stored.sorted(), stored)
        assertTrue("took $ms ms", ms < 1000)
        assertEquals(6000, next.withBookmarks(beats).bookmarks.size)
    }
}
