package app.absentmotion.library

import app.absentmotion.model.Scene
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
}
