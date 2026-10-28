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

    @Test fun restoredProjectsKeepTheDateTheyWereLastChanged() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val a = project(old, "A", "pixels of a")
        val b = project(old, "B", "pixels of b")
        val then = java.time.LocalDate.of(2025, 3, 14).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        oldRoot.resolve("projects/$a.xml").setLastModified(then)
        oldRoot.resolve("projects/$b.xml").setLastModified(then + 86_400_000L)
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        val fresh = ProjectStore(newRoot)
        Backup.restore(fresh, newRoot, ByteArrayInputStream(bytes))
        val dates = fresh.list().associate { it.id to it.modified }
        assertEquals(then.toDouble(), dates.getValue(a).toDouble(), 2_000.0)
        assertEquals(then + 86_400_000.0, dates.getValue(b).toDouble(), 2_000.0)
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

    @Test fun anUnchangedProjectIsCopiedWhenItsLinkedElementWasChanged() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val asset = store.createElement(Scene.parse("""<scene width="10" height="10" type="element" title="Old asset"><shape id="1"/></scene>"""))
        val user = store.create(Scene.parse("""<scene width="10" height="10" title="Project"><embedScene id="5" link="${store.linkOf(asset)}"><scene width="10" height="10"/></embedScene></scene>"""))
        val bytes = backup(root)
        store.save(asset, store.load(asset)!!.withTitle("New asset"))
        assertEquals(Backup.Restored(2, 2, 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        val copy = store.list().single { it.title == "Project" && it.id != user }.id
        fun shown(id: String) = store.load(id)!!.layer(5)!!.node.child("scene")!!["title"]
        assertEquals("New asset", shown(user))
        assertEquals("Old asset", shown(copy))
    }

    @Test fun unchangedProjectsFollowRestoredMediaOwnersThroughOtherProjects() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val owner = project(store, "Before", "old pixels")
        val shared = store.create(store.load(owner)!!.withTitle("Shared"))
        val linked = store.create(Scene.parse("""<scene width="10" height="10" title="Linked"><embedScene id="5" link="${store.linkOf(shared)}"><scene width="10" height="10"/></embedScene></scene>"""))
        val bytes = backup(root)
        store.save(owner, store.load(owner)!!.withTitle("After"))
        store.mediaDir(owner).resolve("photo.png").writeText("new pixels")
        assertEquals(Backup.Restored(3, 3, 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        val copy = store.list().single { it.title == "Shared" && it.id != shared }.id
        assertEquals("new pixels", pictureOf(store, shared))
        assertEquals("old pixels", pictureOf(store, copy))
        val linkedCopy = store.list().single { it.title == "Linked" && it.id != linked }.id
        assertEquals(store.linkOf(copy), store.load(linkedCopy)!!.layer(5)!!.node["link"])
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

    @Test fun aBackedUpFontWithTheNameOfADifferentFontKeepsItsOwnFileAndEveryReference() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val withFont = "imported?name=Mine.ttf"
        val id = old.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Fonts"),
            listOf(XmlNode("text", linkedMapOf("id" to "1", "font" to withFont))))))
        old.versions.snapshot(id, old.text(id)!!)
        File(oldRoot, "presets/p.xml").apply { parentFile.mkdirs(); writeText("""<scene width="10" height="10" type="preset"><text id="1" font="$withFont"/></scene>""") }
        File(oldRoot, "fonts/imported/Mine.ttf").apply { parentFile.mkdirs(); writeText("backed up font") }
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        File(newRoot, "fonts/imported/Mine.ttf").apply { parentFile.mkdirs(); writeText("another font") }
        val fresh = ProjectStore(newRoot)
        Backup.restore(fresh, newRoot, ByteArrayInputStream(bytes))
        assertEquals("another font", File(newRoot, "fonts/imported/Mine.ttf").readText())
        assertEquals("backed up font", File(newRoot, "fonts/imported/Mine-1.ttf").readText())
        val renamed = "imported?name=Mine-1.ttf"
        assertEquals(renamed, fresh.load(id)!!.layers.single().node["font"])
        assertTrue(fresh.versions.list(id).single().file.readText().contains(renamed))
        assertTrue(File(newRoot, "presets/p.xml").readText().contains(renamed))
        val same = tmp.newFolder("same")
        File(same, "fonts/imported/Mine.ttf").apply { parentFile.mkdirs(); writeText("backed up font") }
        Backup.restore(ProjectStore(same), same, ByteArrayInputStream(bytes))
        assertEquals(listOf("Mine.ttf"), File(same, "fonts/imported").list()!!.toList())
        assertEquals(withFont, ProjectStore(same).load(id)!!.layers.single().node["font"])
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

    @Test fun aSceneWithAnImpossibleSizeOrFrameRateIsNotRestored() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val good = project(old, "Good", "pixels")
        val bad = project(old, "Bad", "pixels")
        oldRoot.resolve("projects/$bad.xml").writeText(old.text(bad)!!.replace("<scene ", "<scene fps=\"100000\" "))
        old.thumbnailFile(bad).writeText("png")
        val damaged = old.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"))))
        oldRoot.resolve("projects/$damaged.xml").writeText("<scene")
        val newRoot = tmp.newFolder("new")
        val fresh = ProjectStore(newRoot)
        assertEquals(Backup.Restored(2, 0, 0, 1), Backup.restore(fresh, newRoot, ByteArrayInputStream(backup(oldRoot))))
        assertEquals(listOf("Good"), fresh.list().map { it.title })
        assertEquals(setOf(good, damaged), fresh.list(includeDamaged = true).map { it.id }.toSet())
        assertFalse(fresh.thumbnailFile(bad).exists())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("absentmotion-backup.txt")); zip.write("AbsentMotion backup 1\nfile:/elsewhere/\n".toByteArray())
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes) }
        }
    }.toByteArray()

    private fun everything(root: File) = root.walkTopDown().map { it.relativeTo(root).path to (if (it.isFile) it.readText() else "") }.toList().sortedBy { it.first }

    @Test fun anEntryNamedLikeADotSegmentOrAnOddIdNeverReachesAnotherFolder() {
        val root = tmp.newFolder("root")
        val store = ProjectStore(root)
        val kept = project(store, "Kept", "kept pixels")
        root.resolve("keep.txt").writeText("keep")
        store.versions.snapshot(kept, store.text(kept)!!)
        val before = everything(root)
        val scene = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Evil"))).toXml().toByteArray()
        val bytes = zipOf(
            "projects/...xml" to scene, "projects/.xml" to scene, "projects/a b.xml" to scene, "projects/a:b.xml" to scene,
            "projects/..\\x.xml" to scene, "projects/" + "x".repeat(65) + ".xml" to scene,
            "projects/....png" to "x".toByteArray(), "projects/versions/.../1.xml" to scene, "projects/versions/x y/1.xml" to scene,
            "projects/versions/p1/notnumber.xml" to scene, "projects/versions/p1/1.exe" to scene,
            "media/.../a.png" to "x".toByteArray(), "media/a b/a.png" to "x".toByteArray(), "templates/.../scene.xml" to scene,
        )
        val result = Backup.restore(store, root, ByteArrayInputStream(bytes))
        assertEquals(Backup.Restored(0, 0, 0, 5), result)
        assertEquals(before, everything(root))
        assertEquals(listOf("Kept"), store.list().map { it.title })
    }

    @Test fun idsTheAppCreatesAreAllRestored() {
        val root = tmp.newFolder("root")
        val store = ProjectStore(root)
        val scene = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Ids"))).toXml().toByteArray()
        val uuid = java.util.UUID.randomUUID().toString()
        val bytes = zipOf("projects/p1700000000000.xml" to scene, "projects/$uuid.xml" to scene, "projects/Mixed_id-7.xml" to scene, "templates/t1700000000000/scene.xml" to scene)
        assertEquals(Backup.Restored(3, 0, 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        assertEquals(setOf("p1700000000000", uuid, "Mixed_id-7"), store.list().map { it.id }.toSet())
        assertTrue(root.resolve("templates/t1700000000000/scene.xml").exists())
    }

    @Test fun aStoppedOrCutRestoreLeavesTheLibraryExactlyAsItWas() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val big = "p".repeat(300_000)
        val a = project(old, "A", big)
        val b = project(old, "B", big + "b")
        old.thumbnailFile(a).writeText("thumb")
        old.versions.snapshot(a, old.text(a)!!)
        val bytes = backup(oldRoot)
        val root = tmp.newFolder("new")
        val store = ProjectStore(root)
        val before = everything(root)
        try {
            Backup.restore(store, root, ByteArrayInputStream(bytes)) { read -> if (read > bytes.size / 2) throw IllegalStateException("stopped") }
            fail("should have stopped")
        } catch (e: IllegalStateException) {
            assertEquals("stopped", e.message)
        }
        assertEquals(before, everything(root))
        try {
            Backup.restore(store, root, ByteArrayInputStream(bytes.copyOf(bytes.size - 100_000)))
            fail("should have failed")
        } catch (e: ImportFailed) {
            assertEquals(txt(R.string.library_err_backup_damaged), e.text)
        }
        assertEquals(before, everything(root))
        assertEquals(Backup.Restored(2, 0, 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        assertEquals(big, pictureOf(store, a))
        assertEquals(big + "b", pictureOf(store, b))
    }

    @Test fun retryingAnInterruptedRestoreAddsOneCopyNotTwo() {
        val root = tmp.newFolder("files")
        val store = ProjectStore(root)
        val changed = project(store, "Before", "p".repeat(300_000))
        val bytes = backup(root)
        store.save(changed, store.load(changed)!!.withTitle("After"))
        try {
            Backup.restore(store, root, ByteArrayInputStream(bytes)) { read -> if (read > bytes.size / 2) throw IllegalStateException("stopped") }
            fail("should have stopped")
        } catch (e: IllegalStateException) {
        }
        assertEquals(listOf("After"), store.list().map { it.title })
        assertEquals(Backup.Restored(added = 1, renamed = 1, skipped = 0), Backup.restore(store, root, ByteArrayInputStream(bytes)))
        assertEquals(setOf("After", "Before"), store.list().map { it.title }.toSet())
        assertEquals(2, root.resolve("media").list()!!.size)
    }

    @Test fun projectsAreOnlyPublishedOnceTheirMediaIsThere() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val a = project(old, "A", "pixels")
        val bytes = backup(oldRoot)
        val root = tmp.newFolder("new")
        val store = ProjectStore(root)
        var listedWhileReading = -1
        Backup.restore(store, root, ByteArrayInputStream(bytes)) { listedWhileReading = store.list().size }
        assertEquals(0, listedWhileReading)
        assertEquals(listOf(a), store.list().map { it.id })
    }

    @Test fun aMediaFileWithAnXmlNameIsCopiedByteForByte() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val a = project(old, "A", "pixels")
        val odd = byteArrayOf(-1, -2, 0, 1, -128, 60, 63, 120)
        old.mediaDir(a).resolve("data.xml").writeBytes(odd)
        File(oldRoot, "templates/t1/media").apply { mkdirs() }.resolve("blob.xml").writeBytes(odd)
        File(oldRoot, "templates/t1/scene.xml").writeText(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"))).toXml())
        val root = tmp.newFolder("new")
        Backup.restore(ProjectStore(root), root, ByteArrayInputStream(backup(oldRoot)))
        assertTrue(odd.contentEquals(root.resolve("media/$a/data.xml").readBytes()))
        assertTrue(odd.contentEquals(root.resolve("templates/t1/media/blob.xml").readBytes()))
    }

    @Test fun addressesThatAreNotLocalAreBlockedWhenARestoreBringsThemIn() {
        val root = tmp.newFolder("new")
        val store = ProjectStore(root)
        val scene = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Net"), listOf(
            XmlNode("shape", linkedMapOf("id" to "1", "fillImage" to "https://example.com/a.png")),
            XmlNode("shape", linkedMapOf("id" to "2", "fillVideo" to "/etc/hosts")),
            XmlNode("shape", linkedMapOf("id" to "3", "fillVideo" to "rtsp://example.com/live")),
            XmlNode("audio", linkedMapOf("id" to "4", "src" to "content://media/external/audio/media/3")),
        )))
        Backup.restore(store, root, ByteArrayInputStream(zipOf("projects/p1.xml" to scene.toXml().toByteArray())), MediaAccess.forPhone(root))
        assertEquals(setOf("blocked-https://example.com/a.png", "blocked-/etc/hosts", "blocked-rtsp://example.com/live", "content://media/external/audio/media/3"), store.load("p1")!!.node.mediaUris())
    }

    @Test fun aSmallBackupThatUnpacksToAHugeFileIsStoppedAndLeavesNothing() {
        val root = tmp.newFolder("root")
        val store = ProjectStore(root)
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("absentmotion-backup.txt")); zip.write("AbsentMotion backup 1\nfile:/elsewhere/\n".toByteArray())
                zip.putNextEntry(ZipEntry("media/p1/zeros.bin"))
                val block = ByteArray(1 shl 20)
                repeat(400) { zip.write(block) }
            }
        }.toByteArray()
        assertTrue(bytes.size < 2 shl 20)
        try {
            Backup.restore(store, root, ByteArrayInputStream(bytes))
            fail()
        } catch (e: ImportFailed) {
            assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 1 shl 20)
        }
    }

    @Test fun theWriterNeverChangesTheCompressionOfAStreamItHasUsed() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = project(store, "A", "pixels")
        store.thumbnailFile(id).writeText("png")
        store.versions.snapshot(id, store.text(id)!!)
        val methods = LinkedHashMap<String, Int>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(backup(root))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = zip.readBytes()
                methods[entry.name] = entry.method
                if (entry.method == ZipEntry.STORED) assertEquals(entry.name, data.size.toLong(), entry.size)
            }
        }
        for ((name, method) in methods) assertEquals(name, if (name.endsWith(".xml") || name == "absentmotion-backup.txt") ZipEntry.DEFLATED else ZipEntry.STORED, method)
        assertTrue(methods.keys.any { it.endsWith(".png") })
    }

    @Test fun aHugeEntryTheRestoreSkipsStillCountsTowardsTheUnpackLimit() {
        val root = tmp.newFolder("root")
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("absentmotion-backup.txt")); zip.write("AbsentMotion backup 1\nfile:/elsewhere/\n".toByteArray())
                zip.putNextEntry(ZipEntry("unknown/zeros.bin"))
                val block = ByteArray(1 shl 20)
                repeat(300) { zip.write(block) }
                zip.putNextEntry(ZipEntry("projects/p1.xml")); zip.write(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"))).toXml().toByteArray())
            }
        }.toByteArray()
        assertTrue(bytes.size < 2 shl 20)
        try {
            Backup.restore(ProjectStore(root), root, ByteArrayInputStream(bytes))
            fail()
        } catch (e: ImportFailed) {
            assertTrue(ProjectStore(root).list(includeDamaged = true).isEmpty())
        }
    }

    @Test fun projectTextWaitsInTemporaryFilesThatAreAlwaysRemoved() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        project(old, "A", "p".repeat(300_000))
        project(old, "B", "pixels of b")
        val bytes = backup(oldRoot)
        val root = tmp.newFolder("new")
        val store = ProjectStore(root)
        fun staged() = root.resolve("projects").listFiles { f -> f.name.endsWith(".part") }!!.size
        var most = 0
        Backup.restore(store, root, ByteArrayInputStream(bytes)) { most = maxOf(most, staged()) }
        assertEquals(2, most)
        assertEquals(0, staged())
        try {
            Backup.restore(ProjectStore(tmp.newFolder("cut")), tmp.root.resolve("cut"), ByteArrayInputStream(bytes)) { read -> if (read > bytes.size / 2) throw IllegalStateException("stopped") }
            fail()
        } catch (e: IllegalStateException) {
        }
        assertEquals(0, tmp.root.resolve("cut/projects").listFiles { f -> f.name.endsWith(".part") }!!.size)
    }

    @Test fun aBackupWithTheSameProjectTwiceIsRefusedAndLeavesNothing() {
        val root = tmp.newFolder("root")
        val store = ProjectStore(root)
        val kept = project(store, "Kept", "kept pixels")
        val before = everything(root)
        val scene = { title: String -> Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to title))).toXml().toByteArray() }
        try {
            Backup.restore(store, root, ByteArrayInputStream(zipOf("projects/p1.xml" to scene("one"), "media/p1/a.png" to "x".toByteArray(), "projects\\p1.xml" to scene("two"))))
            fail()
        } catch (e: ImportFailed) {
            assertEquals(txt(R.string.library_err_backup_changed), e.text)
        }
        assertEquals(before, everything(root))
        assertEquals(listOf(kept), store.list().map { it.id })
    }

    @Test fun aReorderedBackupRestoresWhereNothingClashesAndIsRefusedWhereItWouldMixMedia() {
        val oldRoot = tmp.newFolder("old")
        val old = ProjectStore(oldRoot)
        val a = project(old, "Before", "old pixels")
        val ordered = backup(oldRoot)
        val entries = ArrayList<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(ordered)).use { zip -> while (true) { val e = zip.nextEntry ?: break; entries += e.name to zip.readBytes() } }
        val reordered = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> for ((name, data) in entries.sortedBy { it.first }) { zip.putNextEntry(ZipEntry(name)); zip.write(data) } }
        }.toByteArray()
        val fresh = tmp.newFolder("fresh")
        assertEquals(Backup.Restored(1, 0, 0), Backup.restore(ProjectStore(fresh), fresh, ByteArrayInputStream(reordered)))
        assertEquals("old pixels", pictureOf(ProjectStore(fresh), a))
        val root = tmp.newFolder("same")
        val store = ProjectStore(root)
        Backup.restore(store, root, ByteArrayInputStream(ordered))
        store.save(a, store.load(a)!!.withTitle("After"))
        store.mediaDir(a).resolve("photo.png").writeText("new pixels")
        for (missing in listOf(false, true)) {
            if (missing) store.mediaDir(a).resolve("photo.png").delete()
            val before = everything(root)
            try {
                Backup.restore(store, root, ByteArrayInputStream(reordered))
                fail()
            } catch (e: ImportFailed) {
                assertEquals(txt(R.string.library_err_backup_changed), e.text)
            }
            assertEquals(before, everything(root))
        }
    }

    @Test fun aBackupWithTooManyEntriesIsRefused() {
        val root = tmp.newFolder("root")
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("absentmotion-backup.txt")); zip.write("AbsentMotion backup 1\nfile:/elsewhere/\n".toByteArray())
                repeat(100_001) { zip.putNextEntry(ZipEntry("unknown/$it.txt")) }
            }
        }.toByteArray()
        try {
            Backup.restore(ProjectStore(root), root, ByteArrayInputStream(bytes))
            fail()
        } catch (e: ImportFailed) {
            assertEquals(txt(R.string.library_err_too_many_files, "100000"), e.text)
        }
    }

    private fun template(root: File, id: String, title: String, pixels: String): File {
        val dir = File(root, "templates/$id")
        val media = File(dir, "media/photo.png").apply { parentFile.mkdirs(); writeText(pixels) }
        File(dir, "scene.xml").writeText(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to title),
            listOf(XmlNode("shape", linkedMapOf("id" to "1", "fillImage" to media.toURI().toString()))))).toXml())
        File(dir, "thumb.png").writeText("thumb of $title")
        return dir
    }

    @Test fun aChangedTemplateWithTheSameIdComesBackAsASeparateCopyAndTheLocalOneIsUntouched() {
        val oldRoot = tmp.newFolder("old")
        template(oldRoot, "t1", "Old name", "old pixels")
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        template(newRoot, "t1", "New name", "new pixels")
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        assertEquals("New name", Scene.parse(File(newRoot, "templates/t1/scene.xml").readText()).title)
        assertEquals("new pixels", File(newRoot, "templates/t1/media/photo.png").readText())
        val copy = File(newRoot, "templates").listFiles { f -> f.isDirectory && f.name != "t1" }.orEmpty().single()
        assertEquals("Old name", Scene.parse(File(copy, "scene.xml").readText()).title)
        assertEquals("old pixels", File(copy, "media/photo.png").readText())
        assertEquals("thumb of Old name", File(copy, "thumb.png").readText())
        val uri = Scene.parse(File(copy, "scene.xml").readText()).node.mediaUris().single()
        assertEquals(File(copy, "media/photo.png").canonicalPath, File(java.net.URI(uri)).canonicalPath)
        assertFalse(File(newRoot, "restore-staging").exists())
    }

    @Test fun aTemplateCopyFromABackupWithoutMediaStillPointsAtMediaThatExists() {
        val oldRoot = tmp.newFolder("old")
        template(oldRoot, "t1", "Old name", "old pixels")
        val bytes = backup(oldRoot, media = false)
        val newRoot = tmp.newFolder("new")
        template(newRoot, "t1", "New name", "new pixels")
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        val copy = File(newRoot, "templates").listFiles { f -> f.isDirectory && f.name != "t1" }.orEmpty().single()
        assertEquals("Old name", Scene.parse(File(copy, "scene.xml").readText()).title)
        val uri = Scene.parse(File(copy, "scene.xml").readText()).node.mediaUris().single()
        assertEquals(File(newRoot, "templates/t1/media/photo.png").canonicalPath, File(java.net.URI(uri)).canonicalPath)
        assertTrue(File(java.net.URI(uri)).exists())
    }

    @Test fun anUnchangedTemplateStillGetsBackAFileThatWentMissingLocally() {
        val oldRoot = tmp.newFolder("old")
        template(oldRoot, "t1", "Same", "pixels")
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        File(newRoot, "templates/t1/thumb.png").delete()
        File(newRoot, "templates/t1/media/photo.png").delete()
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        assertEquals("thumb of Same", File(newRoot, "templates/t1/thumb.png").readText())
        assertEquals("pixels", File(newRoot, "templates/t1/media/photo.png").readText())
        assertEquals(listOf("t1"), File(newRoot, "templates").listFiles().orEmpty().map { it.name })
        assertFalse(File(newRoot, "restore-staging").exists())
    }

    @Test fun aBackupAlsoFailsWhenAnXmlFileVanished() {
        val root = tmp.newFolder("root")
        val gone = File(root, "projects/gone.xml")
        try {
            Backup.writeFiles(root, listOf("projects/gone.xml" to gone), ByteArrayOutputStream())
            fail("expected a failure")
        } catch (e: java.io.FileNotFoundException) {
            assertTrue(e.message, e.message!!.contains("gone.xml"))
        }
    }

    @Test fun anUnchangedTemplateIsSkippedAndAnAbsentOneIsAddedUnderItsOwnId() {
        val oldRoot = tmp.newFolder("old")
        template(oldRoot, "t1", "Same", "pixels")
        template(oldRoot, "t2", "Other", "other pixels")
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes))
        assertEquals(setOf("t1", "t2"), File(newRoot, "templates").listFiles().orEmpty().map { it.name }.toSet())
        assertEquals("other pixels", File(newRoot, "templates/t2/media/photo.png").readText())
    }

    @Test fun aChangedPresetKeepsBothVersionsAndTheSameBackupDoesNotAddItTwice() {
        val oldRoot = tmp.newFolder("old")
        File(oldRoot, "presets/p.xml").apply { parentFile.mkdirs(); writeText("""<scene width="10" height="10" type="preset" title="Old"><shape id="1"/></scene>""") }
        val bytes = backup(oldRoot)
        val newRoot = tmp.newFolder("new")
        File(newRoot, "presets/p.xml").apply { parentFile.mkdirs(); writeText("""<scene width="10" height="10" type="preset" title="New"><shape id="1"/></scene>""") }
        repeat(2) { Backup.restore(ProjectStore(newRoot), newRoot, ByteArrayInputStream(bytes)) }
        val files = File(newRoot, "presets").listFiles().orEmpty()
        assertEquals(2, files.size)
        assertTrue(File(newRoot, "presets/p.xml").readText().contains("New"))
        assertTrue(files.single { it.name != "p.xml" }.readText().contains("Old"))
        val same = tmp.newFolder("same")
        File(same, "presets/p.xml").apply { parentFile.mkdirs(); writeText(File(oldRoot, "presets/p.xml").readText()) }
        Backup.restore(ProjectStore(same), same, ByteArrayInputStream(bytes))
        assertEquals(1, File(same, "presets").listFiles().orEmpty().size)
    }

    @Test fun aBackupFailsInsteadOfSilentlyOmittingAFileThatVanished() {
        val root = tmp.newFolder("root")
        val present = File(root, "projects/a.xml").apply { parentFile.mkdirs(); writeText("<scene/>") }
        val gone = File(root, "media/a/gone.png")
        try {
            Backup.writeFiles(root, listOf("projects/a.xml" to present, "media/a/gone.png" to gone), ByteArrayOutputStream())
            fail("expected a failure")
        } catch (e: java.io.FileNotFoundException) {
            assertTrue(e.message, e.message!!.contains("gone.png"))
        }
    }
}
