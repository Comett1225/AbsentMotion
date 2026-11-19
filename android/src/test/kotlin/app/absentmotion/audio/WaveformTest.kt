package app.absentmotion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {
    @Test fun peaksSitWhereTheSoundIsHeardWhenItStartsLate() {
        val chunk = PcmChunk().apply { pcm = ShortArray(2 * 480) { 16000 }; frames = 480; rate = 48_000; ptsUs = 300_000 }
        var given = false
        val source = object : PcmSource {
            override val durationUs = 1_000_000L
            override fun seek(us: Long) {}
            override fun next(): PcmChunk? = if (given) null else chunk.also { given = true }
            override fun close() {}
        }
        val peaks = Waveforms.peaksOf(source)
        assertEquals(0f, peaks.peak(0.0, 290.0), 0f)
        assertTrue(peaks.peak(300.0, 305.0) > 0.4f)
    }
}
