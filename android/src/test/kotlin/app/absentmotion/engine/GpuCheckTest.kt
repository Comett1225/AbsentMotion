package app.absentmotion.engine

import android.app.ApplicationExitInfo
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

    @Test fun aCrashWhileStartingSkipsThatBackend() {
        assertEquals(false, GpuCheck.crashedStarting("41:vk") { ApplicationExitInfo.REASON_CRASH_NATIVE })
        assertEquals(true, GpuCheck.crashedStarting("41:gl") { ApplicationExitInfo.REASON_ANR })
        assertEquals(false, GpuCheck.crashedStarting("41:vk") { null })
        assertEquals(null, GpuCheck.crashedStarting("41:vk") { ApplicationExitInfo.REASON_USER_REQUESTED })
        assertEquals(null, GpuCheck.crashedStarting("x:vk") { ApplicationExitInfo.REASON_CRASH_NATIVE })
    }
}
