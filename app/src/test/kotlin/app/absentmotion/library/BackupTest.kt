package app.absentmotion.library

import app.absentmotion.i18n.txt
import app.absentmotion.R
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(store: ProjectStore, title: String, picture: String): String {
        val id = store.newId()
        val file = store.mediaDir(id).apply { mkdirs() }.resolve("photo.png").apply { writeText(picture) }
        val uri = file.toURI().toString()
        val scene = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to title),
            listOf(XmlNode("shape", linkedMapOf("id" to "1", "fillType" to "media", "fillImage" to uri)))))
            .withMedia(MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri, "filename" to "photo.png"))))
        store.save(id, scene)
        return id
    }

    private fun backup(root: File, media: Boolean = true) = ByteArrayOutputStream().also { Backup.write(root, it, media) }.toByteArray()

    private fun pictureOf(store: ProjectStore, id: String): String {
        val uri = store.load(id)!!.node.mediaUris().single()
        assertTrue(uri, store.inMediaFolder(uri))
        return File(java.net.URI(uri)).readText()
    }

    @Test fun backupRestoresOnAnotherPhoneWithMediaAndVersions() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val a = project(old, "A", "pixels of a")
        val b = project(old, "B", "pixels of b")
        old.versions.snapshot(a, old.text(a)!!)
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        val fresh = ProjectStore(newRoot)
        var read = 0L
        val result = Backup.restore(fresh, newRoot, ByteArrayInputStream(bytes)) { read = it }
        assertEquals(Backup.Restored(2, 0, 0), result)
        assertTrue(read in 1..bytes.size.toLong())
        assertEquals(setOf("A", "B"), fresh.list().map { it.title }.toSet())
        assertEquals("pixels of a", pictureOf(fresh, a))
        assertEquals("pixels of b", pictureOf(fresh, b))
        assertEquals(1, fresh.versions.list(a).size)
        assertTrue(fresh.versions.list(a).single().file.readText().contains(newRoot.toURI().toString().trimEnd('/') + "/media/$a/"))
    }

    @Test fun restoringBesideChangedProjectsAddsCopiesAndNeverOverwrites() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val same = project(store, "Same", "same pixels")
        val changed = project(store, "Before", "old pixels")
        val bytes = backup(root)
        store.save(changed, store.load(changed)!!.withTitle("After"))
        store.mediaDir(changed).resolve("photo.png").writeText("new pixels")
        val result = Backup.restore(store, root, ByteArrayInputStream(bytes))
        assertEquals(Backup.Restored(added = 1, renamed = 1, skipped = 1), result)
        val titles = store.list().associate { it.title to it.id }
        assertEquals(setOf("Same", "After", "Before"), titles.keys)
        assertEquals(changed, titles["After"])
        assertEquals("new pixels", pictureOf(store, changed))
        val copy = titles.getValue("Before")
        assertTrue(copy != changed)
        assertEquals("old pixels", pictureOf(store, copy))
        assertEquals("same pixels", pictureOf(store, same))
    }

    @Test fun aRestoredCopyShowsTheAssetFromTheBackupNotTheChangedOne() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        fun scene(title: String, vararg layers: XmlNode) = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to title), layers.toList()))
        val asset = store.createElement(Scene(scene("Old asset", XmlNode("shape", linkedMapOf("id" to "1"))).node.with("type", "element")))
        val user = store.create(scene("Old project", XmlNode("embedScene", linkedMapOf("id" to "5", "link" to store.linkOf(asset)), listOf(scene("").node))))
        val bytes = backup(root)
        store.save(asset, store.load(asset)!!.withTitle("New asset"))
        store.save(user, store.load(user)!!.withTitle("New project"))
        assertEquals(Backup.Restored(added = 2, renamed = 2, skipped = 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        val ids = store.list().associate { it.title to it.id }
        fun shown(project: String) = store.load(ids.getValue(project))!!.layer(5)!!.node.child("scene")!!["title"]
        assertEquals("Old asset", shown("Old project"))
        assertEquals("New asset", shown("New project"))
    }

    @Test fun restoringOnTheSamePhoneKeepsOwnLinksAndFindsEveryProjectAlreadyHere() {
        val root = tmp.newFolder("files")
        val external = tmp.newFolder("external")
        val access = MediaAccess.forPhone(root, external)
        val store = ProjectStore(root)
        project(store, "Kept", "kept pixels")
        val tone = File(external, "tone.wav").apply { writeText("tone") }.toURI().toString()
        val photo = "content://media/external/images/media/12"
        val foreign = File(tmp.newFolder("elsewhere"), "secret.txt").apply { writeText("secret") }.toURI().toString()
        val linked = store.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Linked"), listOf(
            XmlNode("audio", linkedMapOf("id" to "1", "src" to tone)),
            XmlNode("shape", linkedMapOf("id" to "2", "fillImage" to photo)),
            XmlNode("shape", linkedMapOf("id" to "3", "fillImage" to foreign))))))
        val plain = store.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Plain"), listOf(
            XmlNode("audio", linkedMapOf("id" to "1", "src" to tone))))))
        val bytes = backup(root)
        assertEquals(Backup.Restored(0, 0, 3), Backup.restore(store, root, ByteArrayInputStream(bytes), access))
        assertEquals(3, store.list().size)
        val newRoot = tmp.newFolder("new")
        val fresh = ProjectStore(newRoot)
        assertEquals(Backup.Restored(3, 0, 0), Backup.restore(fresh, newRoot, ByteArrayInputStream(bytes), MediaAccess.forPhone(newRoot, external)))
        assertEquals(setOf(tone, photo, "blocked-$foreign"), fresh.load(linked)!!.node.mediaUris())
        assertEquals(store.text(plain), fresh.text(plain))
    }

    @Test fun projectsOnlyBackupLeavesMediaOut() {
        val root = tmp.newFolder()
        project(ProjectStore(root), "A", "pixels")
        assertTrue(Backup.files(root, includeMedia = true).any { it.first.startsWith("media/") })
        assertFalse(Backup.files(root, includeMedia = false).any { it.first.startsWith("media/") })
        assertTrue(Backup.files(root, includeMedia = false).first().first.matches(Regex("projects/[^/]+\\.xml")))
    }

    @Test fun templatesPresetsImportedFontsAndAiMediaAreBackedUpAndRestored() {
        val oldRoot = tmp.newFolder("old")
        val oldAi = tmp.newFolder("old-ai")
        val templateMedia = File(oldRoot, "templates/t1/media/photo.png").apply { parentFile.mkdirs(); writeText("template pixels") }
        val matte = File(oldAi, "matte.png").apply { writeText("ai pixels") }
        val aiUri = "content://app.absentmotion.files/files/ai/${matte.name}"
        File(oldRoot, "templates/t1/scene.xml").writeText(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Template"),
            listOf(XmlNode("shape", linkedMapOf("id" to "1", "fillImage" to templateMedia.toURI().toString())),
                XmlNode("shape", linkedMapOf("id" to "2", "fillImage" to aiUri))))).toXml())
        File(oldRoot, "templates/t1/thumb.png").writeText("thumb")
        File(oldRoot, "presets/p.xml").apply { parentFile.mkdirs(); writeText("""<scene width="10" height="10" type="preset"><shape id="1"/></scene>""") }
        File(oldRoot, "fonts/imported/Mine.ttf").apply { parentFile.mkdirs(); writeText("font") }
        val noMedia = Backup.files(oldRoot, includeMedia = false, oldAi).map { it.first }.toSet()
        assertEquals(setOf("templates/t1/scene.xml", "templates/t1/thumb.png", "presets/p.xml"), noMedia)
        val bytes = ByteArrayOutputStream().also { Backup.write(oldRoot, it, true, oldAi) }.toByteArray()
        val newRoot = tmp.newFolder("new")
        val newAi = File(tmp.root, "new-ai")
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes), ai = newAi)
        val restored = Scene.parse(File(newRoot, "templates/t1/scene.xml").readText()).node.mediaUris()
        val local = restored.single { it.startsWith("file:") }
        assertTrue(local, MediaAccess.forPhone(newRoot).allows(local))
        assertEquals("template pixels", File(java.net.URI(local)).readText())
        assertTrue(aiUri in restored)
        assertEquals("ai pixels", File(newAi, matte.name).readText())
        assertEquals("thumb", File(newRoot, "templates/t1/thumb.png").readText())
        assertTrue(File(newRoot, "presets/p.xml").readText().contains("preset"))
        assertEquals("font", File(newRoot, "fonts/imported/Mine.ttf").readText())
    }

    @Test fun rejectsOtherFilesAndPathsThatLeaveTheFolder() {
        val root = tmp.newFolder("root")
        val store = ProjectStore(root)
        try {
            Backup.restore(store, root, ByteArrayInputStream(ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { it.putNextEntry(ZipEntry("project.xml")); it.write("<scene/>".toByteArray()) }
            }.toByteArray()))
            fail()
        } catch (e: ImportFailed) {
            assertEquals(txt(R.string.library_err_not_backup), e.text)
        }
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("absentmotion-backup.txt")); zip.write("AbsentMotion backup 1\nfile:/elsewhere/\n".toByteArray())
                zip.putNextEntry(ZipEntry("media/../../escaped.txt")); zip.write("x".toByteArray())
                zip.putNextEntry(ZipEntry("media/p1/../../../escaped2.txt")); zip.write("x".toByteArray())
            }
        }.toByteArray()
        assertEquals(Backup.Restored(0, 0, 0), Backup.restore(store, root, ByteArrayInputStream(evil)))
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertFalse(tmp.root.walkTopDown().any { it.name.startsWith("escaped") })
    }
}
