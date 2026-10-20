package app.absentmotion.ui.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.SpeedRamp
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withSpeedRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun aNearestEndTargetThatWouldStartBeforeZeroDoesNotHideAValidOne() {
        assertEquals(MoveTarget(0, 30), move(-25f, setOf(29, 30), zoneMs = 40f))
    }

    @Test fun aZeroSnapZoneSnapsOnlyToAnExactEdge() {
        assertEquals(MoveTarget(60, 60), move(2000f, setOf(60), zoneMs = 0f))
        assertEquals(MoveTarget(60, null), move(2001f, setOf(60), zoneMs = 0f))
    }

    @Test fun everyTargetKeepsTheStartOnTheTimelineAndSnapsOnlyToAnEdgeOfTheClip() {
        val targets = setOf(0, 5, 29, 30, 31, 60)
        for (wanted in -2000..2500 step 3) {
            val target = move(wanted.toFloat(), targets)
            assertTrue("$wanted", target.frame >= 0)
            assertTrue("$wanted", target.snap == null || target.snap == target.frame || target.snap == target.frame + 30)
        }
    }

    @Test fun aClipWithAnOddLengthKeepsItsLengthOnEveryFrame() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "500", "endTime" to "1733")))
        val scene = sceneWith(layer.node)
        for (frame in listOf(0, 3, 15, 50)) {
            val moved = layer.movedTo(TimelineMath.movedStart(scene, layer, frame))
            assertEquals(1233, moved.endTime - moved.startTime)
            assertEquals(frame, scene.firstShownFrame(moved.startTime))
        }
    }

    @Test fun aSpeedRampKeepsItsMediaPositionsWhenTheClipMoves() {
        val clip = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "1000", "endTime" to "4999",
            "fillType" to "media", "fillVideo" to "content://v", "inTime" to "500")))
        val scene = sceneWith(clip.withSpeedRamp(SpeedRamp.Hero, ease = null).node)
        val layer = scene.layers.single()
        val moved = layer.movedTo(TimelineMath.movedStart(scene, layer, 40))
        val shift = moved.startTime - layer.startTime
        assertEquals(layer.keyframeTimes().map { it + shift }, moved.keyframeTimes())
        for (offset in listOf(0, 250, 1500, 3999)) {
            assertEquals(VideoTiming(layer).mediaMs(offset.toDouble()), VideoTiming(moved).mediaMs(offset.toDouble()), 1e-6)
        }
    }
}
