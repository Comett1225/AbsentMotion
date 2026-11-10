package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.remapped
import app.absentmotion.model.reversedInTime
import app.absentmotion.model.trimmedTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRemapTest {
    private fun remapped(vararg keys: Pair<String, String>): Layer {
        val layer = videoLayer(0, 4000, inTime = 0)
        val map = XmlNode("timeRemap", children = keys.map { (t, v) -> XmlNode("kf", mapOf("t" to t, "v" to v)) })
        return layer.copy(node = layer.node.copy(children = layer.node.children + map))
    }

    @Test fun clipTimeFollowsTheKeyframesForwardFrozenAndBackwards() {
        val timing = VideoTiming(remapped("0" to "1", "0.25" to "3", "0.5" to "3", "1" to "0"))
        assertEquals(1000.0, timing.mediaMs(0.0), 1.0)
        assertEquals(2000.0, timing.mediaMs(500.0), 1.0)
        assertEquals(3000.0, timing.mediaMs(1500.0), 1.0)
        assertEquals(1500.0, timing.mediaMs(3000.0), 1.0)
        assertEquals(0f, timing.speed(1500.0), 0.01f)
        assertEquals(1.5f, timing.speed(3000.0), 0.05f)
        assertEquals(500.0, timing.localMs(2000.0), 20.0)
    }

    @Test fun onlyRemappedLayersAreMarked() {
        assertTrue(remapped("0" to "0", "1" to "4").remapped)
        assertFalse(videoLayer(0, 4000).remapped)
        assertEquals(2000.0, VideoTiming(videoLayer(0, 4000, inTime = 0)).mediaMs(2000.0), 1.0)
    }

    @Test fun reverseTurnsTheKeyframesAroundInTimeAndNeverGoesNegative() {
        val layer = remapped("0" to "0", "0.5" to "4", "1" to "0")
        val reversed = layer.copy(node = layer.node.copy(children = layer.node.children.map { if (it.name == "timeRemap") it.reversedInTime() else it }))
        val timing = VideoTiming(reversed)
        assertEquals(0.0, timing.mediaMs(0.0), 1.0)
        assertEquals(4000.0, timing.mediaMs(2000.0), 1.0)
        assertTrue((0..40).all { timing.mediaMs(it * 100.0) >= 0.0 })
        val forward = VideoTiming(remapped("0" to "1", "1" to "3").let { l -> l.copy(node = l.node.copy(children = l.node.children.map { if (it.name == "timeRemap") it.reversedInTime() else it })) })
        assertEquals(3000.0, forward.mediaMs(0.0), 1.0)
        assertEquals(1000.0, forward.mediaMs(4000.0), 1.0)
    }

    @Test fun splittingAReversedRemapKeepsBothHalvesMoving() {
        val layer = remapped("0" to "4", "1" to "0")
        val first = layer.trimmedTo(0, 2000)
        val second = layer.trimmedTo(2000, 4000)
        assertEquals(layer.node["inTime"], first.node["inTime"])
        assertEquals(layer.node["outTime"], second.node["outTime"])
        assertEquals(4000.0, VideoTiming(first).mediaMs(0.0), 1.0)
        assertEquals(3000.0, VideoTiming(first).mediaMs(1000.0), 1.0)
        assertEquals(1000.0, VideoTiming(second).mediaMs(1000.0), 1.0)
    }
}
