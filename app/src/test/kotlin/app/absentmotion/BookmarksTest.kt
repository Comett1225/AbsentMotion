package app.absentmotion

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
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
}
