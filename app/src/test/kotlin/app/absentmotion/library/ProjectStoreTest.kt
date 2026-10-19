package app.absentmotion.library

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ProjectStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scene(vararg layers: XmlNode) = Scene(XmlNode("scene", linkedMapOf("width" to "100", "height" to "100", "fps" to "30"), layers.toList()))
    private fun image(uri: String) = XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "99", "fillType" to "media", "fillImage" to uri))

    @Test fun deleteKeepsFilesAnotherProjectStillUses() {
        val store = ProjectStore(tmp.newFolder())
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        val used = store.mediaDir(a).resolve("used one.png").apply { writeText("x") }
        val unused = store.mediaDir(a).resolve("unused.png").apply { writeText("y") }
        val b = store.create(scene(image(used.toURI().toString())))
        store.delete(a)
        assertTrue(used.exists())
        assertFalse(unused.exists())
        store.delete(b)
        assertFalse(used.exists())
        assertFalse(store.mediaDir(a).exists())
    }

    @Test fun deleteRemovesOwnFolderWhenNothingElseUsesIt() {
        val store = ProjectStore(tmp.newFolder())
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        val file = store.mediaDir(a).resolve("a.png").apply { writeText("x") }
        store.delete(a)
        assertFalse(file.exists())
        assertFalse(store.mediaDir(a).exists())
    }

    @Test fun mediaUriLeavingTheMediaFolderIsNeverSwept() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val keep = root.resolve("fonts.txt").apply { writeText("keep") }
        val a = store.create(scene(image(root.resolve("media").toURI().toString() + "../fonts.txt")))
        store.delete(a)
        assertTrue(keep.exists())
    }

    @Test fun linkedElementInsideGroupFollowsTheOriginalAndBringsItsMedia() {
        val store = ProjectStore(tmp.newFolder())
        val element = Scene(scene().node.with("type", "element").with("title", "now"))
            .add(Layer(image("file:/new.png")), listOf(app.absentmotion.model.MediaDeclaration(XmlNode("media", mapOf("uri" to "file:/new.png", "orientation" to "90")))))
        val elementId = store.createElement(element)
        val linked = XmlNode("embedScene", linkedMapOf("id" to "2", "link" to store.linkOf(elementId)), listOf(XmlNode("scene", mapOf("title" to "old"))))
        val group = XmlNode("embedScene", linkedMapOf("id" to "3"), listOf(XmlNode("scene", mapOf("width" to "100", "height" to "100"), listOf(linked))))
        val id = store.create(scene(group))
        val loaded = store.load(id)!!
        val inner = loaded.layers.single().nested!!.layers.single().nested!!
        assertEquals("now", inner.title)
        assertEquals("90", loaded.media("file:/new.png")?.node?.get("orientation"))
    }

    @Test fun anElementLinkedInsideALinkedElementFollowsItsOriginalToo() {
        val store = ProjectStore(tmp.newFolder())
        fun element(title: String, vararg layers: XmlNode) = Scene(scene(*layers).node.with("type", "element").with("title", title))
        fun link(id: String, cached: String) = XmlNode("embedScene", linkedMapOf("id" to "2", "link" to store.linkOf(id)), listOf(XmlNode("scene", mapOf("title" to cached))))
        val c = store.createElement(element("c old"))
        val b = store.createElement(element("b", link(c, "c cached")))
        val a = store.create(scene(link(b, "b cached")))
        store.save(c, element("c new").add(Layer(image("file:/c.png")), listOf(app.absentmotion.model.MediaDeclaration(XmlNode("media", mapOf("uri" to "file:/c.png"))))))
        val loaded = store.load(a)!!
        val inner = loaded.layers.single().nested!!
        assertEquals("b", inner.title)
        assertEquals("c new", inner.layers.single().nested!!.title)
        assertTrue(loaded.media("file:/c.png") != null)
        store.save(c, element("c loop", link(a, "a cached")))
        assertEquals("a cached", store.load(a)!!.layers.single().nested!!.layers.single().nested!!.layers.single().nested!!.title)
    }

    @Test fun aTemplateThatStillUsesTheProjectsOwnMediaDoesNotReplaceTheProject() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(scene())
        val own = store.mediaDir(id).resolve("a.jpg").toURI().toString()
        val copied = root.resolve("templates/t1/media/1_a.jpg").toURI().toString()
        assertFalse(canReplaceProject(scene(image(copied), image(own)), store))
        assertTrue(canReplaceProject(scene(image(copied), image("content://media/external/images/media/5")), store))
        assertFalse(canReplaceProject(null, store))
    }

    @Test fun speedKeyWithoutValueIsIgnored() {
        val map = XmlNode("speedMap", children = listOf(XmlNode("kf", mapOf("t" to "0")), XmlNode("kf", mapOf("t" to "1000000", "v" to "2"))))
        val layer = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "999"), listOf(map)))
        assertEquals(2.0, VideoTiming(layer).speed(0.0).toDouble(), 1e-6)
    }

    @Test fun listReusesParsedFieldsUntilTheFileChangesButAlwaysLooksForTheThumbnail() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(scene().withTitle("one"))
        assertEquals("one", store.list().single().title)
        assertEquals(null, store.list().single().thumbnail)
        store.thumbnailFile(id).writeText("png")
        assertEquals(store.thumbnailFile(id), store.list().single().thumbnail)
        val file = root.resolve("projects/$id.xml")
        val stamp = file.lastModified()
        file.writeText(file.readText().replace("title=\"one\"", "title=\"two\""))
        file.setLastModified(stamp)
        assertEquals("one", store.list().single().title)
        file.setLastModified(stamp + 2000)
        assertEquals("two", store.list().single().title)
        store.save(id, scene().withTitle("three"))
        assertEquals("three", store.list().single().title)
        store.thumbnailFile(id).delete()
        assertEquals(null, store.list().single().thumbnail)
    }

    @Test fun xmlWithoutAnElementIsRejected() {
        for (text in listOf("", "<?xml version='1.0'?>", "<!-- nothing -->")) {
            try { XmlNode.parse(text); fail("parsed '$text'") } catch (_: Exception) {}
        }
    }

    @Test fun xmlStartingWithABomImports() {
        val store = ProjectStore(tmp.newFolder())
        val xml = "﻿" + scene().node.toXml()
        val report = Importer(store).import { ByteArrayInputStream(xml.toByteArray()) }.single()
        assertEquals(100, store.load(report.id)!!.width)
    }
}
