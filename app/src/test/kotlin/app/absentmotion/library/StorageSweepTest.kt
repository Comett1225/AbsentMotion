package app.absentmotion.library

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageSweepTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()

    private fun aged(file: File): File = file.also { f -> f.walkTopDown().forEach { it.setLastModified(now - 3 * StaleTempMs) } }
    private fun made(dir: File, name: String, text: String = "x") = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }
    private fun scene(vararg layers: XmlNode) = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"), layers.toList()))
    private fun image(uri: String, id: Int = 1) = XmlNode("shape", linkedMapOf("id" to "$id", "fillImage" to uri))
    private fun shared(file: File) = "content://app.absentmotion.files/files/${file.parentFile.name}/${file.name}"

    @Test fun onlyFilesNothingRefersToAnyMoreAreRemoved() {
        val files = tmp.newFolder("files")
        val external = tmp.newFolder("external")
        val ai = File(external, "ai").apply { mkdirs() }
        val gif = File(external, "gif").apply { mkdirs() }
        val store = ProjectStore(files)
        val id = store.create(scene())
        val media = store.mediaDir(id)
        val used = aged(made(media, "used & co.png"))
        val inHistory = aged(made(media, "history.png"))
        val inDraft = aged(made(media, "draft.png"))
        val inTemplate = aged(made(files, "templates/t1/media/1_a.png"))
        val unusedTemplateFile = aged(made(files, "templates/t1/media/2_b.png"))
        val unused = aged(made(media, "unused.png"))
        val fresh = made(media, "just copied.png")
        val model = made(media, "model-1/robot.gltf").also { made(media, "model-1/robot.bin"); aged(it.parentFile) }
        val unusedModel = aged(made(media, "model-2/old.obj").parentFile)
        val aiUsed = aged(made(ai, "a1.png"))
        val aiMarked = aged(made(ai, "a2.wav"))
        val aiUnused = aged(made(ai, "a3.png"))
        val gifUsed = aged(made(gif, "g1.mp4"))
        val gifUnused = aged(made(gif, "g2-mask.mp4"))
        val elsewhere = aged(made(media, "linked twice.mp3"))
        val mentionedOnly = aged(made(media, "kept by a damaged file.png"))
        store.save(id, scene(image(used.toURI().toString()), image(shared(aiUsed), 2), image(model.toURI().toString(), 3),
            XmlNode("audio", linkedMapOf("id" to "4", "src" to "file://" + elsewhere.path)),
            XmlNode("shape", linkedMapOf("id" to "5", "fillVideo" to shared(gifUsed)), listOf(XmlNode("ai", linkedMapOf("original" to shared(aiMarked), "result" to shared(gifUsed)))))))
        store.versions.snapshot(id, scene(image(inHistory.toURI().toString())).toXml())
        Recovery(File(files, "recovery")).write(id, scene(image(inDraft.toURI().toString())).toXml())
        made(files, "templates/t1/scene.xml", scene(image(inTemplate.toURI().toString())).toXml())
        made(files, "projects/broken.xml", "<scene><shape fillImage=\"${mentionedOnly.toURI()}\"")
        val removed = sweepStorage(files, listOf(ai, gif), now)
        for (kept in listOf(used, inHistory, inDraft, inTemplate, fresh, model, File(model.parentFile, "robot.bin"), aiUsed, aiMarked, gifUsed, elsewhere, mentionedOnly)) {
            assertTrue(kept.path, kept.exists())
        }
        for (gone in listOf(unused, unusedTemplateFile, unusedModel, aiUnused, gifUnused)) assertFalse(gone.path, gone.exists())
        assertEquals(5, removed)
    }

    @Test fun leftoversOfDeletedProjectsAndCutTemplatesGoButImportsAndRestoresInProgressStay() {
        val files = tmp.newFolder("files")
        val store = ProjectStore(files)
        val gone = aged(made(store.mediaDir("p1"), "orphan.png")).parentFile
        val importing = aged(made(store.mediaDir("p2"), "half.mp4")).also { made(it.parentFile, ImportMarker) }
        val versions = aged(made(files, "projects/versions/p3/1.xml", scene().toXml()).parentFile)
        val restoring = made(files, "projects/versions/p4/2.xml", scene().toXml()).parentFile
        val cutTemplate = aged(made(files, "templates/t9/media/a.png").parentFile.parentFile)
        val staging = aged(made(store.mediaDir("p5"), "copy-123/model.bin").parentFile).also { store.save("p5", scene()) }
        sweepStorage(files, emptyList(), now)
        assertFalse(gone.exists())
        assertTrue(importing.exists())
        assertFalse(versions.exists())
        assertTrue(restoring.exists())
        assertFalse(cutTemplate.exists())
        assertFalse(staging.exists())
        assertTrue(store.mediaDir("p5").exists())
    }

    @Test fun anUnreadableFileStopsTheCleanUpBeforeAnythingIsRemoved() {
        val files = tmp.newFolder("files")
        val store = ProjectStore(files)
        val id = store.create(scene())
        val unused = aged(made(store.mediaDir(id), "unused.png"))
        File(files, "projects/locked.xml").mkdirs()
        sweepStorage(files, emptyList(), now)
        assertTrue(unused.exists())
    }

    @Test fun versionsBeyondTheKeptCountAreRemovedForProjectsNotOpenedForAWhile() {
        val files = tmp.newFolder("files")
        val store = ProjectStore(files)
        val id = store.create(scene())
        val day = 24 * 60 * 60 * 1000L
        repeat(30) { made(store.versions.dir(id), "${now - 40 * day + it * day}.xml", scene().toXml()) }
        sweepStorage(files, emptyList(), now)
        assertEquals(ProjectVersions.NEWEST, store.versions.list(id).size)
    }
}
