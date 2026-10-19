package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.trimHitsSourceLimit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimLimitTest {
    private fun video(start: Int, end: Int, inTime: Int, speed: String? = null) = Layer(XmlNode("shape", linkedMapOf("id" to "1",
        "startTime" to "$start", "endTime" to "$end", "fillType" to "media", "fillVideo" to "content://v", "inTime" to "$inTime")).with("speed", speed))

    @Test fun theEndHitsTheLimitOnlyPastTheSourceEnd() {
        val clip = video(1000, 2000, 0)
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 2500, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 4000, mediaMs = 3000))
        assertTrue(clip.trimHitsSourceLimit(start = false, wanted = 4001, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 1500, mediaMs = 3000))
    }

    @Test fun theStartHitsTheLimitOnlyBeforeTheSourceStart() {
        val clip = video(1000, 2000, 400)
        assertFalse(clip.trimHitsSourceLimit(start = true, wanted = 600, mediaMs = 3000))
        assertTrue(clip.trimHitsSourceLimit(start = true, wanted = 599, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = true, wanted = 1500, mediaMs = 3000))
    }

    @Test fun speedChangesWhereTheEndLimitIs() {
        val doubled = video(0, 1000, 0, speed = "2")
        assertFalse(doubled.trimHitsSourceLimit(start = false, wanted = 1500, mediaMs = 3000))
        assertTrue(doubled.trimHitsSourceLimit(start = false, wanted = 1600, mediaMs = 3000))
    }

    @Test fun unknownLengthAndStillLayersNeverHitTheLimit() {
        assertFalse(video(0, 1000, 0).trimHitsSourceLimit(start = false, wanted = 99_000))
        val shape = Layer(XmlNode("shape", linkedMapOf("id" to "2", "startTime" to "1000", "endTime" to "2000")))
        assertFalse(shape.trimHitsSourceLimit(start = false, wanted = 99_000, mediaMs = 3000))
        assertFalse(shape.trimHitsSourceLimit(start = true, wanted = 0, mediaMs = 3000))
    }
}
