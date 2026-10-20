package app.absentmotion.library

import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedTemplateMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun deletingTheLastProjectRetainsFilesReferencedByTemplatesAndElements() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val media = store.mediaDir("source").apply { mkdirs() }.resolve("picture.png").apply { writeText("fixture") }
        val unused = store.mediaDir("source").resolve("unused.png").apply { writeText("unused") }
        val scene = Scene.parse("""<scene width="100" height="100"><shape id="1" fillImage="${media.toURI()}"/></scene>""")
        store.save("source", scene)
        root.resolve("templates/template").apply { mkdirs() }.resolve("scene.xml").writeText(scene.toXml())
        store.delete("source")
        assertTrue(media.exists())
        assertFalse(unused.exists())
        assertNull(store.load("source"))
        store.save("element", scene)
        store.delete("element")
        assertTrue(media.exists())
    }

    private fun opener(uri: String) = File(java.net.URI(uri)).inputStream()

    private fun picture(dir: File, text: String) = File(dir, "photo.png").apply { parentFile.mkdirs(); writeText(text) }.toURI().toString()

    private fun template(vararg uris: String) = Scene.parse("""<scene width="10" height="10">""" +
        uris.mapIndexed { i, uri -> """<media uri="$uri" filename="photo.png"/><shape id="${i + 1}" fillImage="$uri"/>""" }.joinToString("") + "</scene>")

    @Test fun embeddingTwoTemplatesWithTheSameFileNameNeverOverwritesMediaTheProjectUses() {
        val root = tmp.newFolder()
        val project = File(root, "media/p1")
        val existing = File(project, "photo.png").apply { parentFile.mkdirs(); writeText("already used") }
        val templates = TemplateStore(File(root, "templates"), ::opener)
        val first = templates.localize(template(picture(tmp.newFolder(), "first")), project).node.mediaUris().single()
        val second = templates.localize(template(picture(tmp.newFolder(), "second")), project).node.mediaUris().single()
        assertEquals("already used", existing.readText())
        assertEquals("first", File(java.net.URI(first)).readText())
        assertEquals("second", File(java.net.URI(second)).readText())
        assertEquals(3, setOf(existing.toURI().toString(), first, second).size)
        val gone = File(tmp.root, "gone/photo.png").toURI().toString()
        assertEquals(gone, templates.localize(template(gone), project).node.mediaUris().single())
        assertEquals(setOf("photo.png", "photo-2.png", "photo-3.png"), project.list()!!.toSet())
    }

    @Test fun aModelIsLocalizedWithItsWholeFolder() {
        val root = tmp.newFolder()
        val source = File(root, "media/p1/model-7").apply { mkdirs() }
        val obj = File(source, "robot.obj").apply { writeText("mtllib robot.mtl") }
        File(source, "robot.mtl").writeText("map_Kd skin.png")
        File(source, "skin.png").writeText("png")
        val project = File(root, "media/p2")
        File(project, "model-7").apply { mkdirs() }.resolve("robot.obj").writeText("someone else's model")
        val scene = Scene.parse("""<scene width="10" height="10"><model id="1" src="${obj.toURI()}"/></scene>""")
        val copied = File(java.net.URI(TemplateStore(File(root, "templates"), ::opener).localize(scene, project).node.mediaUris().single()))
        assertEquals(File(project, "model-7-2/robot.obj"), copied)
        assertEquals(setOf("robot.obj", "robot.mtl", "skin.png"), copied.parentFile.list()!!.toSet())
        assertEquals("someone else's model", File(project, "model-7/robot.obj").readText())
        assertTrue(source.resolve("skin.png").exists())
        assertFalse(project.walkTopDown().any { it.name.startsWith("copy-") })
    }
}
