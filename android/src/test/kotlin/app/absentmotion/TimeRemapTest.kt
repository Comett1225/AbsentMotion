package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.remapped
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
}
