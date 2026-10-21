package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.sceneXml
import app.absentmotion.model.Scene
import app.absentmotion.model.splitAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class SplitAudioTest {
    private class Tone(private val hz: Double) : PcmSource {
        override val durationUs = 30_000_000L
        private var at = 0L
        private val chunk = PcmChunk()

        @Volatile var reads = 0
            private set

        override fun seek(us: Long) { at = us.coerceAtLeast(0) * RATE / 1_000_000 / FRAME * FRAME }

        override fun next(): PcmChunk? {
            reads++
            val n = minOf(FRAME.toLong(), durationUs * RATE / 1_000_000 - at).toInt()
            if (n <= 0) return null
            if (chunk.pcm.size < 2 * n) chunk.pcm = ShortArray(2 * n)
            for (i in 0 until n) {
                val v = (level(hz, at + i) * 32768).roundToInt().toShort()
                chunk.pcm[2 * i] = v
                chunk.pcm[2 * i + 1] = v
            }
            chunk.frames = n
            chunk.rate = RATE
            chunk.ptsUs = at * 1_000_000 / RATE
            at += n
            return chunk
        }

        override fun close() {}
    }

    private fun video(id: Int, start: Int, end: Int, extra: String = "", uri: String = "tone") =
        """<shape id="$id" startTime="$start" endTime="$end" fillType="media" fillVideo="$uri" s=".rect" $extra/>"""

    private fun split(scene: Scene, frame: Int): Scene {
        val (first, second) = scene.splitAt(scene.layer(1)!!, frame, 2)!!
        return scene.update(1) { first }.add(second)
    }

    private fun render(scene: Scene, ms: Int): FloatArray {
        val mix = OfflineMix({ Tone(5.0) }, AudioClip.of(scene), Resampler.ULTRA)
        val pcm = mix(0, ms * 1000L)
        mix.close()
        return FloatArray(pcm.size / 2) { pcm[2 * it] / 32768f }
    }

    private fun assertSameAwayFrom(expected: FloatArray, actual: FloatArray, vararg cuts: Int) {
        assertEquals(expected.size, actual.size)
        val rate = expected.size / 8000.0
        var worst = 0f
        var where = -1
        for (i in expected.indices) {
            val ms = i / rate
            if (ms < 5 || ms > 7995 || cuts.any { abs(ms - it) < 5 }) continue
            val off = abs(expected[i] - actual[i])
            if (off > worst) { worst = off; where = i }
        }
        assertTrue("differs by $worst at ${where / rate} ms", worst < 0.01f)
        assertTrue(expected.maxOf { abs(it) } > 0.4f)
    }

    @Test fun exportOfASplitClipSoundsLikeTheUncutClip() {
        val whole = sceneXml(video(1, 0, 7999))
        assertSameAwayFrom(render(whole, 8000), render(split(whole, 135), 8000), 4500)
    }

    @Test fun exportOfASplitFastClipSoundsLikeTheUncutClip() {
        val whole = sceneXml(video(1, 0, 7999, """speed="2""""))
        assertSameAwayFrom(render(whole, 8000), render(split(whole, 135), 8000), 4500)
    }

    @Test fun exportOfASplitSpeedRampSoundsLikeTheUncutRamp() {
        val whole = sceneXml(video(1, 0, 7999, "", "tone").replace("/>", """><speedMap><kf t="0" v="0.5"/><kf t="9000000000" v="2"/></speedMap></shape>"""))
        assertSameAwayFrom(render(whole, 8000), render(split(whole, 100), 8000), 3333)
    }

    @Test fun exportOfTwoClipsBackToBackPlaysBoth() {
        val first = video(1, 0, 3999, "", "a")
        val second = video(2, 4000, 7999, """inTime="5000"""", "b")
        val both = render(sceneXml(first, second), 8000)
        val half = both.size / 2
        val alone = render(sceneXml(first), 8000)
        val later = render(sceneXml(second), 8000)
        assertSameAwayFrom(FloatArray(both.size) { if (it < half) alone[it] else later[it] }, both, 4000)
        assertTrue(later.drop(half + 1000).maxOf { abs(it) } > 0.4f)
    }

    @Test fun aStreamOpenedDeepInItsMediaPlaysInsteadOfStayingSilent() {
        val source = Tone(440.0)
        val stream = LayerStream("tone", 4500.0 - LayerStream.LEAD_MS) { source }
        try {
            awaitCondition("the decoder to read ahead before the first prepare", 10_000) { source.reads >= 4 }
            awaitCondition("the stream to become ready at 4500 ms") { stream.prepare(4500.0) }
            val dt = 1000.0 / RATE
            val out = FloatArray(2 * FRAME)
            for (block in 0 until 6) {
                java.util.Arrays.fill(out, 0f)
                val pos = 4500.0 + block * FRAME * dt
                awaitCondition("sound for the block at $pos ms") { stream.mix(out, 0, FRAME, pos, dt, 1f, 1f, Resampler.ULTRA, 1000.0, dt, 1e9) }
                if (block == 0) continue
                for (k in 0 until FRAME) assertEquals(level(440.0, 4500L * RATE / 1000 + block * FRAME + k).toFloat(), out[2 * k], 2e-3f)
            }
        } finally {
            stream.close()
        }
    }

    private companion object {
        const val RATE = 48_000
        const val FRAME = 1024
        fun level(hz: Double, frame: Long) = 0.5 * sin(2 * PI * hz * frame / RATE)
    }
}
