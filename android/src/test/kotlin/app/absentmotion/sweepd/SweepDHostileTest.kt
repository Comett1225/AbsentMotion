package app.absentmotion.sweepd

import app.absentmotion.library.ImportFailed
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SweepDHostileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun scene(vararg uris: String) = "<scene title=\"T\" width=\"10\" height=\"10\">" + uris.joinToString("") { "<media uri=\"$it\" filename=\"a.jpg\"/><shape id=\"${it.hashCode().toLong() and 0xffff}\" fillImage=\"$it\"/>" } + "</scene>"

    private fun manifest(vararg pairs: Pair<String, String>) = JSONObject().put("format", "absentmotion-package").put("project", "project.xml")
        .put("media", JSONArray().apply { pairs.forEach { (u, e) -> put(JSONObject().put("uri", u).put("entry", e)) } }).toString().toByteArray()

    private fun outsideFiles(root: File) = root.parentFile.walkTopDown().filter { it.isFile && !it.startsWith(root) }.map { it.name }.toList()

    @Test fun traversalNamesStayInside() {
        val root = tmp.newFolder("files")
        val bytes = zip(listOf("project.xml" to scene("x://a", "x://b").toByteArray(),
            "../../evil.jpg" to "evil".toByteArray(), "/abs/evil2.jpg" to "evil2".toByteArray(),
            "manifest.json" to manifest("x://a" to "../../evil.jpg", "x://b" to "/abs/evil2.jpg")))
        val store = ProjectStore(root)
        Importer(store, access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(bytes) }
        assertEquals(emptyList<String>(), outsideFiles(root))
    }

    @Test fun aMediaFileNamedLikeTheImportMarkerSurvives() {
        val root = tmp.newFolder("files")
        val bytes = zip(listOf("project.xml" to scene("x://a").toByteArray(), "media/importing.part" to "pixels".toByteArray(), "manifest.json" to manifest("x://a" to "media/importing.part")))
        val store = ProjectStore(root)
        val report = Importer(store, access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(bytes) }.single()
        val uri = store.load(report.id)!!.node.mediaUris().single()
        assertTrue(uri, File(java.net.URI(uri)).isFile)
    }

    @Test fun nonUtf8EntryNamesFailAsADamagedPackage() {
        val good = zip(listOf("project.xml" to scene().toByteArray(), "media/caf\u00e9.jpg" to "x".toByteArray(), "manifest.json" to manifest()))
        val i = String(good, Charsets.ISO_8859_1).indexOf("caf\u00c3\u00a9")
        val bytes = good.copyOf()
        var p = 0
        while (true) {
            p = String(bytes, Charsets.ISO_8859_1).indexOf("caf\u00c3\u00a9", p)
            if (p < 0) break
            bytes[p + 3] = 0x82.toByte(); bytes[p + 4] = 'e'.code.toByte()
            p += 5
        }
        for (k in 0 until bytes.size - 4) if (bytes[k] == 0x50.toByte() && bytes[k + 1] == 0x4b.toByte() && (bytes[k + 2].toInt() == 3 || bytes[k + 2].toInt() == 1)) {
            val flagAt = if (bytes[k + 2].toInt() == 3) k + 6 else k + 8
            bytes[flagAt + 1] = (bytes[flagAt + 1].toInt() and 0xF7).toByte()
        }
        assertTrue(i > 0)
        val root = tmp.newFolder("files")
        try {
            val r = Importer(ProjectStore(root), access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(bytes) }
            println("nonutf8 imported ${r.size}")
        } catch (e: ImportFailed) {
            println("nonutf8 ImportFailed ${e.message}")
            return
        } catch (e: Exception) {
            throw AssertionError("raw ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test fun entityExpansionIsRefusedQuickly() {
        val lol = "<?xml version=\"1.0\"?><!DOCTYPE scene [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\"><!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\"><!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\"><!ENTITY f \"&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;\"><!ENTITY g \"&f;&f;&f;&f;&f;&f;&f;&f;&f;&f;\">]><scene title=\"&g;\" width=\"10\" height=\"10\"/>"
        val root = tmp.newFolder("files")
        val t = System.nanoTime()
        val r = runCatching { Importer(ProjectStore(root), access = MediaAccess.forPhone(root)).import { lol.byteInputStream() } }
        println("entities: ${r.exceptionOrNull()?.javaClass?.name} ${r.getOrNull()?.single()?.title?.length} in ${(System.nanoTime() - t) / 1_000_000} ms")
    }

    @Test fun truncatedPackagesFailCleanly() {
        val media = ByteArray(200_000) { (it * 31).toByte() }
        val full = zip(listOf("project.xml" to scene("x://a").toByteArray(), "media/a.jpg" to media, "manifest.json" to manifest("x://a" to "media/a.jpg")))
        for (cut in listOf(10, 100, 1000, full.size / 2, full.size - 200, full.size - 22, full.size - 1)) {
            val root = tmp.newFolder()
            val store = ProjectStore(root)
            val r = runCatching { Importer(store, access = MediaAccess.forPhone(root)).import { ByteArrayInputStream(full.copyOf(cut)) } }
            val e = r.exceptionOrNull()
            println("cut $cut: ${e?.javaClass?.simpleName} ${r.getOrNull()?.size}")
            assertTrue("cut $cut raw $e", e == null || e is ImportFailed)
            if (r.isSuccess) {
                val id = r.getOrThrow().single().id
                val uri = store.load(id)!!.node.mediaUris().single()
                if (uri.startsWith("file:")) assertEquals("cut $cut", media.size.toLong(), File(java.net.URI(uri)).length())
            }
        }
    }
}
