package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingExportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun nextStart(file: java.io.File) = PendingExports(file, HashSet())

    @Test fun anExportThatFinishedIsNeverCleanedUp() {
        val file = tmp.newFolder().resolve("sub/pending.txt")
        val pending = PendingExports(file, HashSet())
        pending.add("content://media/external/video/media/7")
        pending.remove("content://media/external/video/media/7")
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), nextStart(file).abandoned())
    }

    @Test fun onlyTheUnfinishedOnesAreLeftForTheNextStart() {
        val file = tmp.newFile("pending.txt")
        file.delete()
        val pending = PendingExports(file, HashSet())
        pending.add("content://media/external/video/media/7")
        pending.add("/storage/emulated/0/Movies/AbsentMotion/.123.part")
        pending.remove("content://media/external/video/media/7")
        pending.add("content://media/external/images/media/11")
        assertEquals(listOf("/storage/emulated/0/Movies/AbsentMotion/.123.part", "content://media/external/images/media/11"), nextStart(file).abandoned())
    }

    @Test fun aRecordStaysUntilItsCleanupIsConfirmed() {
        val file = tmp.newFile("pending.txt")
        file.delete()
        PendingExports(file, HashSet()).add("content://media/external/video/media/7")
        val later = nextStart(file)
        assertEquals(listOf("content://media/external/video/media/7"), later.abandoned())
        assertEquals(listOf("content://media/external/video/media/7"), nextStart(file).abandoned())
        later.remove("content://media/external/video/media/7")
        assertEquals(emptyList<String>(), nextStart(file).abandoned())
    }

    @Test fun anExportRunningInThisProcessIsNeverTakenForAnAbandonedOne() {
        val file = tmp.newFile("pending.txt")
        file.delete()
        val mine = HashSet<String>()
        PendingExports(file, mine).add("content://media/external/video/media/8")
        assertEquals(emptyList<String>(), PendingExports(file, mine).abandoned())
        assertEquals(listOf("content://media/external/video/media/8"), nextStart(file).abandoned())
    }

    @Test fun aFailedWriteKeepsTheOtherRecords() {
        val file = tmp.newFile("pending.txt")
        file.writeText("content://media/external/video/media/1\n")
        val pending = PendingExports(file, HashSet())
        val blocker = tmp.root.resolve("pending.txt.new")
        blocker.mkdirs()
        pending.remove("content://media/external/video/media/2")
        assertTrue(file.exists())
        assertEquals(listOf("content://media/external/video/media/1"), nextStart(file).abandoned())
    }

    @Test fun anUnreadableFileMeansNothingIsDeletedLater() {
        val folder = tmp.newFolder()
        val file = folder.resolve("pending.txt")
        file.mkdirs()
        val pending = PendingExports(file, HashSet())
        pending.add("content://media/external/video/media/7")
        assertEquals(emptyList<String>(), nextStart(file).abandoned())
    }
}
