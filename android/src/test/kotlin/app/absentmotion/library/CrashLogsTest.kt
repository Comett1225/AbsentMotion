package app.absentmotion.library

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import app.absentmotion.export.StorageFullException
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogsTest {
    @Test fun copiedCrashTextHidesFileNamesAndPathsButKeepsTheCode() {
        val text = """
            java.io.FileNotFoundException: /storage/emulated/0/DCIM/Camera/Family trip.mp4: open failed: ENOENT (No such file or directory)
            Caused by: java.io.IOException: /data/user/0/app.absentmotion/files/media/p1/My Song.mp3 (Permission denied)
            uri content://com.android.externalstorage.documents/document/primary%3ADCIM%2FIMG_2041.jpg and file:/data/user/0/app.absentmotion/files/media/p1/A%20b.png
            	at app.absentmotion.library.MediaKeeper.keep(MediaKeeper.kt:39)
            #00 pc 0000 /data/app/~~x==/app.absentmotion-y==/lib/arm64/libengine.so (render+12)
        """.trimIndent()
        val hidden = redactPaths(text)
        for (secret in listOf("Family", "trip", "My Song", "IMG_2041", "A%20b", "DCIM", "p1/")) assertFalse(secret, hidden.contains(secret))
        assertTrue(hidden, hidden.contains("/storage/….mp4: open failed: ENOENT (No such file or directory)"))
        assertTrue(hidden, hidden.contains("/data/user/0/….mp3 (Permission denied)"))
        assertTrue(hidden, hidden.contains("content://com.android.externalstorage.documents/…"))
        assertTrue(hidden, hidden.contains("file:….png"))
        assertTrue(hidden, hidden.contains("at app.absentmotion.library.MediaKeeper.keep(MediaKeeper.kt:39)"))
        assertTrue(hidden, hidden.contains("/lib/arm64/libengine.so (render+12)"))
    }

    @Test fun exceptionsGetAPlainReason() {
        assertEquals(CrashReason.MemoryExport, crashReason(OutOfMemoryError("Failed to allocate"), "exporting gif"))
        assertEquals(CrashReason.Memory, crashReason(OutOfMemoryError(), "editing a project"))
        assertEquals(CrashReason.Memory, crashReason(RuntimeException("wrapped", OutOfMemoryError()), "home"))
        assertEquals(CrashReason.Storage, crashReason(IOException("write failed: ENOSPC (No space left on device)"), "editing a project"))
        assertEquals(CrashReason.Storage, crashReason(IllegalStateException("save", IOException("No space left on device")), "home"))
        assertEquals(CrashReason.Storage, crashReason(StorageFullException(), "exporting video"))
        assertEquals(CrashReason.Error, crashReason(IOException("Broken pipe"), "importing"))
        assertEquals(CrashReason.Error, crashReason(NullPointerException(), "exporting video"))
    }

    @Test fun exitsGetAPlainReasonAndLowMemoryOnlyInTheForeground() {
        val front = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val back = RunningAppProcessInfo.IMPORTANCE_CACHED
        assertEquals(CrashReason.Engine, exitReason(ApplicationExitInfo.REASON_CRASH_NATIVE, front, "home"))
        assertEquals(CrashReason.NotResponding, exitReason(ApplicationExitInfo.REASON_ANR, front, "home"))
        assertEquals(CrashReason.StartFailed, exitReason(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, front, ""))
        assertEquals(CrashReason.Error, exitReason(ApplicationExitInfo.REASON_CRASH, back, "home"))
        assertEquals(CrashReason.LowMemory, exitReason(ApplicationExitInfo.REASON_LOW_MEMORY, front, "editing a project"))
        assertEquals(CrashReason.MemoryExport, exitReason(ApplicationExitInfo.REASON_LOW_MEMORY, front, "exporting video"))
        assertNull(exitReason(ApplicationExitInfo.REASON_LOW_MEMORY, back, "exporting video"))
        assertNull(exitReason(ApplicationExitInfo.REASON_USER_REQUESTED, front, "home"))
        assertNull(exitReason(ApplicationExitInfo.REASON_EXIT_SELF, front, "home"))
        assertNull(exitReason(ApplicationExitInfo.REASON_SIGNALED, front, "home"))
    }

    @Test fun onlyNewExitsAreRecordedAndJavaCrashesOnce() {
        val front = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val exits = listOf(
            Exit(5_000, ApplicationExitInfo.REASON_ANR, front, "home"),
            Exit(90_000, ApplicationExitInfo.REASON_CRASH, front, "home"),
            Exit(200_000, ApplicationExitInfo.REASON_CRASH, front, "home"),
            Exit(300_000, ApplicationExitInfo.REASON_CRASH_NATIVE, front, "exporting video"),
            Exit(400_000, ApplicationExitInfo.REASON_USER_REQUESTED, front, "home"),
        )
        val recorded = exitsToRecord(exits, seen = 10_000, logged = listOf(89_400))
        assertEquals(listOf(200_000L to CrashReason.Error, 300_000L to CrashReason.Engine), recorded.map { it.first.time to it.second })
        assertEquals(emptyList<Pair<Exit, CrashReason>>(), exitsToRecord(exits, seen = 400_000, logged = emptyList()))
    }

    @Test fun nativeTracesAreReducedToReadableText() {
        val bytes = byteArrayOf(10, 2) + "SIGABRT".toByteArray() + byteArrayOf(0, 18, 1) + "ab".toByteArray() + byteArrayOf(-1) +
            "libamengine.so".toByteArray() + byteArrayOf(0)
        assertEquals("SIGABRT\nlibamengine.so\n", readableStrings(bytes, 1000))
        assertEquals("SIGAB", readableStrings(bytes, 5))
    }

    @Test fun onlyTheLastTwentyLogsAreKeptNewestFirst() {
        val folder = Files.createTempDirectory("crashlogs").toFile()
        try {
            for (i in 1..25) writeCrash(folder, 1_000L * i, if (i % 2 == 0) CrashReason.Engine else CrashReason.Error, "head", "detail $i")
            writeCrash(folder, 25_000, CrashReason.Memory, "head", "same millisecond")
            File(folder, "77_SomethingNew.txt").writeText("from a newer version")
            val logs = crashEntries(folder)
            assertEquals(21, logs.size)
            assertEquals(listOf(25_001L, 25_000L, 24_000L), logs.take(3).map { it.time })
            assertEquals(CrashReason.Memory, logs[0].reason)
            assertEquals(CrashReason.Unknown, logs.last().reason)
            assertTrue(logs[0].file.readText().contains("Reason: Memory"))
            writeCrash(folder, 30_000, CrashReason.Storage, "head", "one more")
            assertEquals(20, crashEntries(folder).size)
            assertEquals(30_000L, crashEntries(folder).first().time)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun overlappingTasksNeverLeaveAStaleOneBehind() = kotlinx.coroutines.runBlocking {
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val second = kotlinx.coroutines.CompletableDeferred<Unit>()
        val screen = CrashLogs.doing
        val one = launch { CrashLogs.during("importing") { first.await() } }
        yield()
        assertEquals("importing", CrashLogs.doing)
        val two = launch { CrashLogs.during("exporting video") { second.await() } }
        yield()
        assertEquals("exporting video", CrashLogs.doing)
        first.complete(Unit)
        one.join()
        assertEquals("exporting video", CrashLogs.doing)
        second.complete(Unit)
        two.join()
        assertEquals(screen, CrashLogs.doing)
    }
}
