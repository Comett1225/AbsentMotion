package app.absentmotion.audio

import app.absentmotion.model.Scene
import app.absentmotion.model.splitAt
import org.junit.Assert.*
import org.junit.Test

class GroupedAudioTest {
    private fun scene(start: Int = 2000, end: Int = 3999, speed: Int = 1, retime: String = "off", childStart: Int = 100, childEnd: Int = 999) = Scene.parse("""
        <scene width="100" height="100" fps="30"><embedScene id="1" startTime="$start" endTime="$end" speed="$speed">
        <scene width="100" height="100" fps="30" totalTime="999" retime="$retime" retimeOut="500">
        <audio id="2" src="tone" startTime="$childStart" endTime="$childEnd" outTime="1000"/>
        </scene></embedScene></scene>""")

    @Test fun groupedAudioIsFoundAndUsesTheGroupLocalTime() {
        val clip = AudioClip.of(scene()).single()
        assertEquals(150.0, clip.mediaAt(2250.0), 1e-6)
        val span = clip.span(2050.0, 1000, 1.0)!!
        assertEquals(50, span.from)
        assertEquals(950, span.to)
        assertEquals(0.0, span.pos, 1e-6)
    }

    @Test fun aSoundThatStartsLateInAGroupIsReadiedAtItsInPointNotBeforeIt() {
        val late = Scene.parse("""
            <scene width="100" height="100" fps="30"><embedScene id="1" startTime="0" endTime="19999">
            <scene width="100" height="100" fps="30" totalTime="19999">
            <audio id="2" src="tone" startTime="5000" endTime="9999" inTime="8000" outTime="13000"/>
            </scene></embedScene></scene>""")
        val clip = AudioClip.of(late).single()
        assertEquals(8000.0 to true, clip.cue(0.0))
        assertEquals(8000.0 to true, clip.cue(4900.0))
        val (playing, early) = clip.cue(6000.0)
        assertEquals(9000.0, playing, 1e-6)
        assertFalse(early)
        val top = AudioClip.of(Scene.parse("""<scene width="100" height="100" fps="30"><audio id="3" src="tone" startTime="5000" endTime="9999" inTime="8000" outTime="13000"/></scene>""")).single()
        assertEquals(8000.0 to true, top.cue(1000.0))
        assertEquals(8500.0 to false, top.cue(5500.0))
    }

    @Test fun groupSpeedChangesTheSampleStep() {
        val clip = AudioClip.of(scene(speed = 2, childStart = 0)).single()
        assertEquals(500.0, clip.mediaAt(2250.0), 1e-6)
        assertEquals(2.0, clip.span(2000.0, 100, 1.0)!!.step, 1e-6)
    }

    @Test fun loopedGroupSplitsTheAudioSpanAtTheDiscontinuity() {
        val clip = AudioClip.of(scene(start = 0, end = 1999, retime = "loop", childStart = 0)).single()
        assertEquals(100.0, clip.mediaAt(1100.0), 1e-6)
        val first = clip.span(490.0, 30, 1.0)!!
        assertEquals(10, first.to)
        val next = clip.span(490.0, 30, 1.0, first.to)!!
        assertEquals(10, next.from)
        assertEquals(0.0, next.pos, 1e-6)
    }

    @Test fun hiddenGroupsDoNotScheduleTheirAudio() {
        val root = scene()
        val group = root.layers.single()
        val hidden = Scene(root.node.withChild("embedScene", group.node.with("hidden", "true")))
        assertTrue(AudioClip.of(hidden).isEmpty())
    }

    @Test fun videoSoundAndGroupedAudioCountAsExportAudio() {
        val video = Scene.parse("""<scene width="100" height="100" fps="30"><shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="file:/v.mp4" s=".rect"/></scene>""")
        assertEquals("file:/v.mp4", AudioClip.of(video).single().uri)
        assertFalse(scene().layers.any { it.kind == "audio" })
        assertTrue(AudioClip.of(scene()).isNotEmpty())
    }

    private class CountingFx : FakeFx() {
        var frames = 0L
        override fun process(buf: FloatArray, frames: Int) { this.frames += frames }
    }

    @Test fun eachHalfOfASplitGroupRunsItsSoundThroughItsOwnEffectChain() {
        val whole = Scene.parse("""
            <scene width="100" height="100" fps="30"><embedScene id="1" startTime="0" endTime="1999">
            <scene width="100" height="100" fps="30" totalTime="1999">
            <audio id="2" src="tone" startTime="0" endTime="1999" outTime="30000"><audioEffects><fx type="eq"/></audioEffects></audio>
            </scene></embedScene></scene>""")
        val (first, second) = whole.splitAt(whole.layer(1)!!, 30, 3)!!
        val clips = AudioClip.of(whole.update(1) { first }.add(second))
        assertEquals(2, clips.map { it.id }.toSet().size)
        val chains = mutableListOf<CountingFx>()
        OfflineMix({ ToneSource(440.0) }, clips, Resampler.ULTRA, { _, _ -> CountingFx().also { chains += it } }, SafeLimiter(44_100), 44_100)
            .use { it(0, 2_000_000) }
        assertEquals(2, chains.size)
        assertTrue(chains.all { it.frames <= 88_200 + 2048 })
    }
}
