package app.absentmotion.audio

import app.absentmotion.sceneXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveMixTest {
    private val clips = AudioClip.of(sceneXml(
        """<audio id="1" src="a" startTime="0" endTime="999" outTime="30000"/>""",
    ))

    @Test fun aClipWithoutEffectsMixesStraightIntoTheOutput() {
        val out = FloatArray(8)
        FxRack(48_000) { _, _ -> null }.use { rack -> assertSame(out, rack.target(clips[0], out, 4)) }
    }

    @Test fun theMasterKeepsLeftAndRightApart() {
        val limiter = SafeLimiter(48_000)
        limiter.process(floatArrayOf(0.5f, -0.25f, -0.4f, 0.1f), 2)
        assertEquals(0.5f, limiter.left, 0f)
        assertEquals(0.25f, limiter.right, 0f)
        assertEquals(0.5f, limiter.peak, 0f)
        val live = LiveMix()
        live.master(limiter.left, limiter.right)
        assertEquals(0.5f, live.masterLeft, 0f)
        assertEquals(0.25f, live.masterRight, 0f)
        assertEquals(0.5f, live.master, 0f)
    }
}
