package app.absentmotion.ui.editor

import androidx.compose.ui.unit.dp
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerTest {
    @Test fun stripsAreTheLayersThatMakeSoundInTimelineOrder() {
        val scene = Scene.parse("""<scene width="1920" height="1080" fps="30">
  <media uri="with.mp4" type="video/mp4" />
  <media uri="silent.mp4" type="video/mp4" audio="none" />
  <audio id="1" label="Music" src="music.m4a" startTime="0" endTime="999" />
  <shape id="2" label="Picture" fillType="media" fillImage="photo.jpg" startTime="0" endTime="999" />
  <shape id="3" label="Clip" fillType="media" fillVideo="with.mp4" startTime="0" endTime="999" />
  <shape id="4" label="Silent clip" fillType="media" fillVideo="silent.mp4" startTime="0" endTime="999" />
  <text id="5" label="Title" startTime="0" endTime="999" />
  <audio id="6" label="Voice" src="voice.wav" startTime="0" endTime="999" hidden="true" />
</scene>""")
        assertEquals(listOf("Voice", "Clip", "Music"), mixerLayers(scene).map { it.label })
        assertTrue(mixerLayers(Scene.parse("""<scene width="100" height="100" fps="30"><text id="1" startTime="0" endTime="9" /></scene>""")).isEmpty())
    }

    @Test fun theTabletMixerOnlyShowsWhenTheCanvasKeepsItsSize() {
        assertTrue("16:9 on a landscape tablet", mixerFits(1280.dp, 392.dp, 1920, 1080))
        assertFalse("16:9 on a portrait tablet", mixerFits(800.dp, 656.dp, 1920, 1080))
        assertTrue("9:16 on a portrait tablet", mixerFits(800.dp, 656.dp, 1080, 1920))
        assertFalse("square that would shrink", mixerFits(900.dp, 600.dp, 1000, 1000))
        assertFalse("no room for the preview at all", mixerFits(1280.dp, 0.dp, 1920, 1080))
    }

    @Test fun theFaderIsLinearLikeTheVolumePanelAndSnapsToWholePercent() {
        assertEquals(0f, MixerFader.fraction(0f), 0f)
        assertEquals(1f, MixerFader.fraction(1.5f), 0f)
        assertEquals(1f, MixerFader.gain(MixerFader.fraction(1f)), 0f)
        assertEquals(0.75f, MixerFader.gain(0.5f), 0f)
        assertEquals(1.5f, MixerFader.gain(2f), 0f)
        assertEquals(0f, MixerFader.gain(-1f), 0f)
        assertEquals("dragging up by the whole travel goes from silent to the top", 1.5f, MixerFader.dragged(0f, -300f, 300f), 0f)
        assertEquals("dragging down by a third of the travel takes 50 points", 0.5f, MixerFader.dragged(1f, 100f, 300f), 0f)
        assertEquals(1f, MixerFader.dragged(1f, 0.4f, 300f), 0f)
    }

    @Test fun theMeterRisesAtOnceHoldsItsPeakThenFalls() {
        assertEquals(1f, MeterBallistics.position(1f), 0f)
        assertEquals(0f, MeterBallistics.position(0.0039f), 0f)
        assertEquals(0.875f, MeterBallistics.position(0.5012f), 0.001f)
        assertNull(MeterBallistics.decibels(0f))
        assertEquals(-6, MeterBallistics.decibels(0.875f))
        val meter = MeterBallistics()
        meter.step(0.5012f, 0.016f)
        assertEquals(0.875f, meter.shown, 0.001f)
        assertEquals(meter.shown, meter.peak, 0f)
        repeat(30) { meter.step(0f, 0.016f) }
        assertTrue("the bar falls", meter.shown < 0.02f)
        assertEquals("the peak tick holds for 0.6 s", 0.875f, meter.peak, 0.001f)
        repeat(10) { meter.step(0f, 0.016f) }
        assertTrue("then the tick falls", meter.peak < 0.875f)
        assertEquals(0f, meter.clip, 0f)
        meter.step(1.2f, 0.016f)
        assertEquals(1f, meter.shown, 0f)
        assertTrue(meter.clip > 0f)
        meter.reset()
        assertEquals(0f, meter.peak, 0f)
    }
}
