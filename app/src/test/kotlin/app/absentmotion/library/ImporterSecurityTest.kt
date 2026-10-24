package app.absentmotion.library

import app.absentmotion.i18n.txt
import app.absentmotion.i18n.UiText
import app.absentmotion.R
import app.absentmotion.model.Scene
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

    private val tooLarge = setOf(R.string.library_err_package_large, R.string.library_err_media_large, R.string.library_err_project_files_large, R.string.library_err_xml_large)
    private val malformedScene = setOf(R.string.library_err_no_project, R.string.library_err_scene_needs_size, R.string.library_err_scene_too_big, R.string.library_err_scene_fps)

    private fun failure(importer: Importer, bytes: ByteArray): UiText =
        try { importer.import { ByteArrayInputStream(bytes) }; fail("imported"); txt(0) } catch (e: ImportFailed) { e.text }

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
            """<shape id="7" fillType="media" fillVideo="https://example.com/a.mp4"/>""",
            """<audio id="8" src="/etc/hosts"/>""",
        )
        val store = ProjectStore(files)
        val report = Importer(store, access = MediaAccess.forPhone(files)).import { xml.byteInputStream() }.single()
        val node = store.load(report.id)!!.node
        val uris = node.mediaUris()
        assertEquals(setOf("blocked-$settings", "blocked-$project", "blocked-$own", "blocked-content://media/external/images/media/5", "blocked-file:///etc/hosts", "blocked-x://elsewhere", "blocked-https://example.com/a.mp4", "blocked-/etc/hosts"), uris)
        assertFalse(node.toXml().contains("\"$settings\"") || node.toXml().contains("\"$project\""))
        val missing = node.children.filter { it.name == "media" && it["missing"] == "true" }.map { it["uri"] }.toSet()
        assertEquals(setOf("blocked-$settings", "blocked-$project", "blocked-$own", "blocked-content://media/external/images/media/5", "blocked-file:///etc/hosts", "blocked-x://elsewhere", "blocked-https://example.com/a.mp4", "blocked-/etc/hosts"), missing)
        assertEquals(listOf(reportText("settings.json")), report.sections.first { it.first == txt(R.string.library_report_media) }.second.filter { it == reportText("settings.json") })
    }

    @Test fun aHiddenLayerInAnImportedFileCannotPullThePhonesPhotosIntoAPackage() {
        val files = tmp.newFolder("files")
        val photo = "content://media/external/images/media/42"
        val xml = scene(
            """<media uri="$photo" filename="IMG_0042.jpg" size="1234" type="image/jpeg"/>""",
            """<shape id="1" fillType="media" fillImage="$photo" hidden="true"/>""",
        )
        val store = ProjectStore(files)
        val report = Importer(store, access = MediaAccess.forPhone(files)).import { xml.byteInputStream() }.single()
        val imported = store.load(report.id)!!
        assertEquals(setOf("blocked-$photo"), imported.node.mediaUris())
        assertTrue(report.sections.first { it.first == txt(R.string.library_report_media) }.second.contains(reportText("IMG_0042.jpg")))
        val opened = ArrayList<String>()
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking {
            app.absentmotion.export.writeProjectPackage(imported, imported.toXml(), out, MediaAccess.forPhone(files), { opened += it; "private pixels".byteInputStream() }, {})
        }
        assertEquals(emptyList<String>(), opened)
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("private pixels"))
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
        assertEquals(R.string.library_err_too_many_files, failure(importer(files, ImportLimits(entries = 5)), zip(entries)).id)
        assertEquals(1, importer(files, ImportLimits(entries = 6)).import { ByteArrayInputStream(zip(entries)) }.size)
    }

    @Test fun aPackageWithTooManyScenesIsRefused() {
        val files = tmp.newFolder("files")
        val bytes = zip((1..3).map { "s$it.xml" to scene().toByteArray() } + ("manifest.json" to "{}".toByteArray()))
        assertEquals(R.string.library_err_too_many_projects, failure(importer(files, ImportLimits(scenes = 2)), bytes).id)
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(3, importer(files, ImportLimits(scenes = 3)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun aPackageThatUnpacksToTooMuchIsRefusedEvenForEntriesNothingUses() {
        val files = tmp.newFolder("files")
        val bytes = zip(listOf("project.xml" to scene().toByteArray(), "manifest.json" to "{}".toByteArray(), "unused.bin" to ByteArray(300_000)))
        assertTrue(bytes.size < 5_000)
        assertTrue(failure(importer(files, ImportLimits(unpacked = 100_000)), bytes).id in tooLarge)
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(1, importer(files, ImportLimits(unpacked = 400_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun theCopiesOfSeveralScenesTogetherAreLimitedToo() {
        val files = tmp.newFolder("files")
        fun withPicture(n: Int) = scene("""<shape id="$n" fillType="media" fillImage="x://a"/>""")
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg")))
        val bytes = zip(listOf("one.xml" to withPicture(1).toByteArray(), "two.xml" to withPicture(2).toByteArray(), "a.jpg" to ByteArray(60_000), "manifest.json" to manifest.toString().toByteArray()))
        assertTrue(failure(importer(files, ImportLimits(unpacked = 100_000)), bytes).id in tooLarge)
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(emptyList<String>(), File(files, "media").list().orEmpty().toList())
        assertEquals(2, importer(files, ImportLimits(unpacked = 130_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun projectFilesThatAreTooLargeTogetherAreRefused() {
        val files = tmp.newFolder("files")
        val big = scene("""<shape id="1" label="${"x".repeat(2_000)}"/>""").toByteArray()
        val bytes = zip(listOf("one.xml" to big, "two.xml" to big, "manifest.json" to "{}".toByteArray()))
        assertTrue(failure(importer(files, ImportLimits(sceneText = 3_000)), bytes).id in tooLarge)
        assertEquals(2, importer(files, ImportLimits(sceneText = 5_000)).import { ByteArrayInputStream(bytes) }.size)
    }

    @Test fun theDefaultLimitsAreTheDocumentedOnes() {
        val limits = ImportLimits()
        assertEquals(2_000, limits.entries)
        assertEquals(32, limits.scenes)
        assertEquals(2L * 1024 * 1024 * 1024, limits.unpacked)
        assertEquals(32 * 1024 * 1024, limits.sceneText)
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
        assertTrue("$outcome", outcome is UiText && (outcome as UiText).id == R.string.library_err_malformed_xml)
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(1, importer(files).import { ByteArrayInputStream(nested(200).toByteArray()) }.size)
    }

    @Test fun aPackageWhoseLaterProjectIsBrokenLeavesNothingBehind() {
        val files = tmp.newFolder("files")
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg")))
        val bytes = zip(listOf("ok.xml" to scene("""<shape id="1" fillType="media" fillImage="x://a"/>""").toByteArray(),
            "bad.xml" to "<scene title=\"Broken\" height=\"10\"/>".toByteArray(), "a.jpg" to "jpg".toByteArray(), "manifest.json" to manifest.toString().toByteArray()))
        assertTrue(failure(importer(files), bytes).id in malformedScene)
        assertEquals(emptyList<ProjectInfo>(), ProjectStore(files).list())
        assertEquals(emptyList<String>(), File(files, "media").list().orEmpty().toList())
    }

    @Test fun aLinkOnlyPullsInAnElementNeverAnotherProject() {
        val files = tmp.newFolder("files")
        val store = ProjectStore(files)
        val private = store.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Private"))))
        val element = store.createElement(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Shared", "type" to "element"))))
        fun embedded(id: String): String? {
            val xml = scene("""<embedScene id="1" startTime="0" endTime="100" link="${store.linkOf(id)}"><scene width="10" height="10" title="Original"/></embedScene>""")
            val report = Importer(store, access = MediaAccess.forPhone(files)).import { xml.byteInputStream() }.single()
            return store.load(report.id)!!.layers.single().node.child("scene")?.get("title")
        }
        assertEquals("Original", embedded(private))
        assertEquals("Shared", embedded(element))
    }

    @Test fun aSceneWithMoreElementsThanTheBudgetIsRefused() {
        val files = tmp.newFolder("files")
        val xml = scene("<bookmark/>".repeat(XmlNode.IMPORT_NODES))
        assertEquals(txt(R.string.library_err_malformed_xml, "Too many elements"), failure(importer(files), xml.toByteArray()))
    }

    @Test fun aSceneLastingLongerThanADayIsRefused() {
        val files = tmp.newFolder("files")
        val xml = """<scene title="Long" width="10" height="10" totalTime="2000000000"/>"""
        assertEquals(txt(R.string.library_err_scene_long, "24"), failure(importer(files), xml.toByteArray()))
    }
}
