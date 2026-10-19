package app.absentmotion.library

import app.absentmotion.model.XmlNode
import app.absentmotion.model.mediaUris
import org.json.JSONArray
import org.json.JSONObject
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

class ImporterSecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun importer(files: File, limits: ImportLimits = ImportLimits()) = Importer(ProjectStore(files), access = MediaAccess.forPhone(files), limits = limits)

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun failure(importer: Importer, bytes: ByteArray): String =
        try { importer.import { ByteArrayInputStream(bytes) }; fail("imported"); "" } catch (e: ImportFailed) { e.message!! }

    private fun scene(vararg body: String) = """<scene title="Crafted" width="10" height="10">${body.joinToString("")}</scene>"""

    @Test fun aCraftedSceneCannotMakeThePrivateFilesOpen() {
        val files = tmp.newFolder("files")
        val settings = File(files, "preferences/settings.json").apply { parentFile.mkdirs(); writeText("secret") }.toURI().toString()
        val project = File(files, "projects/p1.xml").apply { parentFile.mkdirs(); writeText("<scene/>") }.toURI().toString()
        val own = File(files, "media/p9/a.jpg").apply { parentFile.mkdirs(); writeText("picture") }.toURI().toString()
        val xml = scene(
            """<media uri="$settings" filename="settings.json"/>""",
            """<shape id="1" fillType="media" fillImage="$project"/>""",
            """<shape id="2" fillType="media" fillImage="$own"/>""",
            """<shape id="3" fillType="media" fillImage="content://media/external/images/media/5"/>""",
            """<audio id="4" src="file:///etc/hosts"/>""",
            """<shape id="5" fillType="media" fillImage="$settings"/>""",
            """<shape id="6" fillType="media" fillImage="x://elsewhere"/>""",
        )
        val store = ProjectStore(files)
        val report = Importer(store, access = MediaAccess.forPhone(files)).import { xml.byteInputStream() }.single()
        val node = store.load(report.id)!!.node
        val uris = node.mediaUris()
        assertEquals(setOf("blocked-$settings", "blocked-$project", own, "content://media/external/images/media/5", "blocked-file:///etc/hosts", "x://elsewhere"), uris)
        assertFalse(node.toXml().contains("\"$settings\"") || node.toXml().contains("\"$project\""))
        val missing = node.children.filter { it.name == "media" && it["missing"] == "true" }.map { it["uri"] }.toSet()
        assertEquals(setOf("blocked-$settings", "blocked-$project", "blocked-file:///etc/hosts", "x://elsewhere"), missing)
        assertEquals(listOf("settings.json"), report.sections.first { it.first == "Media not found" }.second.filter { it == "settings.json" })
    }

    @Test fun aCraftedPackageKeepsItsOwnMediaAndLosesTheRest() {
        val files = tmp.newFolder("files")
        val secret = File(files, "preferences/settings.json").apply { parentFile.mkdirs(); writeText("secret") }.toURI().toString()
        val xml = scene(
            """<media uri="x://a" filename="a.jpg"/>""",
            """<shape id="1" fillType="media" fillImage="x://a"/>""",
            """<shape id="2" fillType="media" fillImage="$secret"/>""",
        )
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "media/1_a.jpg")))
        val bytes = zip(listOf("project.xml" to xml.toByteArray(), "media/1_a.jpg" to "jpg".toByteArray(), "manifest.json" to manifest.toString().toByteArray()))
        val store = ProjectStore(files)
        val report = Importer(store, access = MediaAccess.forPhone(files)).import { ByteArrayInputStream(bytes) }.single()
        val node = store.load(report.id)!!.node
        val copy = File(store.mediaDir(report.id), "1_a.jpg")
        assertEquals("jpg", copy.readText())
        assertEquals(setOf(copy.toURI().toString(), "blocked-$secret"), node.mediaUris())
        assertEquals(listOf("blocked-$secret"), node.children.filter { it.name == "media" && it["missing"] == "true" }.map { it["uri"] })
    }

    @Test fun aPackageWithTooManyEntriesIsRefusedBeforeAnythingIsSaved() {
        val files = tmp.newFolder("files")
        val entries = listOf("project.xml" to scene().toByteArray(), "manifest.json" to "{}".toByteArray()) + (1..4).map { "media/$it.bin" to ByteArray(1) }
        assertTrue(failure(importer(files, ImportLimits(entries = 5)), zip(entries)).contains("too many files"))
        assertEquals(1, importer(files, ImportLimits(entries = 6)).import { ByteArrayInputStream(zip(entries)) }.size)
    }

    @Test fun aPackageWithTooManyScenesIsRefused() {
        val files = tmp.newFolder("files")
        val bytes = zip((1..3).map { "s$it.xml" to scene().toByteArray() } + ("manifest.json" to "{}".toByteArray()))
        assertTrue(failure(importer(files, ImportLimits(scenes = 2)), bytes).contains("too many projects"))
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(3, importer(files, ImportLimits(scenes = 3)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun aPackageThatUnpacksToTooMuchIsRefusedEvenForEntriesNothingUses() {
        val files = tmp.newFolder("files")
        val bytes = zip(listOf("project.xml" to scene().toByteArray(), "manifest.json" to "{}".toByteArray(), "unused.bin" to ByteArray(300_000)))
        assertTrue(bytes.size < 5_000)
        assertTrue(failure(importer(files, ImportLimits(unpacked = 100_000)), bytes).contains("too large"))
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(1, importer(files, ImportLimits(unpacked = 400_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun theCopiesOfSeveralScenesTogetherAreLimitedToo() {
        val files = tmp.newFolder("files")
        fun withPicture(n: Int) = scene("""<shape id="$n" fillType="media" fillImage="x://a"/>""")
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg")))
        val bytes = zip(listOf("one.xml" to withPicture(1).toByteArray(), "two.xml" to withPicture(2).toByteArray(), "a.jpg" to ByteArray(60_000), "manifest.json" to manifest.toString().toByteArray()))
        assertTrue(failure(importer(files, ImportLimits(unpacked = 100_000)), bytes).contains("too large"))
        assertEquals(2, importer(files, ImportLimits(unpacked = 130_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun projectFilesThatAreTooLargeTogetherAreRefused() {
        val files = tmp.newFolder("files")
        val big = scene("""<shape id="1" label="${"x".repeat(2_000)}"/>""").toByteArray()
        val bytes = zip(listOf("one.xml" to big, "two.xml" to big, "manifest.json" to "{}".toByteArray()))
        assertTrue(failure(importer(files, ImportLimits(sceneText = 3_000)), bytes).contains("too large"))
        assertEquals(2, importer(files, ImportLimits(sceneText = 5_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun theDefaultLimitsAreTheDocumentedOnes() {
        val limits = ImportLimits()
        assertEquals(2_000, limits.entries)
        assertEquals(32, limits.scenes)
        assertEquals(2L * 1024 * 1024 * 1024, limits.unpacked)
        assertEquals(64 * 1024 * 1024, limits.sceneText)
        XmlNode.parse(scene())
    }

    @Test fun aDeeplyNestedFileIsRefusedInsteadOfOverflowingTheStack() {
        val files = tmp.newFolder("files")
        fun nested(depth: Int) = scene("<shape id=\"1\">" + "<a>".repeat(depth) + "</a>".repeat(depth) + "</shape>")
        var outcome: Any? = null
        val worker = Thread(null, {
            outcome = try { failure(importer(files), nested(20_000).toByteArray()) } catch (t: Throwable) { t }
        }, "small-stack", 512L * 1024)
        worker.start(); worker.join()
        assertTrue("$outcome", outcome is String && (outcome as String).startsWith("Malformed XML"))
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(1, importer(files).import { ByteArrayInputStream(nested(200).toByteArray()) }.size)
    }
}
