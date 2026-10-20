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

    @Test fun projectsOnlyBackupLeavesMediaOut() {
        val root = tmp.newFolder()
        project(ProjectStore(root), "A", "pixels")
        assertTrue(Backup.files(root, includeMedia = true).any { it.first.startsWith("media/") })
        assertFalse(Backup.files(root, includeMedia = false).any { it.first.startsWith("media/") })
        assertTrue(Backup.files(root, includeMedia = false).first().first.matches(Regex("projects/[^/]+\\.xml")))
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
            assertTrue(e.message!!.contains("not an AbsentMotion backup"))
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
