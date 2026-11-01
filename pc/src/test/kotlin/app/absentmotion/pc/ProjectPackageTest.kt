package app.absentmotion.pc

import app.absentmotion.pc.media.openProject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectPackageTest {
    @Test fun importsPhonePackageAndRelinksMedia() {
        val folder = File("build/package-test").also { it.deleteRecursively(); it.mkdirs() }
        val source = File("../testdata/scenes/shapes.xml").readText()
        val uri = "content:test-image"
        val xml = source.replace("</scene>", "<media uri=\"$uri\" type=\"image/jpeg\"/><shape id=\"998\" startTime=\"0\" endTime=\"1000\" s=\".rect\" fillImage=\"$uri\"/><text id=\"999\" startTime=\"0\" endTime=\"1000\" font=\"imported?name=Test.ttf\"><content>Test</content></text></scene>")
        val font = "imported?name=Test.ttf"
        val manifest = JSONObject().put("format", "absentmotion-package").put("version", 1).put("project", "project.xml")
            .put("media", JSONArray().put(JSONObject().put("uri", uri).put("entry", "media/1_test-image.jpg"))).put("fonts", JSONArray().put(JSONObject().put("font", font).put("entry", "fonts/1_Test.ttf")))
        val archive = folder.resolve("test.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("project.xml", xml.toByteArray())
            entry("media/1_test-image.jpg", File("../testdata/media/test-image.jpg").readBytes())
            entry("fonts/1_Test.ttf", File("../app/src/main/assets/fonts/Roboto/Roboto-Regular.ttf").readBytes())
            entry("manifest.json", manifest.toString().toByteArray())
        }
        val (scene, project) = openProject(archive, folder.resolve("data"))
        val moved = scene.layer(998)!!.node["fillImage"]!!
        assertEquals(moved, scene.media.single().uri)
        assertTrue(File(java.net.URI(moved)).isFile)
        assertTrue(folder.resolve("data/fonts/imported/Test.ttf").isFile)
        assertEquals(font, scene.layer(999)!!.node["font"])
        assertEquals(moved, app.absentmotion.model.Scene.parse(project.readText()).layer(998)!!.node["fillImage"])
        assertTrue(project.parentFile.canonicalPath.startsWith(folder.resolve("data/projects").canonicalPath))
    }

    @Test fun rejectsMissingProjectAndCleansUp() {
        val folder = File("build/package-invalid-test").also { it.deleteRecursively(); it.mkdirs() }
        val archive = folder.resolve("bad.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "absentmotion-package").put("project", "missing.xml").toString().toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalStateException::class.java) { openProject(archive, folder.resolve("data")) }
        assertTrue(folder.resolve("data/projects").listFiles()!!.isEmpty())
    }
}
