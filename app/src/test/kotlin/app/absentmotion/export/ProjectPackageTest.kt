package app.absentmotion.export

import app.absentmotion.library.MediaAccess
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

class ProjectPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyMediaTheAppMayOpenIsPackedAndNothingElseIsEvenOpened() = runBlocking {
        val files = tmp.newFolder("files")
        val picture = File(files, "media/p1/a.jpg").apply { parentFile.mkdirs(); writeText("picture") }.toURI().toString()
        val secret = File(files, "preferences/settings.json").apply { parentFile.mkdirs(); writeText("secret") }.toURI().toString()
        val project = File(files, "projects/p1.xml").apply { parentFile.mkdirs(); writeText("<scene/>") }.toURI().toString()
        val scene = Scene.parse(
            """<scene title="T" width="10" height="10">
                <media uri="$picture" filename="a.jpg"/><media uri="$secret" filename="settings.json"/><media uri="$project" filename="p1.xml"/>
                <media uri="content://provider/1" filename="c.jpg"/><media uri="file:///etc/hosts" filename="hosts"/>
                <shape id="1" fillImage="$picture"/><shape id="2" fillImage="$secret"/><shape id="3" fillImage="$project"/>
                <shape id="4" fillImage="content://provider/1"/><audio id="5" src="file:///etc/hosts"/>
            </scene>""",
        )
        val opened = ArrayList<String>()
        val out = ByteArrayOutputStream()
        writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forPhone(files), { uri ->
            opened += uri
            if (uri.startsWith("content:")) "provider bytes".byteInputStream() else File(URI(uri)).inputStream()
        }, {})
        assertEquals(listOf(picture, "content://provider/1"), opened)
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(listOf("project.xml", "media/1_a.jpg", "media/4_c.jpg", "manifest.json"), entries.keys.toList())
        assertEquals("picture", entries["media/1_a.jpg"])
        assertFalse(entries.filterKeys { it.startsWith("media/") }.values.any { it == "secret" || it.contains("<scene") })
        val listed = JSONObject(entries.getValue("manifest.json")).getJSONArray("media")
        val byUri = (0 until listed.length()).map { listed.getJSONObject(it) }.associateBy { it.getString("uri") }
        assertEquals(5, byUri.size)
        assertEquals("media/1_a.jpg", byUri.getValue(picture).getString("entry"))
        for (refused in listOf(secret, project, "file:///etc/hosts")) assertFalse(refused, byUri.getValue(refused).has("entry"))
        assertTrue(byUri.getValue("content://provider/1").has("entry"))
    }
}
