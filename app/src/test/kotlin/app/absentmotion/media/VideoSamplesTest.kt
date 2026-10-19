package app.absentmotion.media

import app.absentmotion.model.Scene
import app.absentmotion.model.splitAt
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSamplesTest {
    private val whole = Scene.parse("""<scene width="100" height="100" fps="30"><shape id="1" startTime="0" endTime="7999" fillType="media" fillVideo="v.mp4" s=".rect"/></scene>""")
    private val split = whole.splitAt(whole.layer(1)!!, 135, 2)!!.let { (first, second) -> whole.update(1) { first }.add(second) }

    private fun at(timeMs: Int, playing: Boolean, warm: Boolean = true) =
        split.videoSamples(timeMs, playing, warm).associate { it.layer.id to it.timeMs.toInt() }

    @Test fun playbackOpensTheNextClipBeforeItIsReached() {
        assertEquals(mapOf(1L to 4000, 2L to 4500), at(4000, playing = true))
        assertEquals(mapOf(1L to 3000), at(3000, playing = true))
    }

    @Test fun playbackLetsThePreviousClipGo() {
        assertEquals(mapOf(2L to 4600), at(4600, playing = true))
    }

    @Test fun aPausedPlayheadKeepsBothNeighboursReadyForScrubbing() {
        assertEquals(mapOf(1L to 4466, 2L to 4600), at(4600, playing = false))
        assertEquals(mapOf(1L to 4400, 2L to 4500), at(4400, playing = false))
    }

    @Test fun exactFramesOnlyDecodeWhatIsShown() {
        assertEquals(mapOf(1L to 4400), at(4400, playing = false, warm = false))
        assertEquals(mapOf(2L to 4500), at(4500, playing = false, warm = false))
        assertEquals(mapOf(1L to 4499), at(4499, playing = false, warm = false))
    }
}
