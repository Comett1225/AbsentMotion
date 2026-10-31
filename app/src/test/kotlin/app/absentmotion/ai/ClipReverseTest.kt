package app.absentmotion.ai

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.videoLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipReverseTest {
    private val original = MediaDeclaration(XmlNode("media", mapOf("uri" to "content://v", "duration" to "10000000")))
    private val copy = MediaDeclaration(XmlNode("media", mapOf("uri" to "file://r.mp4", "duration" to "10000000")))

    @Test fun reversingMirrorsTheTrimAndTogglingBackRestoresIt() {
        val layer = videoLayer(1000, 3000, inTime = 2000, outTime = 4000)
        val scene = Scene.create("t", 100, 100, 30, 0).withMedia(original).add(layer)
        val reversed = scene.update(1) { it.reversedTo(copy.uri, 10_000_000, original) }.withMedia(copy)
        val turned = reversed.layer(1)!!
        assertEquals("file://r.mp4", turned.node["fillVideo"])
        assertEquals("6000", turned.node["inTime"])
        assertEquals("8000", turned.node["outTime"])
        assertEquals("content://v", turned.reverseMark!!.original)
        assertNull(reversed.media("content://v"))
        val back = reversed.unreversed(1)
        val restored = back.layer(1)!!
        assertEquals(layer.node, restored.node)
        assertTrue(restored.aiMarks.isEmpty())
        assertEquals(original, back.media("content://v"))
        assertNull(back.media("file://r.mp4"))
    }

    @Test fun aSpeedRampPlaysBackwardsAndComesBack() {
        val ramp = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "2000000000", "v" to "0.5")),
            XmlNode("kf", mapOf("t" to "4000000000", "v" to "2", "e" to "linear")),
        ))
        val layer = videoLayer(0, 2500, inTime = 2000, children = listOf(ramp))
        val mirror = 9000.0
        val turned = layer.mirroredMedia(mirror)
        val before = VideoTiming(layer)
        val after = VideoTiming(turned)
        for (step in 0..10) {
            val t = 2500.0 * step / 10
            assertEquals(mirror - before.mediaMs(2500 - t), after.mediaMs(t), 1.0)
        }
        val again = turned.mirroredMedia(mirror)
        assertEquals(layer.node["inTime"], again.node["inTime"])
        assertEquals(ramp.keyframes.map { it.t to it.v }, again.node.child("speedMap")!!.keyframes.map { it.t to it.v })
    }

    @Test fun timeLeftComesFromTheSpeedSoFar() {
        assertNull(reverseSecondsLeft(2, 300, 5_000_000_000L))
        assertNull(reverseSecondsLeft(30, 300, 500_000_000L))
        assertEquals(45, reverseSecondsLeft(100, 400, 15_000_000_000L))
        assertEquals(1, reverseSecondsLeft(300, 300, 9_000_000_000L))
        assertEquals(200, reverseSecondsLeft(10, 1010, 2_000_000_000L))
    }

    @Test fun piecesStayWithinTheHeldFramesAndRunBackwards() {
        val stamps = LongArray(100) { it * 33_333L }
        val syncs = longArrayOf(0, stamps[30], stamps[60], stamps[90])
        val pieces = reversePieces(stamps, syncs, 10, 95, 20)
        assertTrue(pieces.all { it.count() in 1..20 })
        assertEquals((10 until 95).toList(), pieces.reversed().flatten())
        assertTrue(listOf(30, 60, 90).all { sync -> pieces.any { it.first == sync } })
    }
}
