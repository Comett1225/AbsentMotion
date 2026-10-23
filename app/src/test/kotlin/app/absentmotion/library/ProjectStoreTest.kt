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

    @Test fun deleteRemovesOnlyAiFilesThatNoProjectOrTemplateUses() {
        val root = tmp.newFolder()
        val ai = tmp.newFolder("ai")
        val store = ProjectStore(root)
        fun aiFile(name: String) = ai.resolve(name).apply { writeText("x") }
        fun uri(file: java.io.File) = "content://app.absentmotion.files/files/ai/${file.name}"
        val kept = aiFile("kept.png")
        val shared = aiFile("shared.png")
        val orphan = aiFile("orphan.wav")
        val a = store.create(scene(image(uri(kept))))
        val b = store.create(scene(image(uri(shared))))
        val c = store.create(scene(image(uri(shared))))
        store.delete(b, ai)
        assertTrue(kept.exists())
        assertTrue(shared.exists())
        assertFalse(orphan.exists())
        val later = aiFile("later.png")
        store.delete(c)
        assertTrue(later.exists())
        assertTrue(shared.exists())
        root.resolve("templates/t1").apply { mkdirs() }.resolve("scene.xml").writeText(scene(image(uri(kept))).toXml())
        store.delete(a, ai)
        assertTrue(kept.exists())
        assertFalse(shared.exists())
        assertFalse(later.exists())
        val loose = aiFile("loose.png")
        val gone = aiFile("gone.png")
        root.resolve("projects/broken.xml").writeText("<scene><shape fillImage=\"${uri(loose)}\"")
        store.delete(store.create(scene()), ai)
        assertTrue(loose.exists())
        assertFalse(gone.exists())
    }

    @Test fun deleteKeepsOwnedMediaWhenAnotherProjectOrTemplateCannotBeRead() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        val shared = store.mediaDir(a).resolve("shared & co.png").apply { writeText("x") }
        val b = store.create(scene(image(shared.toURI().toString())))
        root.resolve("projects/$b.xml").writeText(root.resolve("projects/$b.xml").readText().substringBefore("/>"))
        store.delete(a)
        assertTrue(shared.exists())
        root.resolve("projects/$b.xml").delete()
        val c = store.create(scene())
        store.mediaDir(c).mkdirs()
        val own = store.mediaDir(c).resolve("own.png").apply { writeText("x") }
        root.resolve("projects/unreadable.xml").mkdirs()
        store.delete(c)
        assertTrue(own.exists())
    }

    @Test fun aDamagedFileOrAHalfWrittenTemplateDoesNotStopTheMediaCleanUp() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        val own = store.mediaDir(a).resolve("own.png").apply { writeText("x") }
        val damaged = store.create(scene())
        root.resolve("projects/$damaged.xml").writeText("<scene")
        root.resolve("templates/t1/media").mkdirs()
        root.resolve("templates/t2").apply { mkdirs() }.resolve("scene.xml").writeText("<scene title=")
        store.delete(a)
        assertFalse(own.exists())
        assertFalse(store.mediaDir(a).exists())
    }

    @Test fun renamingATemplateOrPresetThatCannotBeReadFails() {
        val root = tmp.newFolder()
        val templates = TemplateStore(root.resolve("templates")) { null }
        val presets = PresetStore(root.resolve("presets"))
        for (rename in listOf({ templates.rename("t1", "x") }, { presets.rename("p1", "x") })) {
            try { rename(); fail("renamed") } catch (_: java.io.IOException) {}
        }
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

    @Test fun aDuplicateThatCannotCopyItsMediaLeavesNothingBehind() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        store.mediaDir(a).resolve("a.png").writeText("x")
        val locked = store.mediaDir(a).resolve("b.png").apply { writeText("y"); setReadable(false) }
        org.junit.Assume.assumeFalse(locked.canRead())
        try {
            store.duplicate(a)
            fail("the copy should fail")
        } catch (e: java.io.IOException) {
        }
        assertEquals(listOf(a), store.list().map { it.id })
        assertEquals(listOf(a), root.resolve("media").list()!!.toList())
        assertEquals(setOf("$a.xml"), root.resolve("projects").list()!!.filter { it != "versions" }.toSet())
        locked.setReadable(true)
        val copy = store.duplicate(a)!!
        assertEquals(setOf("a.png", "b.png"), store.mediaDir(copy).list()!!.toSet())
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

    private fun model(uri: String) = XmlNode("model", linkedMapOf("id" to "5", "startTime" to "0", "endTime" to "99", "src" to uri))

    @Test fun deleteKeepsAModelFolderAnotherProjectStillUsesAndSweepsItWithTheLastUser() {
        val store = ProjectStore(tmp.newFolder())
        val a = store.create(scene())
        val folder = store.mediaDir(a).resolve("model-1").apply { mkdirs() }
        val gltf = folder.resolve("robot.gltf").apply { writeText("{}") }
        val bin = folder.resolve("robot.bin").apply { writeText("data") }
        val unusedFolder = store.mediaDir(a).resolve("model-10").apply { mkdirs() }.resolve("other.obj").apply { writeText("v") }
        val b = store.create(scene(model(gltf.toURI().toString())))
        store.delete(a)
        assertTrue(gltf.exists())
        assertTrue(bin.exists())
        assertFalse(unusedFolder.exists())
        store.delete(b)
        assertFalse(gltf.exists())
        assertFalse(store.mediaDir(a).exists())
    }

    @Test fun deleteKeepsMediaAndAiFilesThatOnlyVersionsOrRecoveryDraftsStillUse() {
        val root = tmp.newFolder()
        val ai = tmp.newFolder("ai")
        val store = ProjectStore(root)
        fun aiUri(file: java.io.File) = "content://app.absentmotion.files/files/ai/${file.name}"
        val a = store.create(scene())
        store.mediaDir(a).mkdirs()
        val inHistory = store.mediaDir(a).resolve("history.png").apply { writeText("x") }
        val inDraft = store.mediaDir(a).resolve("draft.png").apply { writeText("x") }
        val unused = store.mediaDir(a).resolve("unused.png").apply { writeText("x") }
        val aiHistory = ai.resolve("matte.png").apply { writeText("x") }
        val aiDraft = ai.resolve("voice.wav").apply { writeText("x") }
        val aiUnused = ai.resolve("old.png").apply { writeText("x") }
        val b = store.create(scene())
        store.versions.snapshot(b, scene(image(inHistory.toURI().toString()), image(aiUri(aiHistory))).toXml())
        val c = store.create(scene())
        Recovery(root.resolve("recovery")).write(c, scene(image(inDraft.toURI().toString()), image(aiUri(aiDraft))).toXml())
        store.delete(a, ai)
        assertTrue(inHistory.exists())
        assertTrue(inDraft.exists())
        assertFalse(unused.exists())
        assertTrue(aiHistory.exists())
        assertTrue(aiDraft.exists())
        assertFalse(aiUnused.exists())
        Recovery(root.resolve("recovery")).clear(c)
        store.delete(b, ai)
        assertFalse(inHistory.exists())
        assertFalse(inDraft.exists())
        assertFalse(store.mediaDir(a).exists())
        assertFalse(aiHistory.exists())
        assertFalse(aiDraft.exists())
    }

    @Test fun restoringOverADamagedProjectKeepsTheBrokenFileOutOfTheVersionList() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(scene(image("file:///a.png")))
        store.versions.snapshot(id, store.text(id)!!)
        val good = store.versions.list(id).single()
        root.resolve("projects/$id.xml").writeText("<scene width=\"100\" hei")
        store.restore(id, good)
        assertEquals(listOf(good.time), store.versions.list(id).map { it.time })
        assertEquals(1, store.versions.dir(id).listFiles { f -> f.extension == "damaged" }!!.size)
        assertTrue(store.load(id) != null)
    }

    @Test fun anIdThatWouldLeaveItsFolderIsRefusedEverywhere() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val real = store.create(scene())
        fun refused(use: () -> Any?) = try { use(); fail("accepted") } catch (_: IllegalArgumentException) {}
        for (bad in listOf("..", ".", "", "a/b", "../x", "a\\b", "x\u0000y")) {
            refused { store.mediaDir(bad) }
            refused { store.versions.dir(bad) }
        }
        for (bad in listOf("a/b", "../x", "a\\b", "x\u0000y")) {
            refused { store.thumbnailFile(bad) }
            refused { store.save(bad, "<scene/>") }
        }
        assertEquals(listOf(real), store.list().map { it.id })
        assertEquals(setOf("$real.xml"), root.resolve("projects").list()!!.toSet())
    }

    @Test fun aStrayFileNamedLikeADotSegmentIsNotAProjectAndDeletesNothing() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val real = store.create(scene())
        store.mediaDir(real).mkdirs()
        root.resolve("keep.txt").writeText("keep")
        root.resolve("projects/...xml").writeText(scene().toXml())
        root.resolve("projects/..xml").writeText(scene().toXml())
        assertEquals(listOf(real), store.list(includeDamaged = true).map { it.id })
        store.delete(real)
        assertTrue(root.resolve("keep.txt").exists())
        assertTrue(root.resolve("projects/...xml").exists())
    }

    @Test fun aMediaAddressNamingTheFolderAboveIsNotAnOwner() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        root.resolve("keep.txt").writeText("keep")
        val id = store.create(scene(image(root.resolve("media").toURI().toString() + "%2e%2e/x.png"), image(root.resolve("media").toURI().toString() + "./x.png")))
        store.delete(id)
        assertTrue(root.resolve("keep.txt").exists())
    }
}
