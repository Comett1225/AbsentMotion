package app.absentmotion.library

import app.absentmotion.i18n.txt
import app.absentmotion.i18n.UiText
import app.absentmotion.R
import app.absentmotion.TestData
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import app.absentmotion.model.mediaUris
import app.absentmotion.model.replaceMedia
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

class ImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val samples = TestData.scenes

    private fun store() = ProjectStore(tmp.newFolder())

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun assertSame(expected: XmlNode, actual: XmlNode, root: Boolean = true, where: String = "scene") {
        fun attrs(n: XmlNode) = n.attrs.entries.filterNot { root && it.key == "modifiedTime" }.map { it.key to it.value }
        assertEquals("attributes of $where", attrs(expected), attrs(actual))
        assertEquals("text of $where", expected.text, actual.text)
        assertEquals("children of $where", expected.children.map { it.name }, actual.children.map { it.name })
        expected.children.zip(actual.children).forEachIndexed { i, (a, b) -> assertSame(a, b, false, "$where/${a.name}[$i]") }
    }

    private fun saved(store: ProjectStore, report: ImportReport) = store.load(report.id)!!.node

    @Test fun plainXmlRoundTrips() {
        assertTrue(samples.size >= 6)
        for (file in samples) {
            val store = store()
            val report = Importer(store).import { file.inputStream() }.single()
            assertSame(XmlNode.parse(file.readText()), saved(store, report))
            println("${file.name}: ${report.sections.map { it.first to it.second }}")
        }
    }

    @Test fun absentMotionPackageRoundTrips() {
        for (file in samples) {
            val original = Scene.parse(file.readText())
            val uris = original.node.mediaUris().toList()
            val entries = uris.mapIndexed { i, uri -> uri to "media/${i + 1}_${original.media(uri)?.filename ?: "file"}" }
            val manifest = JSONObject().put("format", "absentmotion-package").put("version", 1).put("media", JSONArray().also { list ->
                entries.forEach { (uri, entry) -> list.put(JSONObject().put("uri", uri).put("entry", entry)) }
            })
            val bytes = zip(listOf("project.xml" to file.readBytes()) + entries.map { (_, entry) -> entry to "bytes of $entry".toByteArray() } +
                ("manifest.json" to manifest.toString().toByteArray()))
            val store = store()
            val report = Importer(store).import { ByteArrayInputStream(bytes) }.single()
            val back = entries.associate { (uri, entry) -> store.mediaDir(report.id).resolve(entry.substringAfterLast('/')).toURI().toString() to uri }
            assertEquals(uris.size, back.size)
            assertSame(original.node, saved(store, report).mapMediaUris { back[it] ?: it })
            for ((_, entry) in entries) assertEquals("bytes of $entry", store.mediaDir(report.id).resolve(entry.substringAfterLast('/')).readText())
        }
    }

    @Test fun otherPackageLayoutFindsMediaBySignatureAndName() {
        val scene = """<?xml version='1.0' encoding='UTF-8' ?>
            <scene title="P" width="100" height="100" fps="30" totalTime="1000" type="element">
              <media uri="amproj:one.jpg" filename="a.jpg" sig="abc123" />
              <media uri="amproj:two.png" filename="b.png" />
              <media uri="amproj:three.mp4" filename="c.mp4" sig="nope" />
              <shape id="1" startTime="0" endTime="999" fillType="media" fillImage="amproj:one.jpg" s=".rect" />
              <shape id="2" startTime="0" endTime="999" fillType="media" fillImage="amproj:two.png" s=".rect" />
              <shape id="3" startTime="0" endTime="999" fillType="media" fillVideo="amproj:three.mp4" s=".rect" />
              <widget id="4" />
            </scene>"""
        val bytes = zip(listOf(
            "1111-2222.xml" to scene.toByteArray(), "manifest.txt" to "abc123:ph.jpg\nxx:yy:two.png\n".toByteArray(),
            "ph.jpg" to "jpg".toByteArray(), "two.png" to "png".toByteArray(),
        ))
        val store = store()
        val report = Importer(store, exists = { it.startsWith("file:") }).import { ByteArrayInputStream(bytes) }.single()
        val node = saved(store, report)
        val dir = store.mediaDir(report.id)
        assertEquals(dir.resolve("ph.jpg").toURI().toString(), node.children.first { it["id"] == "1" }["fillImage"])
        assertEquals(dir.resolve("two.png").toURI().toString(), node.children.first { it["id"] == "2" }["fillImage"])
        assertEquals("amproj:three.mp4", node.children.first { it["id"] == "3" }["fillVideo"])
        assertEquals("element", node["type"])
        assertTrue(node.children.any { it.name == "widget" })
        assertEquals(true, report.element)
        assertEquals(listOf(reportText("<widget>")), report.sections.first { it.first == txt(R.string.library_report_unknown) }.second)
        assertEquals(listOf(reportText("c.mp4")), report.sections.first { it.first == txt(R.string.library_report_media) }.second)
        assertEquals("true", node.children.first { it.name == "media" && it["uri"] == "amproj:three.mp4" }["missing"])
        assertFalse(node.children.first { it.name == "media" && it["filename"] == "a.jpg" }.attrs.containsKey("missing"))
    }

    @Test fun namesInsideAZipCannotLeaveTheProjectFolder() {
        val scene = """<scene width="10" height="10"><shape id="1" fillImage="x://a" /></scene>"""
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "../../../escape.jpg")))
        val bytes = zip(listOf("../../../p.xml" to scene.toByteArray(), "../../../escape.jpg" to "x".toByteArray(), "manifest.json" to manifest.toString().toByteArray()))
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val report = Importer(store).import { ByteArrayInputStream(bytes) }.single()
        assertEquals(listOf("escape.jpg"), store.mediaDir(report.id).list()!!.toList())
        assertFalse(File(root.parentFile, "escape.jpg").exists())
        store.delete(report.id)
        assertFalse(store.mediaDir(report.id).exists())
        assertEquals(null, store.load(report.id))
    }

    @Test fun missingMediaAndUnknownThingsAreReportedNotDropped() {
        val file = TestData.scene("media")
        val store = store()
        val report = Importer(store, exists = { false }, canRun = { false }, hasFont = { false }).import { file.inputStream() }.single()
        assertEquals(listOf("test-image.jpg", "test-alpha.png", "test-video.mp4", "test-audio.m4a").map(::reportText), report.sections.first { it.first == txt(R.string.library_report_media) }.second)
        assertEquals(4, saved(store, report).children.count { it.name == "media" && it["missing"] == "true" })
        val effect = """<scene width="10" height="10"><text id="1" font="imported?name=Foo.ttf"><effect id="blur" /></text>
            <shape id="2"><effect id="blur" /><effect id="glow" /></shape></scene>"""
        val sections = Importer(store, canRun = { false }, hasFont = { false }).import { effect.byteInputStream() }.single().sections.toMap()
        assertEquals(listOf(UiText.plural(R.plurals.library_report_effect_layers, 2, "blur", "2"), UiText.plural(R.plurals.library_report_effect_layers, 1, "glow", "1")), sections[txt(R.string.library_report_cannot_run)])
        assertEquals(listOf(reportText("Foo.ttf")), sections[txt(R.string.library_report_fonts)])
    }

    @Test fun otherFilesAreRefusedWithTheImportersMessages() {
        fun failure(bytes: ByteArray) = try { Importer(store()).import { ByteArrayInputStream(bytes) }; fail("imported"); txt(0) } catch (e: ImportFailed) { e.text }
        assertEquals(txt(R.string.library_err_unsupported_type), failure("hello".toByteArray()))
        assertEquals(txt(R.string.library_err_missing_manifest), failure(zip(listOf("a.xml" to "<scene width=\"1\" height=\"1\"/>".toByteArray()))))
        assertEquals(R.string.library_err_scene_needs_size, failure("<html/>".toByteArray()).id)
        assertEquals(R.string.library_err_malformed_xml, failure("<scene width=\"1\"".toByteArray()).id)
    }

    @Test fun aZeroFrameRateFallsBackAndImportRefusesBadRatesAndSizes() {
        val zero = Scene.parse("""<scene width="10" height="10" fps="0" totalTime="1000"/>""")
        assertEquals(30, zero.fps)
        assertEquals(1000, zero.frameStart(30))
        assertEquals(30, zero.lastFrame)
        fun failure(xml: String) = try { Importer(store()).import { xml.byteInputStream() }; fail("imported $xml"); txt(0) } catch (e: ImportFailed) { e.text }
        assertEquals(R.string.library_err_scene_fps, failure("""<scene width="10" height="10" fps="0"/>""").id)
        assertEquals(R.string.library_err_scene_fps, failure("""<scene width="10" height="10" fps="1000"/>""").id)
        assertEquals(R.string.library_err_scene_too_big, failure("""<scene width="100000" height="10" fps="30"/>""").id)
        assertEquals(R.string.library_err_scene_too_big, failure("""<scene width="10" height="100000"/>""").id)
        val store = store()
        assertEquals(240, store.load(Importer(store).import { """<scene width="16384" height="10" fps="240"/>""".byteInputStream() }.single().id)!!.fps)
    }

    @Test fun duplicateAndMissingLayerIdsAreRenumberedAndParentsKeepTheFirstOwner() {
        val xml = """<scene width="10" height="10">
            <shape id="5" startTime="0" endTime="99" />
            <shape id="5" parent="5" startTime="0" endTime="99" />
            <shape startTime="0" endTime="99" />
            <shape id="0" parent="5" startTime="0" endTime="99" />
            <embedScene id="7" startTime="0" endTime="99"><scene width="10" height="10">
              <shape id="5" startTime="0" endTime="99" />
              <shape id="8" parent="5" startTime="0" endTime="99" />
              <shape id="8" startTime="0" endTime="99" />
            </scene></embedScene>
        </scene>"""
        val store = store()
        val top = store.load(Importer(store).import { xml.byteInputStream() }.single().id)!!.layers
        val inner = top[4].nested!!.layers
        val ids = (top + inner).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(0L in ids)
        assertEquals(listOf(5L, 7L, 8L), listOf(top[0].id, top[4].id, inner[1].id))
        assertEquals(listOf("5", "5"), listOf(top[1].node["parent"], top[3].node["parent"]))
        assertEquals("${inner[0].id}", inner[1].node["parent"])
    }

    @Test fun renumberedLayersKeepEveryReferenceFromTheirSiblings() {
        val xml = """<scene width="10" height="10">
            <shape id="5" startTime="0" endTime="99" />
            <embedScene id="7" startTime="0" endTime="99"><scene width="10" height="10">
              <shape id="5" startTime="0" endTime="99" />
              <shape id="6" parent="5" pathLayer="5" noiseId="5" genIn="t fade 100 0 0 5" startTime="0" endTime="99" />
              <shape id="9" genOut="t fade 100 0 0 6" startTime="0" endTime="99" />
            </scene></embedScene>
        </scene>"""
        val store = store()
        val top = store.load(Importer(store).import { xml.byteInputStream() }.single().id)!!.layers
        val inner = top[1].nested!!.layers
        val moved = inner[0].id
        assertTrue(moved != 5L)
        assertEquals(5L, top[0].id)
        assertEquals(listOf("$moved", "$moved", "$moved"), listOf("parent", "pathLayer", "noiseId").map { inner[1].node[it] })
        assertEquals("t fade 100 0 0 $moved", inner[1].node["genIn"])
        assertEquals("t fade 100 0 0 6", inner[2].node["genOut"])
    }

    @Test fun aCopyOwnsItsMediaAndReplacingMediaSwapsEveryUse() {
        val scene = """<scene width="10" height="10"><shape id="1" fillType="media" fillImage="x://a" /><shape id="2" fillType="media" fillImage="x://a" /></scene>"""
        val bytes = zip(listOf("p.xml" to scene.toByteArray(), "a.jpg" to "jpg".toByteArray(),
            "manifest.json" to JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg"))).toString().toByteArray()))
        val store = store()
        val id = Importer(store).import { ByteArrayInputStream(bytes) }.single().id
        val copy = store.duplicate(id)!!
        store.delete(id)
        val uri = store.load(copy)!!.node.mediaUris().single()
        assertTrue(uri.contains(copy) && File(java.net.URI(uri)).readText() == "jpg")
        val swapped = store.load(copy)!!.replaceMedia(uri, MediaDeclaration(XmlNode("media", mapOf("uri" to "y://b"))))
        assertEquals(setOf("y://b"), swapped.node.mediaUris())
        assertEquals(listOf("y://b"), swapped.media.map { it.uri })
    }

    @Test fun mediaWithTheSameNameInDifferentFoldersStaySeparate() {
        val scene = """<scene width="10" height="10"><shape id="1" fillType="media" fillImage="x://a" /><shape id="2" fillType="media" fillImage="x://b" /></scene>"""
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a/clip.jpg"))
            .put(JSONObject().put("uri", "x://b").put("entry", "b/clip.jpg")))
        for (top in listOf("", "Top/")) {
            val bytes = zip(listOf("${top}p.xml" to scene.toByteArray(), "${top}a/clip.jpg" to "one".toByteArray(), "${top}b/clip.jpg" to "two".toByteArray(),
                "${top}manifest.json" to manifest.toString().toByteArray()))
            val store = store()
            val node = saved(store, Importer(store).import { ByteArrayInputStream(bytes) }.single())
            val files = listOf("1", "2").map { id -> File(java.net.URI(node.children.first { it["id"] == id }["fillImage"]!!)) }
            assertEquals(listOf("one", "two"), files.map { it.readText() })
            assertEquals(2, files.toSet().size)
        }
    }

    @Test fun ownPackagesComeBackAsProjectsAndOtherPackagesAsTemplates() {
        val scene = """<scene width="10" height="10"><shape id="1" fillType="media" fillImage="x://a" /></scene>"""
        fun manifest(format: String?) = JSONObject().apply { format?.let { put("format", it) } }
            .put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg"))).toString().toByteArray()
        fun import(format: String?) = Importer(store()).import {
            ByteArrayInputStream(zip(listOf("p.xml" to scene.toByteArray(), "a.jpg" to "jpg".toByteArray(), "manifest.json" to manifest(format))))
        }.single()
        assertFalse(import("absentmotion-package").template)
        assertTrue(import(null).template)
        val alight = zip(listOf("p.xml" to scene.toByteArray(), "manifest.txt" to "sig:a.jpg\n".toByteArray(), "a.jpg" to "jpg".toByteArray()))
        assertTrue(Importer(store()).import { ByteArrayInputStream(alight) }.single().template)
    }

    @Test fun aPackageWithSeveralScenesIsReadInThreePassesAndEachProjectOwnsItsMedia() {
        val scene = """<scene width="10" height="10"><shape id="1" fillType="media" fillImage="x://a" /></scene>"""
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg")))
        val bytes = zip(listOf("p1.xml" to scene.toByteArray(), "p2.xml" to scene.toByteArray(), "p3.xml" to scene.toByteArray(),
            "a.jpg" to "jpg".toByteArray(), "manifest.json" to manifest.toString().toByteArray()))
        val store = store()
        var opened = 0
        val reports = Importer(store).import { opened++; ByteArrayInputStream(bytes) }
        assertEquals(4, opened)
        assertEquals(3, reports.map { it.id }.toSet().size)
        val files = reports.map { File(java.net.URI(saved(store, it).children.first { c -> c.name == "shape" }["fillImage"]!!)) }
        assertEquals(3, files.toSet().size)
        files.forEachIndexed { i, f -> assertEquals("jpg", f.readText()); assertTrue(f.path.contains(reports[i].id)) }
    }

    @Test fun aBadSceneInAPackageLeavesNoProjectOrMediaBehind() {
        val good = """<scene width="10" height="10"><shape id="1" fillType="media" fillImage="x://a" /></scene>"""
        val bad = """<scene width="10" height="10" fps="100000"/>"""
        val manifest = JSONObject().put("media", JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "a.jpg")))
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        try {
            Importer(store).import { ByteArrayInputStream(zip(listOf("p1.xml" to good.toByteArray(), "p2.xml" to bad.toByteArray(),
                "a.jpg" to "jpg".toByteArray(), "manifest.json" to manifest.toString().toByteArray()))) }
            fail("imported")
        } catch (e: ImportFailed) {
            assertEquals(R.string.library_err_scene_fps, e.text.id)
        }
        assertEquals(emptyList<ProjectInfo>(), store.list(includeDamaged = true))
        assertFalse(root.resolve("media").walkTopDown().any { it.isFile })
    }
}
