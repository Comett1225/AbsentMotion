package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingExportsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun anExportThatFinishedIsNeverDrained() {
        val file = tmp.newFolder().resolve("sub/pending.txt")
        val pending = PendingExports(file)
        pending.add("content://media/external/video/media/7")
        pending.remove("content://media/external/video/media/7")
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), pending.drain())
    }

    @Test fun onlyTheUnfinishedOnesAreLeftForTheNextStart() {
        val file = tmp.newFile("pending.txt")
        file.delete()
        val pending = PendingExports(file)
        pending.add("content://media/external/video/media/7")
        pending.add("content://media/external/file/9")
        pending.remove("content://media/external/video/media/7")
        pending.add("content://media/external/images/media/11")
        assertEquals(listOf("content://media/external/file/9", "content://media/external/images/media/11"), PendingExports(file).drain())
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), pending.drain())
    }

    @Test fun anUnreadableFileMeansNothingIsDeletedLater() {
        val folder = tmp.newFolder()
        val file = folder.resolve("pending.txt")
        file.mkdirs()
        val pending = PendingExports(file)
        pending.add("content://media/external/video/media/7")
        assertEquals(emptyList<String>(), pending.drain())
    }
}
