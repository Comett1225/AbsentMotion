package app.absentmotion.library

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
        assertEquals(listOf("<widget>"), report.sections.first { it.first.startsWith("Not recognised") }.second)
        assertEquals(listOf("c.mp4"), report.sections.first { it.first == "Media not found" }.second)
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
        assertEquals(listOf("test-image.jpg", "test-alpha.png", "test-video.mp4", "test-audio.m4a"), report.sections.first { it.first == "Media not found" }.second)
        assertEquals(4, saved(store, report).children.count { it.name == "media" && it["missing"] == "true" })
        val effect = """<scene width="10" height="10"><text id="1" font="imported?name=Foo.ttf"><effect id="blur" /></text>
            <shape id="2"><effect id="blur" /><effect id="glow" /></shape></scene>"""
        val sections = Importer(store, canRun = { false }, hasFont = { false }).import { effect.byteInputStream() }.single().sections.toMap()
        assertEquals(listOf("blur (2 layers)", "glow (1 layer)"), sections["Effects this version cannot run"])
        assertEquals(listOf("Foo.ttf"), sections["Fonts not installed"])
    }

    @Test fun otherFilesAreRefusedWithTheImportersMessages() {
        fun failure(bytes: ByteArray) = try { Importer(store()).import { ByteArrayInputStream(bytes) }; fail("imported"); "" } catch (e: ImportFailed) { e.message!! }
        assertEquals("Unsupported file type", failure("hello".toByteArray()))
        assertEquals("Project package missing manifest", failure(zip(listOf("a.xml" to "<scene width=\"1\" height=\"1\"/>".toByteArray()))))
        assertTrue(failure("<html/>".toByteArray()).startsWith("Malformed Scene"))
        assertTrue(failure("<scene width=\"1\"".toByteArray()).startsWith("Malformed XML"))
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
}
