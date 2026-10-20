package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonFiniteKeyframeTest {
    @Test fun keyframesWithNonFiniteTimesAreIgnoredInsteadOfCrashing() {
        val node = XmlNode("opacity", emptyMap(), listOf(
            XmlNode("kf", linkedMapOf("t" to "0", "v" to "1")),
            XmlNode("kf", linkedMapOf("t" to "NaN", "v" to "2")),
            XmlNode("kf", linkedMapOf("t" to "Infinity", "v" to "3")),
            XmlNode("kf", linkedMapOf("t" to "-Infinity", "v" to "4")),
            XmlNode("kf", linkedMapOf("v" to "5")),
        ))
        assertEquals(listOf(0f, 0f), node.keyframes.map { it.t })
        for (t in listOf(-1f, 0f, 0.5f, 1f, 2f)) assertTrue(node.valueAt(t) in setOf("1", "5"))
    }

    @Test fun aSpeedMapWithNonFiniteKeysOrValuesStillGivesFiniteTimes() {
        val map = XmlNode("speedMap", linkedMapOf("value" to "NaN"), listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "1")),
            XmlNode("kf", mapOf("t" to "NaN", "v" to "2")),
            XmlNode("kf", mapOf("t" to "1000000000", "v" to "NaN")),
            XmlNode("kf", mapOf("t" to "2000000000", "v" to "2")),
        ))
        val layer = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "3000", "fillType" to "media",
            "fillVideo" to "content://v", "speed" to "NaN"), listOf(map)))
        val timing = VideoTiming(layer)
        for (ms in listOf(0.0, 500.0, 1500.0, 2999.0, 5000.0)) assertTrue(timing.mediaMs(ms).isFinite())
        val constant = Layer(layer.node.copy(children = listOf(XmlNode("speedMap", linkedMapOf("value" to "NaN")))))
        assertEquals(1000.0, VideoTiming(constant).mediaMs(1000.0), 1e-6)
    }
}
