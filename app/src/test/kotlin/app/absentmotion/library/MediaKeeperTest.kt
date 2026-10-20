package app.absentmotion.library

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mediaUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaKeeperTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun media(file: File, uri: String = file.toURI().toString()) =
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri, "filename" to file.name, "size" to "${file.length()}", "type" to "image/png")))

    private fun scene(vararg uris: String) = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"),
        uris.mapIndexed { i, uri -> XmlNode("shape", linkedMapOf("id" to "${i + 1}", "fillImage" to uri)) }))

    @Test fun copiesIntoTheProjectFolderSoTheOriginalCanGo() {
        val store = ProjectStore(tmp.newFolder())
        val photo = tmp.newFile("photo.png").apply { writeText("pixels") }
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        val kept = keeper.keep("p1", media(photo))
        assertTrue(store.inMediaFolder(kept.uri))
        photo.delete()
        assertEquals("pixels", File(java.net.URI(kept.uri)).readText())
        assertEquals(kept.uri, keeper.keep("p1", kept).uri)
        val again = tmp.newFile("photo.png").apply { writeText("pixels") }
        val scene = scene(kept.uri).withMedia(kept)
        assertEquals(kept.uri, keeper.keep("p1", media(again), scene).uri)
        assertEquals(1, store.mediaDir("p1").listFiles()!!.size)
    }

    @Test fun refusesWhenStorageIsLow() {
        val store = ProjectStore(tmp.newFolder())
        val photo = tmp.newFile("big.png").apply { writeText("x") }
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() }, free = { MediaKeeper.RESERVE })
        try {
            keeper.keep("p1", media(photo))
            fail()
        } catch (e: MediaKeeper.NoSpace) {
            assertTrue(e.needed > MediaKeeper.RESERVE)
        }
        assertFalse(store.mediaDir("p1").listFiles().orEmpty().any { it.isFile })
    }

    @Test fun collectCopiesEverythingItCanReadAndCountsTheRest() {
        val store = ProjectStore(tmp.newFolder())
        val a = tmp.newFile("a.png").apply { writeText("a") }
        val b = tmp.newFile("b.png").apply { writeText("b") }
        val gone = File(tmp.root, "gone.png")
        val scene = scene(a.toURI().toString(), b.toURI().toString(), gone.toURI().toString())
            .withMedia(media(a)).withMedia(media(b)).withMedia(MediaDeclaration(XmlNode("media", linkedMapOf("uri" to gone.toURI().toString(), "filename" to "gone.png"))))
        val result = MediaKeeper(store, { File(java.net.URI(it)).inputStream() }).collect("p1", scene)
        assertEquals(2, result.copied)
        assertEquals(1, result.unreadable)
        assertFalse(result.noSpace)
        val uris = result.scene.node.mediaUris()
        assertEquals(2, uris.count(store::inMediaFolder))
        assertTrue(gone.toURI().toString() in uris)
        assertEquals(setOf("a.png", "b.png"), store.mediaDir("p1").listFiles()!!.map { it.name }.toSet())
    }
}
