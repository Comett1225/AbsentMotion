package app.absentmotion.sweepd

import app.absentmotion.TestData
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mapMediaUris
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

class SweepDRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun norm(node: XmlNode, files: File): XmlNode {
        val base = files.toURI().toString()
        val n = node.mapMediaUris { u -> if (u.startsWith(base)) "M:" + u.removePrefix(base).replace(Regex("^media/p[0-9]+/"), "").replace(Regex("model-[0-9]+/"), "model/").replace(Regex("^([0-9]+_)+"), "").replace(Regex("%28|%29|[()]"), "") else u }
        return strip(n, true)
    }

    private fun strip(n: XmlNode, root: Boolean): XmlNode = n.copy(attrs = n.attrs.filterKeys { !(root && it == "modifiedTime") }, children = n.children.map { strip(it, false) })

    private fun pack(scene: Scene, files: File): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forPhone(files), { File(URI(it)).inputStream() }, {})
        out.toByteArray()
    }

    private fun diff(a: XmlNode, b: XmlNode, where: String = "scene"): String? {
        if (a.name != b.name) return "$where name ${a.name} vs ${b.name}"
        if (a.attrs != b.attrs) return "$where attrs ${(a.attrs.entries - b.attrs.entries)} vs ${(b.attrs.entries - a.attrs.entries)}"
        if (a.text != b.text) return "$where text"
        if (a.children.size != b.children.size) return "$where children ${a.children.map { it.name }} vs ${b.children.map { it.name }}"
        a.children.zip(b.children).forEachIndexed { i, (x, y) -> diff(x, y, "$where/${x.name}[$i]")?.let { return it } }
        return null
    }

    private fun roundTrip(label: String, first: ByteArray) {
        val f1 = tmp.newFolder()
        val s1 = ProjectStore(f1)
        val r1 = Importer(s1, access = MediaAccess.forPhone(f1)).import { ByteArrayInputStream(first) }.single()
        val scene1 = s1.load(r1.id)!!
        val second = pack(scene1, f1)
        val f2 = tmp.newFolder()
        val s2 = ProjectStore(f2)
        val r2 = Importer(s2, access = MediaAccess.forPhone(f2)).import { ByteArrayInputStream(second) }.single()
        val scene2 = s2.load(r2.id)!!
        assertEquals(label, null, diff(norm(scene1.node, f1), norm(scene2.node, f2)))
        val m1 = File(f1, "media").walkTopDown().filter { it.isFile }.map { it.name.replace(Regex("^([0-9]+_)+"), "").replace(Regex("[()]"), "") to it.length() }.sortedBy { it.first }.toList()
        val m2 = File(f2, "media").walkTopDown().filter { it.isFile }.map { it.name.replace(Regex("^([0-9]+_)+"), "").replace(Regex("[()]"), "") to it.length() }.sortedBy { it.first }.toList()
        assertEquals("$label media", m1, m2)
        println("$label ok: ${m1.size} media, sections ${r1.sections.map { it.first.toString() to it.second.map(Any::toString) }}")
    }

    @Test fun ownerSamplesSurviveTwoRoundTrips() {
        val dir = File(System.getProperty("sweep.samples") ?: "/private/tmp/claude-501/-Users-jd/44da8514-9de8-4c0b-acc3-89282ac7865b/scratchpad/sweep/D/samples")
        val zips = dir.listFiles { f -> f.extension == "zip" }.orEmpty()
        assumeTrue(zips.isNotEmpty())
        for (z in zips) roundTrip(z.name, z.readBytes())
    }

    @Test fun testScenesSurviveARoundTrip() {
        for (file in TestData.scenes) {
            val f0 = tmp.newFolder()
            val s0 = ProjectStore(f0)
            val r = Importer(s0, access = MediaAccess.forPhone(f0)).import { file.inputStream() }.single()
            roundTrip(file.name, pack(s0.load(r.id)!!, f0))
        }
    }
}
