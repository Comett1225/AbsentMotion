package app.absentmotion.ui.editor

import app.absentmotion.ai.withClip
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.videoNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTrimGroupTest {
    private val a = shapeNode(id = 1, start = 0, end = 2999)
    private val b = shapeNode(id = 2, start = 500, end = 2499)
    private val c = shapeNode(id = 3, start = 1000, end = 1999)
    private val scene = sceneOf(a, b, c)

    private fun trims(grabbed: Long, others: List<Long>, start: Boolean, wantedMs: Float, from: Scene = scene) =
        TimelineMath.trimmedEdges(from, from.layer(grabbed)!!, others.map { from.layer(it)!! }, start, wantedMs)

    @Test fun theGrabbedEndAndEveryOtherEndMoveByTheSameTime() {
        val result = trims(1, listOf(2, 3), start = false, wantedMs = 2400f)
        assertEquals(listOf(2399, 1899, 1399), result.map { it.layer.endTime })
        assertEquals(listOf(0, 500, 1000), result.map { it.layer.startTime })
        assertTrue(result.none { it.limited })
    }

    @Test fun theGrabbedStartAndEveryOtherStartMoveByTheSameTime() {
        val result = trims(2, listOf(1, 3), start = true, wantedMs = 800f)
        assertEquals(listOf(800, 300, 1300), result.map { it.layer.startTime })
        assertEquals(listOf(2499, 2999, 1999), result.map { it.layer.endTime })
    }

    @Test fun aDragPastTheProjectEndLengthensEveryClipAndTheProject() {
        val result = trims(2, listOf(1, 3), start = false, wantedMs = 3500f)
        assertEquals(listOf(3499, 3999, 2999), result.map { it.layer.endTime })
        val next = listOf(2L, 1L, 3L).zip(result).fold(scene) { s, (id, trim) -> s.withClip(id, trim.layer) }
        assertEquals(3999, next.totalTime)
    }

    @Test fun theGrabbedClipAloneBehavesExactlyAsASingleTrim() {
        for (wanted in listOf(-500f, 0f, 133f, 1490f, 2000f, 2499f, 2999f, 3000f, 5000f)) {
            for (start in listOf(true, false)) {
                val alone = trims(2, emptyList(), start, wanted).single()
                val frame = TimelineMath.nearestFrame(wanted, scene.fps)
                val base = scene.layer(2)!!
                val edge = if (start) frame.coerceIn(0, scene.lastShownFrame(base.endTime)) else (frame - 1).coerceAtLeast(scene.firstShownFrame(base.startTime)) + 1
                assertEquals("$start $wanted", edge, alone.edge)
                assertEquals("$start $wanted", if (start) scene.frameStart(edge) else scene.frameEnd(edge - 1), alone.time)
            }
        }
    }

    @Test fun aClipCannotStartBeforeZeroButTheOthersKeepGoing() {
        val result = trims(2, listOf(1, 3), start = true, wantedMs = 200f)
        assertEquals(listOf(200, 0, 700), result.map { it.layer.startTime })
        val far = trims(2, listOf(1, 3), start = true, wantedMs = -400f)
        assertEquals(listOf(0, 0, 100), far.map { it.layer.startTime })
        assertEquals(0, far[0].edge)
    }

    @Test fun aClipKeepsAtLeastOneFrameWhileTheOthersKeepGoing() {
        val short = trims(1, listOf(2, 3), start = false, wantedMs = 600f)
        assertEquals(599, short[0].layer.endTime)
        assertEquals(scene.frameEnd(scene.firstShownFrame(500)), short[1].layer.endTime)
        assertEquals(scene.frameEnd(scene.firstShownFrame(1000)), short[2].layer.endTime)
        assertTrue(short.all { it.layer.startTime <= it.layer.endTime })
        val late = trims(1, listOf(2, 3), start = true, wantedMs = 2900f)
        assertEquals(2900, late[0].layer.startTime)
        assertEquals(scene.frameStart(scene.lastShownFrame(2499)), late[1].layer.startTime)
        assertTrue(late.all { it.layer.startTime <= it.layer.endTime })
    }

    @Test fun aClipPastItsSourceLengthStopsAloneAndIsFlaggedAsLimited() {
        val long = videoNode(0, 999, id = 1, inTime = 0)
        val short = videoNode(0, 999, id = 2, inTime = 1500)
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to "content://v", "duration" to "3000000")))
        val source = sceneOf(long, short).withMedia(media)
        val result = trims(1, listOf(2), start = false, wantedMs = 2000f, from = source)
        assertEquals(1999, result[0].layer.endTime)
        assertFalse(result[0].limited)
        assertEquals(1500, result[1].layer.endTime)
        assertTrue(result[1].limited)
        val back = trims(1, listOf(2), start = false, wantedMs = 1000f, from = source)
        assertTrue(back.none { it.limited })
        assertEquals(listOf(999, 999), back.map { it.layer.endTime })
    }

    @Test fun returningToTheStartRestoresEveryClipExactly() {
        val result = trims(1, listOf(2, 3), start = false, wantedMs = 3000f)
        assertEquals(listOf(a, b, c), result.map { it.layer.node })
        val starts = trims(2, listOf(1, 3), start = true, wantedMs = 500f)
        assertEquals(listOf(b, a, c), starts.map { it.layer.node })
    }

    @Test fun anEdgeOffTheFrameGridKeepsItsOffsetFromTheGrabbedOne() {
        val odd = sceneOf(shapeNode(id = 1, start = 0, end = 2999), shapeNode(id = 2, start = 517, end = 2516))
        val result = trims(1, listOf(2), start = true, wantedMs = 600f, from = odd)
        assertEquals(600, result[0].layer.startTime)
        assertEquals(1133, result[1].layer.startTime)
    }
}
