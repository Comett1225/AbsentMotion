package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.model.Scene
import app.absentmotion.sceneXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class KeepPitchTest {
    private class Switch(private val rate: Int = 48_000) : PcmSource {
        override val durationUs = 30_000_000L
        private var at = 0L
        private var phase = 0.0
        private val chunk = PcmChunk()

        override fun seek(us: Long) {
            at = us.coerceAtLeast(0) * rate / 1_000_000
            phase = 0.0
            for (i in 0 until at) phase += 2 * PI * hz(i) / rate
        }

        private fun hz(i: Long) = if (i < 2L * rate) 440.0 else 660.0

        override fun next(): PcmChunk? {
            val n = minOf(1024L, durationUs * rate / 1_000_000 - at).toInt()
            if (n <= 0) return null
            if (chunk.pcm.size < 2 * n) chunk.pcm = ShortArray(2 * n)
            for (i in 0 until n) {
                val v = (0.5 * sin(phase) * 32767).roundToInt().toShort()
                phase += 2 * PI * hz(at + i) / rate
                chunk.pcm[2 * i] = v
                chunk.pcm[2 * i + 1] = v
            }
            chunk.frames = n
            chunk.rate = rate
            chunk.ptsUs = at * 1_000_000 / rate
            at += n
            return chunk
        }

        override fun close() {}
    }

    private val rate = 44_100

    private fun render(speed: String, keep: Boolean): ShortArray {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30"><audio id="1" src="tone" startTime="0" endTime="3999" outTime="30000" speed="$speed"${if (keep) """ keepPitch="true"""" else ""}/></scene>""")
        return OfflineMix({ Switch() }, AudioClip.of(scene), Resampler.ULTRA).use { it(0, 4_000_000) }
    }

    private fun hz(pcm: ShortArray, fromS: Double, toS: Double): Double {
        var rising = 0
        val a = (fromS * rate).toInt()
        val b = (toS * rate).toInt()
        for (i in a + 1 until b) if (pcm[2 * (i - 1)] < 0 && pcm[2 * i] >= 0) rising++
        return rising / (toS - fromS)
    }

    private fun rms(pcm: ShortArray, fromS: Double, toS: Double): Double {
        var sum = 0.0
        val a = (fromS * rate).toInt()
        val b = (toS * rate).toInt()
        for (i in a until b) sum += pcm[2 * i].toDouble() * pcm[2 * i]
        return sqrt(sum / (b - a))
    }

    @Test fun keepPitchChangesTheLengthButNotThePitch() {
        val fast = render("2", true)
        assertEquals(440.0, hz(fast, 0.2, 0.8), 6.0)
        assertEquals(660.0, hz(fast, 1.2, 1.8), 6.0)
        val slow = render("0.5", true)
        assertEquals(440.0, hz(slow, 0.5, 3.5), 6.0)
        val plain = render("2", false)
        assertEquals(880.0, hz(plain, 0.2, 0.8), 6.0)
        assertEquals(1320.0, hz(plain, 1.2, 1.8), 8.0)
        assertEquals(220.0, hz(render("0.5", false), 0.5, 3.5), 6.0)
    }

    @Test fun thePreviewKeepsThePitchToo() {
        val clip = AudioClip.of(sceneXml("""<audio id="1" src="tone" startTime="0" endTime="7999" speed="2" keepPitch="true"/>""")).single()
        val live = 48_000
        val block = 1024
        val dt = 1000.0 / live
        val first = Math.round(1000.0 / dt)
        val stream = LayerStream("tone", clip.mediaAt(1000.0) - LayerStream.LEAD_MS) { ToneSource(440.0) }
        val rack = FxRack(live) { _, _ -> null }
        val out = FloatArray(2 * block)
        val all = FloatArray(2 * block * 24)
        try {
            for (b in 0 until 24) {
                val t0 = (first + b * block) * dt
                awaitCondition("sound at ${t0.toInt()} ms") { stream.prepare(clip.wrap(clip.mediaAt(t0)), 600.0) }
                mixStreams(listOf(clip), out, block, t0, live, Resampler.ULTRA, rack) { stream }
                System.arraycopy(out, 0, all, 2 * block * b, 2 * block)
            }
        } finally { stream.close() }
        var rising = 0
        for (i in block + 1 until 24 * block) if (all[2 * (i - 1)] < 0f && all[2 * i] >= 0f) rising++
        assertEquals(440.0, rising / (23.0 * block / live), 8.0)
    }

    @Test fun keepPitchStaysSteadyAndMatchesNormalSpeed() {
        val full = 0.5 * 32767 / sqrt(2.0)
        for (sound in listOf(render("2", true), render("0.5", true), render("1.37", true))) {
            var at = 0.1
            while (at < 0.9) {
                val level = rms(sound, at, at + 0.02)
                assertTrue("level $level at $at", level in full * 0.85..full * 1.15)
                at += 0.02
            }
        }
        val same = render("1", true)
        val normal = render("1", false)
        var worst = 0
        for (i in 2 * rate / 10 until 2 * rate * 3) worst = maxOf(worst, kotlin.math.abs(same[i] - normal[i]))
        assertTrue("speed 1 differs by $worst", worst < 400)
    }
}
