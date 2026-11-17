package app.absentmotion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DownmixTest {
    private val c = 0.7071f

    private fun scaled(peak: Float, vararg gains: Float) = FloatArray(gains.size) { gains[it] / peak }

    @Test fun fivePointOneKeepsTheCentreDialogueAndSurroundsAndDropsTheLfe() {
        assertArrayEquals(scaled(1 + 2 * c, 1f, 0f, 0f, 1f, c, c, 0f, 0f, c, 0f, 0f, c), downmixGains(6, 0), 1e-6f)
    }

    @Test fun sevenPointOneAddsTheSideChannels() {
        assertArrayEquals(scaled(1 + 3 * c, 1f, 0f, 0f, 1f, c, c, 0f, 0f, c, 0f, 0f, c, c, 0f, 0f, c), downmixGains(8, 0), 1e-6f)
    }

    @Test fun quadHasNoCentreAndAReportedMaskWins() {
        assertArrayEquals(scaled(1 + c, 1f, 0f, 0f, 1f, c, 0f, 0f, c), downmixGains(4, 0), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), downmixGains(3, 0x4 or 0x8 or 0x20), 0f)
    }

    @Test fun loudSurroundMixesDownWithoutClippingLikeFfmpeg() {
        for (channels in listOf(3, 4, 5, 6, 7, 8)) {
            val gains = downmixGains(channels, 0)
            for (side in 0..1) assertEquals("$channels channels at 0.5", 0.5, (0 until channels).sumOf { gains[2 * it + side] * 0.5 }, 1e-6)
        }
    }
}
