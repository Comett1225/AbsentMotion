package app.absentmotion.pc

import app.absentmotion.audio.AudioClip
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.PcmChunk
import app.absentmotion.audio.PcmSource
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.SafeLimiter
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DeskExportSoundTest {
    private class BreakingTone : PcmSource {
        private val chunk = PcmChunk()
        private var at = 0L
        override val durationUs = 3_000_000L
        override fun seek(us: Long) { at = us * 48_000 / 1_000_000 }
        override fun next(): PcmChunk? {
            if (at >= 48_000) throw IllegalStateException("damaged packet")
            chunk.pcm = ShortArray(2048) { (sin((at + it / 2) * 0.05) * 12000).toInt().toShort() }
            chunk.frames = 1024
            chunk.rate = 48_000
            chunk.ptsUs = at * 1_000_000 / 48_000
            at += 1024
            return chunk
        }
        override fun close() {}
    }

    @Test fun aSoundThatBreaksDuringExportIsSilencedAndReported() {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30"><audio id="1" src="tone" startTime="0" endTime="2999"/></scene>""")
        OfflineMix({ BreakingTone() }, AudioClip.of(scene), Resampler.ULTRA, { _, _ -> null }, SafeLimiter(48_000), 48_000).use { mix ->
            val out = mix.floats(0, 3_000_000)
            assertTrue(out.copyOfRange(0, 48_000).any { it != 0f })
            assertTrue(out.copyOfRange(2 * 48_000 * 2, out.size).all { it == 0f })
            assertEquals(listOf("tone"), mix.unreadable.toList())
        }
    }
}
