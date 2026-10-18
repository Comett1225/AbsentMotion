package app.absentmotion.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class LibraryPackTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyMediaTheAppMayOpenIsPackedAndTheRestArrivesAsMissing() {
        val root = tmp.newFolder("files")
        fun put(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }.toURI().toString()
        val own = put("media/p1/a.jpg", "my picture")
        val template = put("templates/t1/media/1_b.png", "template picture")
        val secret = put("preferences/settings.json", "secret settings")
        val other = put("projects/other.xml", "<scene width='1' height='1'/>")
        val font = put("fonts/imported/Test.ttf", "font")
        val provided = File(tmp.newFolder("provider"), "c.jpg").apply { writeText("provided") }
        put("projects/p1.xml", """<scene title="P" width="10" height="10">
            <media uri="$own" filename="a.jpg"/><media uri="$secret" filename="settings.json"/>
            <shape id="1" fillImage="$own"/><shape id="2" fillImage="$secret"/><shape id="3" fillImage="$other"/><shape id="4" fillImage="$font"/>
            <shape id="5" fillImage="$template"/><shape id="6" fillImage="content://provider/1"/><shape id="7" fillImage="file:///etc/hosts"/></scene>""")
        val asked = ArrayList<String>()
        val pack = LibraryPack.collect(root, "Phone", external = { uri ->
            asked += uri
            if (uri.startsWith("content:")) provided else runCatching { File(URI(uri)) }.getOrNull()
        })
        assertEquals(setOf(own, template, "content://provider/1"), pack.manifest.media.keys)
        assertEquals(setOf(secret, other, font, "file:///etc/hosts"), pack.missing.toSet())
        assertEquals(setOf(own, template, "content://provider/1"), asked.toSet())
        val mediaBytes = pack.manifest.entries.filter { it.path.startsWith("media/") }.map { pack.open(it.path).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        assertEquals(setOf("my picture", "template picture", "provided"), mediaBytes.toSet())
        assertFalse(mediaBytes.contains("secret settings"))
        assertTrue(pack.manifest.entries.any { it.path == "projects/p1.xml" })
    }
}
