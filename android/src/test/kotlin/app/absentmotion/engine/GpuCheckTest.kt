package app.absentmotion.engine

import android.app.ApplicationExitInfo
import app.absentmotion.R
import app.absentmotion.ui.home.engineProblemText
import app.absentmotion.ui.home.openGlAlsoFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuCheckTest {
    private fun picture(mix: Int, color: (Int, Int) -> IntArray) = ByteArray(64 * 64 * 4).also { px ->
        for (y in 0 until 64) for (x in 0 until 64) {
            var c = color(x, y)
            if (y >= 48) c = IntArray(3) { (c[it] + 255 * mix / 100) * 100 / (100 + mix) }
            for (k in 0..2) px[(y * 64 + x) * 4 + k] = c[k].toByte()
            px[(y * 64 + x) * 4 + 3] = -1
        }
    }

    private val quad = { x: Int, y: Int ->
        when {
            y < 32 && x < 32 -> intArrayOf(255, 0, 0)
            y < 32 -> intArrayOf(0, 255, 0)
            x < 32 -> intArrayOf(0, 0, 255)
            else -> intArrayOf(0, 0, 0)
        }
    }

    @Test fun rightPictureMatches() = assertTrue(GpuCheck.matches(picture(100, quad)))

    @Test fun whitePictureFails() = assertFalse(GpuCheck.matches(picture(0) { _, _ -> intArrayOf(255, 255, 255) }))

    @Test fun faintPictureFails() = assertFalse(GpuCheck.matches(picture(100) { x, y -> quad(x, y).map { 230 + it / 10 }.toIntArray() }))

    @Test fun upsideDownFails() = assertFalse(GpuCheck.matches(picture(100) { x, y -> quad(x, 63 - y) }))

    @Test fun oldMaliAndPowerVrStartWithOpenGl() {
        listOf("5045\tMali-G52 MC2", "5045\tMali-G51", "5045\tMali-G31 MP2", "5045\tMali-T880 MP12",
            "4112\tPowerVR Rogue GE8320", "4112\tPowerVR B-Series BXM-8-256").forEach { assertTrue(it, GpuCheck.vulkanRisky(it)) }
        listOf("5045\tMali-G57 MC2", "5045\tMali-G510", "5045\tMali-G310", "5045\tMali-G76 MP10", "5045\tMali-G715-Immortalis MC11",
            "4112\tPowerVR D-Series DXT-48-1536", "20803\tAdreno (TM) 740", "6880\tSwiftShader Device (Subzero)",
            "garbage", null).forEach { assertFalse("$it", GpuCheck.vulkanRisky(it)) }
    }

    @Test fun aNativeCrashWhileStartingSkipsThatBackend() {
        assertEquals(GpuCheck.Judged(false, null), GpuCheck.judge("41:vk", ApplicationExitInfo.REASON_CRASH_NATIVE, null))
        assertEquals(GpuCheck.Judged(true, null), GpuCheck.judge("41:gl", ApplicationExitInfo.REASON_CRASH_NATIVE, "gl"))
    }

    @Test fun otherKnownDeathsAreNotBlamed() {
        listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED).forEach {
            assertEquals("$it", GpuCheck.Judged(null, null), GpuCheck.judge("41:vk", it, "vk"))
        }
    }

    @Test fun anAwayDeathIsNeverBlamed() {
        assertEquals(GpuCheck.Judged(null, null), GpuCheck.judge("41:vk~", ApplicationExitInfo.REASON_CRASH_NATIVE, "vk"))
        assertEquals(GpuCheck.Judged(null, null), GpuCheck.judge("41:vk~", null, "vk"))
    }

    @Test fun anUnexplainedDeathIsBlamedTheSecondTimeInARow() {
        assertEquals(GpuCheck.Judged(null, "vk"), GpuCheck.judge("41:vk", null, null))
        assertEquals(GpuCheck.Judged(false, null), GpuCheck.judge("42:vk", null, "vk"))
        assertEquals(GpuCheck.Judged(null, "gl"), GpuCheck.judge("43:gl", null, "vk"))
        assertEquals(GpuCheck.Judged(null, "vk"), GpuCheck.judge("44:vk", ApplicationExitInfo.REASON_ANR, null))
        assertEquals(GpuCheck.Judged(false, null), GpuCheck.judge("45:vk", ApplicationExitInfo.REASON_ANR, "vk"))
    }

    @Test fun aBackendThatClosedTheAppIsNeverTheFallback() {
        val crashed = GpuCheck.CRASHED
        val risky = { error("not asked") }
        assertEquals(listOf(false, true), GpuCheck.startOrder(false, null, null) { false })
        assertEquals(listOf(true, false), GpuCheck.startOrder(false, null, null) { true })
        assertEquals(listOf(true), GpuCheck.startOrder(false, "bad", crashed, risky))
        assertEquals(listOf(true), GpuCheck.startOrder(false, null, crashed, risky))
        assertEquals(listOf(false), GpuCheck.startOrder(true, crashed, null, risky))
        assertEquals(listOf(false), GpuCheck.startOrder(false, crashed, "bad", risky))
        assertEquals(listOf(false, true), GpuCheck.startOrder(false, "bad", "bad", risky))
        assertEquals(listOf(true, false), GpuCheck.startOrder(true, "bad", "ok", risky))
    }

    @Test fun whenBothBackendsClosedTheAppNoneIsStartedAndTheUnsupportedScreenExplains() {
        val crashed = GpuCheck.CRASHED
        val risky = { error("not asked") }
        assertEquals(emptyList<Boolean>(), GpuCheck.startOrder(false, crashed, crashed, risky))
        assertEquals(emptyList<Boolean>(), GpuCheck.startOrder(true, crashed, crashed, risky))
        assertEquals(R.string.unsupported_unknown, engineProblemText(GpuCheck.BOTH_CLOSED))
        assertTrue(openGlAlsoFailed(GpuCheck.BOTH_CLOSED))
    }

    @Test fun aBrokenRecordIsIgnored() {
        listOf("x:vk", "41", "41:", "").forEach { assertEquals(it, GpuCheck.Judged(null, null), GpuCheck.judge(it, ApplicationExitInfo.REASON_CRASH_NATIVE, null)) }
    }
}
