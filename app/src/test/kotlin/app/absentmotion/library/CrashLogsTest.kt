package app.absentmotion.library

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import app.absentmotion.export.StorageFullException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogsTest {
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
}
