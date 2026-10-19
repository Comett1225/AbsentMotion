package app.absentmotion.audio

import app.absentmotion.model.Scene
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
}
