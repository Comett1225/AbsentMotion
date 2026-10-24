package app.absentmotion

import app.absentmotion.model.trimHitsSourceLimit
import app.absentmotion.model.trimmedTo
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

    @Test fun ordinaryClipsStillStopAtTheirMedia() {
        val clip = videoLayer(0, 2000, inTime = 0, outTime = 2000)
        assertTrue(clip.trimHitsSourceLimit(start = false, wanted = 2500, mediaMs = 2000))
        assertEquals(2000, clip.trimmedTo(0, 2500, 2000).endTime)
    }
}
