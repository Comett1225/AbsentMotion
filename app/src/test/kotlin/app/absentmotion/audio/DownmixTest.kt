package app.absentmotion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DownmixTest {
    private val c = 0.7071f

    @Test fun fivePointOneKeepsTheCentreDialogueAndSurroundsAndDropsTheLfe() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, c, c, 0f, 0f, c, 0f, 0f, c), downmixGains(6, 0), 0f)
    }

    @Test fun sevenPointOneAddsTheSideChannels() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, c, c, 0f, 0f, c, 0f, 0f, c, c, 0f, 0f, c), downmixGains(8, 0), 0f)
    }

    @Test fun quadHasNoCentreAndAReportedMaskWins() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, c, 0f, 0f, c), downmixGains(4, 0), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), downmixGains(3, 0x4 or 0x8 or 0x20), 0f)
    }
}
