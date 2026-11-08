package app.absentmotion.sweepd

import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.mediaUris
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SweepDFixTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun manifest(media: JSONArray) = JSONObject().put("format", "absentmotion-package").put("project", "project.xml").put("media", media).toString().toByteArray()

    @Test fun theAppsOwnLayerAttributesAreNotReportedAsUnknown() {
        val root = tmp.newFolder()
        val xml = """<scene title="T" width="10" height="10"><shape id="1" startTime="0" endTime="999" genIn="fade" genOut="fade" genLoop="wiggle" outlineShape="gear" outlineMark="0000abcd"/></scene>"""
        val report = Importer(ProjectStore(root), access = MediaAccess.forPhone(root)).import { xml.byteInputStream() }.single()
        assertEquals(null, report.sections.firstOrNull { it.first == txt(R.string.library_report_unknown) }?.second)
    }

    @Test fun aMediaFileNamedLikeTheImportMarkerIsKept() {
        val root = tmp.newFolder()
        val xml = """<scene title="T" width="10" height="10"><media uri="x://a" filename="a.jpg"/><shape id="1" fillImage="x://a"/></scene>"""
        val bytes = zip(listOf("project.xml" to xml.toByteArray(), "media/importing.part" to "pixels".toByteArray(),
            "manifest.json" to manifest(JSONArray().put(JSONObject().put("uri", "x://a").put("entry", "media/importing.part")))))
        val store = ProjectStore(root)
        val report = Importer(store, access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(bytes) }.single()
        val uri = store.load(report.id)!!.node.mediaUris().single()
        assertEquals("pixels", File(URI(uri)).readText())
    }

    @Test fun modelFileNamesFromAPhoneAreSafeOnWindows() {
        val root = tmp.newFolder()
        val xml = """<scene title="T" width="10" height="10"><media uri="x://m" filename="car:v2.obj"/><model id="1" src="x://m"/></scene>"""
        val bytes = zip(listOf("project.xml" to xml.toByteArray(), "media/1_model/car:v2.obj" to "v 0 0 0".toByteArray(), "media/1_model/tex?.png" to "png".toByteArray(),
            "manifest.json" to manifest(JSONArray().put(JSONObject().put("uri", "x://m").put("entry", "media/1_model/car:v2.obj").put("companions", JSONArray().put("media/1_model/tex?.png"))))))
        val store = ProjectStore(root)
        val report = Importer(store, access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(bytes) }.single()
        val names = store.mediaDir(report.id).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(2, names.size)
        assertTrue(names.toString(), names.none { n -> n.any { it in "<>:\"|?*" } })
        assertTrue(File(URI(store.load(report.id)!!.node.mediaUris().single())).isFile)
    }
}
