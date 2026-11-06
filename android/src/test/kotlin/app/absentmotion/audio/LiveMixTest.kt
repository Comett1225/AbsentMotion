package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.sceneXml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMixTest {
    private val clips = AudioClip.of(sceneXml(
        """<audio id="1" src="a" startTime="0" endTime="999" outTime="30000"/>""",
        """<audio id="2" src="b" startTime="0" endTime="999" outTime="30000"><audioEffects><fx type="eq"/></audioEffects></audio>""",
    ))

    private fun block(rack: FxRack, out: FloatArray, a: Float, b: Float) {
        java.util.Arrays.fill(out, 0f)
        for ((clip, value) in clips.zip(listOf(a, b))) {
            val dst = rack.target(clip, out, 4)
            for (i in 0 until 8) dst[i] += if (i % 2 == 0) value else -value
            rack.finish(clip, out, 4, true)
        }
        rack.sweep()
    }

    @Test fun eachLayerGetsItsOwnPeakAndTheSumIsUnchanged() {
        val live = LiveMix()
        val out = FloatArray(8)
        FxRack(48_000) { _, _ -> null }.also { it.live = live }.use { rack -> block(rack, out, 0.5f, 0.25f) }
        assertEquals(0.5f, live.level(1), 1e-6f)
        assertEquals(0.25f, live.level(2), 1e-6f)
        assertEquals(0f, live.level(3), 0f)
        assertArrayEquals(FloatArray(8) { if (it % 2 == 0) 0.75f else -0.75f }, out, 1e-6f)
    }

    @Test fun soloKeepsOnlyTheSoloedLayersAndTheirMeters() {
        val live = LiveMix()
        live.solo = longArrayOf(2)
        val out = FloatArray(8)
        FxRack(48_000) { _, _ -> null }.also { it.live = live }.use { rack -> block(rack, out, 0.5f, 0.25f) }
        assertEquals(0f, live.level(1), 0f)
        assertEquals(0.25f, live.level(2), 1e-6f)
        assertArrayEquals(FloatArray(8) { if (it % 2 == 0) 0.25f else -0.25f }, out, 1e-6f)
        assertTrue(live.silenced(1))
        live.solo = LongArray(0)
        assertFalse(live.silenced(1))
    }

    @Test fun withoutALiveMixAClipWithoutEffectsStillMixesStraightIntoTheOutput() {
        val out = FloatArray(8)
        FxRack(48_000) { _, _ -> null }.use { rack -> assertSame(out, rack.target(clips[0], out, 4)) }
    }

    @Test fun aLayerThatStopsBeingMixedReadsSilentAfterABlock() {
        val live = LiveMix()
        live.put(7, 0.9f)
        assertEquals(0.9f, live.level(7), 0f)
        live.next()
        assertEquals("one block late is still shown", 0.9f, live.level(7), 0f)
        live.next()
        assertEquals(0f, live.level(7), 0f)
    }

    @Test fun aFullTableDropsNewLayersInsteadOfGrowing() {
        val live = LiveMix(capacity = 2)
        live.put(1, 0.1f)
        live.put(2, 0.2f)
        live.put(3, 0.3f)
        assertEquals(0.2f, live.level(2), 0f)
        assertEquals(0f, live.level(3), 0f)
        live.put(1, 0.4f)
        assertEquals(0.4f, live.level(1), 0f)
    }

    @Test fun aFullTableReusesTheSlotOfALayerThatStoppedPlaying() {
        val live = LiveMix(capacity = 2)
        live.put(1, 0.1f)
        live.put(2, 0.2f)
        live.next()
        live.next()
        live.put(2, 0.5f)
        live.put(3, 0.3f)
        assertEquals(0.3f, live.level(3), 0f)
        assertEquals(0.5f, live.level(2), 0f)
        assertEquals(0f, live.level(1), 0f)
    }

    @Test fun leftAndRightAreMeasuredApartForEachLayerAndTheMaster() {
        val live = LiveMix()
        val out = FloatArray(8)
        FxRack(48_000) { _, _ -> null }.also { it.live = live }.use { rack ->
            val dst = rack.target(clips[0], out, 4)
            for (k in 0 until 4) {
                dst[2 * k] = if (k % 2 == 0) 0.6f else -0.3f
                dst[2 * k + 1] = -0.2f
            }
            rack.finish(clips[0], out, 4, true, 2)
            rack.sweep()
        }
        assertEquals(0.6f, live.left(1), 1e-6f)
        assertEquals(0.2f, live.right(1), 1e-6f)
        assertEquals("the single value is the louder side", 0.6f, live.level(1), 1e-6f)
        assertFalse(live.mono(1))
        assertArrayEquals(floatArrayOf(0.6f, -0.2f, -0.3f, -0.2f, 0.6f, -0.2f, -0.3f, -0.2f), out, 1e-6f)

        val limiter = SafeLimiter(48_000)
        limiter.process(floatArrayOf(0.5f, -0.25f, -0.4f, 0.1f), 2)
        assertEquals(0.5f, limiter.left, 0f)
        assertEquals(0.25f, limiter.right, 0f)
        assertEquals(0.5f, limiter.peak, 0f)
        live.master(limiter.left, limiter.right)
        assertEquals(0.5f, live.masterLeft, 0f)
        assertEquals(0.25f, live.masterRight, 0f)
        assertEquals(0.5f, live.master, 0f)
    }

    @Test fun aMonoSourceStaysKnownAsMonoUntilItsSlotGoesToAnotherLayer() {
        val live = LiveMix(capacity = 1)
        assertFalse("unknown reads as stereo", live.mono(1))
        live.put(1, 0.3f, 0.3f, 1)
        assertTrue(live.mono(1))
        live.put(1, 0f, 0f, 0)
        assertTrue("a block without a stream keeps what was learnt", live.mono(1))
        live.next()
        live.next()
        assertEquals(0f, live.level(1), 0f)
        assertTrue("still mono after it stops playing", live.mono(1))
        live.put(2, 0.1f)
        assertFalse(live.mono(2))
        assertFalse(live.mono(1))
        live.put(2, 0.1f, 0.2f, 2)
        assertFalse(live.mono(2))
    }

    @Test fun theMixerLearnsFromTheDecoderWhichSourcesAreMono() {
        val rate = 48_000
        val block = 1024
        val pair = AudioClip.of(sceneXml(
            """<audio id="1" src="mono" startTime="0" endTime="2999" outTime="3000"/>""",
            """<audio id="2" src="stereo" startTime="0" endTime="2999" outTime="3000"/>""",
        ))
        fun source(channels: Int, right: Float) = object : PcmSource {
            private val tone = ToneSource(440.0)
            override val durationUs get() = tone.durationUs
            override fun seek(us: Long) = tone.seek(us)
            override fun next(): PcmChunk? = tone.next()?.also { c ->
                c.channels = channels
                for (i in 0 until c.frames) c.pcm[2 * i + 1] = (c.pcm[2 * i + 1] * right).toInt().toShort()
            }
            override fun close() {}
        }
        val streams = mapOf(1L to LayerStream("mono", 0.0) { source(1, 1f) }, 2L to LayerStream("stereo", 0.0) { source(2, 0.25f) })
        val live = LiveMix()
        val out = FloatArray(2 * block)
        try {
            FxRack(rate) { _, _ -> null }.also { it.live = live }.use { rack ->
                for (s in streams.values) awaitCondition("sound at 500 ms") { s.prepare(500.0) }
                assertEquals(1, streams.getValue(1).channels)
                assertEquals(2, streams.getValue(2).channels)
                mixStreams(pair, out, block, 500.0, rate, Resampler.ULTRA, rack) { streams[it.id] }
            }
        } finally {
            streams.values.forEach { it.close() }
        }
        assertTrue(live.mono(1))
        assertFalse(live.mono(2))
        assertEquals(0.5f, live.left(1), 0.02f)
        assertEquals(0.5f, live.right(1), 0.02f)
        assertEquals(0.5f, live.left(2), 0.02f)
        assertEquals(0.125f, live.right(2), 0.01f)
    }
}
