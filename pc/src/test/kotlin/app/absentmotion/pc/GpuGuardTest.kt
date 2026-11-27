package app.absentmotion.pc

import app.absentmotion.desk.DeskOs
import app.absentmotion.engine.GpuGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpuGuardTest {
    @Test fun aCrashDuringGraphicsSetupSwitchesToTheCpuUntilTheNextVersion() {
        val dir = File("build/gpu-guard").also { it.deleteRecursively(); it.mkdirs() }
        assertEquals(false to false, GpuGuard.softwareFor(dir, "5.3.3"))
        File(dir, "gpu-starting").writeText("1")
        assertEquals(true to true, GpuGuard.softwareFor(dir, "5.3.3"))
        File(dir, "gpu-starting").delete()
        assertEquals(true to false, GpuGuard.softwareFor(dir, "5.3.3"))
        assertEquals(false to false, GpuGuard.softwareFor(dir, "5.3.4"))
    }

    @Test fun theProbeResultIsKeptForOneVersion() {
        val dir = File("build/gpu-record").also { it.deleteRecursively(); it.mkdirs() }
        assertFalse(GpuGuard.probed(dir, "5.3.3"))
        GpuGuard.record(dir, "5.3.3", true)
        assertTrue(GpuGuard.probed(dir, "5.3.3"))
        assertEquals(false to false, GpuGuard.softwareFor(dir, "5.3.3"))
        GpuGuard.record(dir, "5.3.4", false)
        assertTrue(GpuGuard.probed(dir, "5.3.4"))
        assertFalse(GpuGuard.probed(dir, "5.3.3"))
        assertEquals(true to false, GpuGuard.softwareFor(dir, "5.3.4"))
        assertFalse(GpuGuard.probed(dir, "5.3.5"))
        assertEquals(false to false, GpuGuard.softwareFor(dir, "5.3.5"))
    }

    @Test fun aProbeChildThatCrashesHangsOrCannotStartNeverTakesTheAppDown() {
        val dir = File("build/gpu-probe").also { it.deleteRecursively(); it.mkdirs() }
        assertEquals(null, GpuGuard.probe(listOf(File(dir, "missing").absolutePath, GpuGuard.PROBE), dir, 2000))
        if (!DeskOs.windows) {
            val started = System.nanoTime()
            assertEquals(false, GpuGuard.probe(listOf("sleep", "30"), dir, 300))
            assertTrue(System.nanoTime() - started < 5_000_000_000L)
        }
        val child = GpuGuard.command()!!
        assertEquals(GpuGuard.PROBE, child.last())
        assertEquals(false, GpuGuard.probe(child, dir, 30000, mapOf("AM_GPU_PROBE_CRASH" to "1")))
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("hs_err") })
        assertEquals(true, GpuGuard.probe(child, dir, 30000))
    }
}
