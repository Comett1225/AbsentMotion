package app.absentmotion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

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
        val times = listOf(0.99f, 1.5f, 2.99f)
        val bytes = TextBlips.wav(times, 6, 0.8f, 0f, 2f, 3)
        val all = TextBlips.render(times, 6, 0.8f, 0f, 2f, 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(44 + all.size * 2, bytes.size)
        assertEquals(36 + all.size * 2, buffer.getInt(4))
        assertEquals(all.size * 2, buffer.getInt(40))
        for (i in all.indices) assertEquals(i.toString(), (all[i] * 32767f).roundToInt().toShort(), buffer.getShort(44 + 2 * i))
    }

    @Test
    fun blocksSoundTheSameAsOneRender() {
        val times = listOf(0f, 0.0113f, 0.3f, 0.5f)
        for (voice in 0..7) {
            val all = TextBlips.render(times, voice, 1f, 2f, 3f, 5)
            val joined = FloatArray(all.size)
            var from = 0
            while (from < all.size) {
                val part = TextBlips.render(times, voice, 1f, 2f, 3f, 5, from, minOf(333, all.size - from))
                part.copyInto(joined, from)
                from += part.size
            }
            assertArrayEquals("voice $voice", all, joined, 0f)
        }
    }
}
