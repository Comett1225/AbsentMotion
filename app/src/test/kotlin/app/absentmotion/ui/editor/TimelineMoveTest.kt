package app.absentmotion.ui.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMoveTest {
    private val fps = 30
    private val second = 1000f
    private val zone = 20f

    private fun move(wantedMs: Float, targets: Set<Int> = emptySet(), spanFrames: Int = 30, zoneMs: Float = zone) =
        TimelineMath.moveTarget(wantedMs, spanFrames * second / fps, spanFrames, targets, fps, zoneMs)

    private fun sceneWith(clip: XmlNode, rate: Int = fps) =
        Scene.create("t", 1080, 1080, rate, 0).let { Scene(it.node.copy(children = it.node.children + clip)) }

    @Test fun withNothingToSnapToTheStartLandsOnTheNearestFrame() {
        assertEquals(MoveTarget(30, null), move(1010f))
        assertEquals(MoveTarget(31, null), move(1020f))
        assertEquals(MoveTarget(29, null), move(980f))
    }

    @Test fun theStartNeverGoesBelowZero() {
        assertEquals(MoveTarget(0, null), move(-250f))
        assertEquals(MoveTarget(0, null), move(-0.4f))
        val tailWouldNeedANegativeStart = TimelineMath.frameTimeMs(10, fps) - second
        assertEquals(MoveTarget(0, null), move(tailWouldNeedANegativeStart, setOf(10)))
    }

    @Test fun theLeadingEdgeSnapsToATargetInsideTheZone() {
        val at = TimelineMath.frameTimeMs(60, fps)
        assertEquals(MoveTarget(60, 60), move(at + 5f, setOf(60)))
        assertEquals(MoveTarget(60, 60), move(at - 15f, setOf(60)))
        assertEquals(MoveTarget(62, null), move(at + 70f, setOf(60)))
    }

    @Test fun theTrailingEdgeSnapsAndTheClipKeepsItsLengthInFrames() {
        val start = TimelineMath.frameTimeMs(60, fps)
        assertEquals(MoveTarget(60, 90), move(start + 6f, setOf(90)))
        assertEquals(MoveTarget(70, 100), move(TimelineMath.frameTimeMs(70, fps) - 9f, setOf(100)))
    }

    @Test fun theEdgeNearerToItsTargetWins() {
        val start = TimelineMath.frameTimeMs(60, fps)
        val targets = setOf(60, 91)
        assertEquals(MoveTarget(60, 60), move(start + 10f, targets, zoneMs = 40f))
        assertEquals(MoveTarget(61, 91), move(start + 30f, targets, zoneMs = 40f))
    }

    @Test fun movingKeepsTheLengthAndCarriesTheKeyframesAlong() {
        val key = { t: String -> XmlNode("kf", mapOf("t" to t, "v" to "0,0,0")) }
        val clip = XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "999"),
            listOf(XmlNode("transform", children = listOf(XmlNode("location", children = listOf(key("0"), key("0.3")))))))
        val scene = sceneWith(clip)
        val layer = scene.layers.single()
        assertEquals(listOf(0, 300), layer.keyframeTimes())
        for (frame in listOf(0, 1, 7, 100)) {
            val start = TimelineMath.movedStart(scene, layer, frame)
            val moved = layer.movedTo(start)
            assertEquals(layer.endTime - layer.startTime, moved.endTime - moved.startTime)
            assertEquals(frame, scene.firstShownFrame(moved.startTime))
            assertEquals(frame + 29, scene.lastShownFrame(moved.endTime))
            assertEquals(layer.keyframeTimes().map { it + start }, moved.keyframeTimes())
        }
    }

    @Test fun aStartOffTheFrameGridComesBackExactlyWhenTheClipReturnsToItsFrame() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "3482", "endTime" to "3981")))
        val scene = sceneWith(layer.node, rate = 60)
        val home = scene.firstShownFrame(layer.startTime)
        assertEquals(209, home)
        assertEquals(3482, TimelineMath.movedStart(scene, layer, home))
        assertEquals(scene.frameStart(210), TimelineMath.movedStart(scene, layer, home + 1))
        val away = layer.movedTo(TimelineMath.movedStart(scene, layer, home + 40))
        assertEquals(layer.startTime, away.movedTo(TimelineMath.movedStart(scene, layer, home)).startTime)
        assertEquals(layer.endTime, away.movedTo(TimelineMath.movedStart(scene, layer, home)).endTime)
    }
}
