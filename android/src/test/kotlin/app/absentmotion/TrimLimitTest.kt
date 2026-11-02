package app.absentmotion

import app.absentmotion.editor.navigationStops
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.trimEndAt
import app.absentmotion.model.trimHitsSourceLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimLimitTest {
    @Test fun theEndHitsTheLimitOnlyPastTheSourceEnd() {
        val clip = videoLayer(1000, 2000, inTime = 0)
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 2500, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 4000, mediaMs = 3000))
        assertTrue(clip.trimHitsSourceLimit(start = false, wanted = 4001, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = false, wanted = 1500, mediaMs = 3000))
    }

    @Test fun theStartHitsTheLimitOnlyBeforeTheSourceStart() {
        val clip = videoLayer(1000, 2000, inTime = 400)
        assertFalse(clip.trimHitsSourceLimit(start = true, wanted = 600, mediaMs = 3000))
        assertTrue(clip.trimHitsSourceLimit(start = true, wanted = 599, mediaMs = 3000))
        assertFalse(clip.trimHitsSourceLimit(start = true, wanted = 1500, mediaMs = 3000))
    }

    @Test fun speedChangesWhereTheEndLimitIs() {
        val doubled = videoLayer(0, 1000, inTime = 0, speed = "2")
        assertFalse(doubled.trimHitsSourceLimit(start = false, wanted = 1500, mediaMs = 3000))
        assertTrue(doubled.trimHitsSourceLimit(start = false, wanted = 1600, mediaMs = 3000))
    }

    @Test fun skippingBetweenKeyframesStaysOnTheTrimmedClip() {
        val keys = XmlNode("location", children = listOf("0", "0.5", "1").map { XmlNode("kf", mapOf("t" to it, "v" to "0,0,0")) })
        val shape = shapeLayer(end = 9999, children = listOf(XmlNode("transform", children = listOf(keys))))
        val full = Scene.create("t", 100, 100, 30, 0).add(shape)
        assertEquals(listOf(0, 150, 299), full.navigationStops(full.layer(1)))
        val trimmed = full.update(1) { full.trimEndAt(it, 149)!! }
        assertEquals(4999, trimmed.layer(1)!!.endTime)
        assertEquals(listOf(0, 149), trimmed.navigationStops(trimmed.layer(1)))
        assertEquals(listOf(0, 149), trimmed.navigationStops(null))
        val marked = trimmed.withBookmarks(listOf(trimmed.frameStart(40), trimmed.frameStart(90)))
        assertEquals(listOf(0, 40, 90, 149), marked.navigationStops(null))
        assertEquals(listOf(0, 40, 90, 149), marked.navigationStops(marked.layer(1)))
    }

    @Test fun unknownLengthAndStillLayersNeverHitTheLimit() {
        assertFalse(videoLayer(0, 1000, inTime = 0).trimHitsSourceLimit(start = false, wanted = 99_000))
        val shape = shapeLayer(id = 2, start = 1000, end = 2000)
        assertFalse(shape.trimHitsSourceLimit(start = false, wanted = 99_000, mediaMs = 3000))
        assertFalse(shape.trimHitsSourceLimit(start = true, wanted = 0, mediaMs = 3000))
    }
}
