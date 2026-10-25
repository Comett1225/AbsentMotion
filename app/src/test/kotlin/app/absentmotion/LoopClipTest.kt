package app.absentmotion

import app.absentmotion.audio.AudioClip
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.splitAt
import app.absentmotion.model.trimHitsSourceLimit
import app.absentmotion.model.trimmedTo
import app.absentmotion.model.videoTimeUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopClipTest {
    private val gif = videoLayer(0, 2000, inTime = 0, outTime = 700, attrs = mapOf("loop" to "true"))

    @Test fun aLoopingClipCanBeStretchedPastItsMediaAndKeepsItsLoop() {
        assertFalse(gif.trimHitsSourceLimit(start = false, wanted = 9000, mediaMs = 700))
        val longer = gif.trimmedTo(0, 9000, 700)
        assertEquals(9000, longer.endTime)
        assertEquals("700", longer.node["outTime"])
        val shorter = gif.trimmedTo(0, 300, 700)
        assertEquals("700", shorter.node["outTime"])
    }

    @Test fun splittingOrTrimmingALoopKeepsItsLoopWindowForPictureAndSound() {
        val clip = videoLayer(0, 5000, inTime = 200, outTime = 900, attrs = mapOf("loop" to "true"))
        val scene = sceneOf(clip.node, fps = 30)
        val (first, second) = scene.splitAt(clip, 50, 2)!!
        assertEquals("200", second.node["loopIn"])
        for (t in 0..4999 step 37) assertEquals("at $t", clip.videoTimeUs(t, scene), (if (t < second.startTime) first else second).videoTimeUs(t, scene))
        val later = clip.trimmedTo(2500, 5000)
        for (t in 2500..4999 step 41) assertEquals("at $t", clip.videoTimeUs(t, scene), later.videoTimeUs(t, scene))
        assertEquals(clip.videoTimeUs(4000, scene), later.trimmedTo(3100, 5000).videoTimeUs(4000, scene))
        val sound = Layer(XmlNode("audio", linkedMapOf("id" to "3", "src" to "a.wav", "startTime" to "0", "endTime" to "5000", "inTime" to "200", "outTime" to "900", "loop" to "true")))
        val whole = AudioClip(sound, "a.wav")
        val tail = AudioClip(sound.trimmedTo(1700, 5000), "a.wav")
        for (t in 1700..4999 step 53) assertEquals("at $t", whole.wrap(whole.mediaAt(t.toDouble())), tail.wrap(tail.mediaAt(t.toDouble())), 1e-6)
    }

    @Test fun ordinaryClipsStillStopAtTheirMedia() {
        val clip = videoLayer(0, 2000, inTime = 0, outTime = 2000)
        assertTrue(clip.trimHitsSourceLimit(start = false, wanted = 2500, mediaMs = 2000))
        assertEquals(2000, clip.trimmedTo(0, 2500, 2000).endTime)
    }
}
