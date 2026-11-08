package app.absentmotion.library

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedTimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_794_100_000_000L

    private fun file(time: Long): File = tmp.newFile().apply { writeText("x"); setLastModified(time) }

    @Test fun timesRestoredInSecondsAreRepaired() {
        val restored = file(1_794_077_145_000L / 1000)
        assertEquals(1_794_077_145_000L, restored.savedTime(now))
        assertEquals(1_794_077_145_000L, restored.lastModified())
    }

    @Test fun normalAndImpossibleTimesStayAsTheyAre() {
        assertEquals(1_794_077_145_000L, file(1_794_077_145_000L).savedTime(now))
        assertEquals(0L, file(0L).savedTime(now))
        assertEquals(5_000L, file(5_000L).savedTime(now))
        val future = (now + 10 * 86_400_000L) / 1000
        assertEquals(future, file(future).savedTime(now))
    }

    @Test fun projectsListedAfterARestoreShowTheirRealDate() {
        val store = ProjectStore(tmp.newFolder("files"))
        val id = store.create(Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"), emptyList())))
        val xml = File(tmp.root, "files/projects/$id.xml")
        xml.setLastModified(1_762_560_000_000L / 1000)
        assertEquals(1_762_560_000_000L, store.list().single { it.id == id }.modified)
    }
}
