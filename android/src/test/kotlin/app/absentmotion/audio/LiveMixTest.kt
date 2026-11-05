package app.absentmotion.audio

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
}
