package app.absentmotion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextBlipsTest {
    private val rate = TextBlips.RATE

    private fun FloatArray.peak(from: Int, to: Int) = (from until minOf(to, size)).maxOfOrNull { abs(this[it]) } ?: 0f

    @Test
    fun blipsSoundAtTheirTimesAndNowhereElse() {
        val out = TextBlips.render(listOf(0f, 0.5f), 0, 1f, 0f, 0f, 1)
        assertTrue(out.peak(0, rate / 50) > 0.1f)
        assertEquals(0f, out.peak(rate / 10, rate * 4 / 10))
        assertTrue(out.peak(rate / 2, rate / 2 + rate / 50) > 0.1f)
        assertTrue(out.all { it in -1f..1f })
    }

    @Test
    fun everyVoiceMakesASound() {
        for (voice in 0..7) assertTrue("voice $voice", TextBlips.render(listOf(0f), voice, 1f, 0f, 0f, 1).peak(0, rate / 10) > 0.05f)
    }

    @Test
    fun wavHasAHeaderAndTwoBytesPerSample() {
        val bytes = TextBlips.wav(FloatArray(10))
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(64, bytes.size)
    }
}
