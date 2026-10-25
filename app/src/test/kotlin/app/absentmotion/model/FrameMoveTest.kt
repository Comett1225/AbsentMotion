package app.absentmotion.model

import app.absentmotion.editor.withBlockAt
import app.absentmotion.editor.withClipEdgesAt
import app.absentmotion.ui.editor.TimelineMath
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class FrameMoveTest {
    private val rates = listOf("24", "25", "30", "60", "29.97")

    private fun scene(fps: String, vararg clips: Layer) =
        Scene.parse("""<scene width="100" height="100" fps="$fps"/>""").let { Scene(it.node.copy(children = clips.map { c -> c.node })) }

    private fun clip(start: Int, end: Int, id: Long = 1) = Layer(XmlNode("shape", linkedMapOf("id" to "$id", "startTime" to "$start", "endTime" to "$end")))

    private fun Scene.drawn(l: Layer): List<Int> = (0..frameAt(maxOf(l.endTime, 0)) + 1).filter { frameStart(it) in l.startTime..l.endTime }

    private fun Scene.clips(): List<Layer> = buildList {
        for (first in 0..70) for (count in listOf(1, 2, 3, 7, 30)) add(clip(frameStart(first), frameEnd(first + count - 1)))
        for (k in 0..70) for (count in 1..4) {
            val start = (k * 1000 / 29.97).roundToInt()
            add(clip(start, (((k + count) * 1000) / 29.97).roundToInt() - 1))
            add(clip(start + 7, start + 7 + count * 1000 / fps - 3))
        }
    }

    private fun Scene.assertSameFrames(before: Layer, after: Layer, where: String) {
        val was = drawn(before)
        val now = drawn(after)
        if (was.isEmpty()) return
        assertEquals("$where: $before -> $after", was.size, now.size)
        assertEquals(where, (now.first()..now.last()).toList(), now)
    }

    @Test fun movingAClipOnTheTimelineNeverGrowsOrHidesIt() {
        for (fps in rates) {
            val s = scene(fps)
            for (layer in s.clips()) for (frame in 0..90) {
                val start = TimelineMath.movedStart(s, layer, frame)
                val moved = s.movedClip(layer, start)
                assertEquals(start, moved.startTime)
                s.assertSameFrames(layer, moved, "fps $fps frame $frame")
            }
        }
    }

    @Test fun aOneFrameClipDraggedToFrameZeroStaysOneFrame() {
        val s = scene("30")
        val moved = s.movedClip(clip(66, 99), 0)
        assertEquals(0 to 32, moved.startTime to moved.endTime)
        assertEquals(listOf(0), s.drawn(moved))
    }

    @Test fun aClipThatFitsKeepsItsExactLengthAndReturnsHomeExactly() {
        val s = scene("30")
        val layer = clip(500, 1733)
        for (frame in listOf(0, 3, 15)) assertEquals(1233, s.movedClip(layer, s.frameStart(frame)).let { it.endTime - it.startTime })
        for (fps in rates) {
            val r = scene(fps)
            for (original in r.clips()) {
                val home = r.movedClip(original, TimelineMath.movedStart(r, original, r.firstShownFrame(original.startTime)))
                assertEquals(original.startTime to original.endTime, home.startTime to home.endTime)
            }
        }
    }

    @Test fun aligningEndsKeepsEveryClipVisibleAndEndsThemTogether() {
        for (fps in rates) {
            val s = scene(fps)
            for (layer in s.clips()) for (last in listOf(4999, 5000, 5016, 5033, 5040)) {
                if (last - (layer.endTime - layer.startTime) < 0) continue
                val moved = s.movedClip(layer, last - (layer.endTime - layer.startTime), keepEnd = true)
                assertEquals(last, moved.endTime)
                s.assertSameFrames(layer, moved, "fps $fps end $last")
            }
        }
    }

    @Test fun movingClipEdgesToThePlayheadKeepsTheirFrames() {
        for (fps in rates) {
            val base = scene(fps)
            for (layer in base.clips().filterIndexed { i, _ -> i % 7 == 0 }) {
                val s = scene(fps, layer)
                for (frame in 40..60) {
                    val starts = s.withClipEdgesAt(listOf(1), frame, end = false, extend = false).layer(1)!!
                    s.assertSameFrames(layer, starts, "fps $fps start at $frame")
                    assertEquals(frame, s.drawn(starts).first())
                    val ends = s.withClipEdgesAt(listOf(1), frame, end = true, extend = false).layer(1)!!
                    s.assertSameFrames(layer, ends, "fps $fps end at $frame")
                    assertEquals(frame, s.drawn(ends).last())
                }
            }
        }
    }

    @Test fun severalClipsMoveToThePlayheadAsABlockFirstAndLineUpSecond() {
        val s = scene("30", clip(10_000, 14_999, 1), clip(15_000, 19_999, 2), clip(20_000, 24_999, 3), clip(0, 999, 4))
        val ids = listOf(1L, 2L, 3L)
        val block = s.withBlockAt(ids, 0, end = false)
        assertEquals(listOf(0 to 4_999, 5_000 to 9_999, 10_000 to 14_999), ids.map { block.layer(it)!!.let { l -> l.startTime to l.endTime } })
        assertEquals(s.layer(4), block.layer(4))
        val lined = block.withClipEdgesAt(ids, 0, end = false, extend = false)
        assertEquals(List(3) { 0 to 4_999 }, ids.map { lined.layer(it)!!.let { l -> l.startTime to l.endTime } })
        val ends = s.withBlockAt(ids, 900, end = true)
        assertEquals(30_000, ends.layer(3)!!.endTime)
        assertEquals(listOf(5_001, 5_001, 5_001), ids.map { ends.layer(it)!!.startTime - s.layer(it)!!.startTime })
        assertEquals(900, ends.drawn(ends.layer(3)!!).last())
        val allEnds = ends.withClipEdgesAt(ids, 900, end = true, extend = false)
        assertEquals(List(3) { 30_000 }, ids.map { allEnds.layer(it)!!.endTime })
        for (fps in rates) {
            val r = scene(fps, *scene(fps).clips().filterIndexed { i, _ -> i % 37 == 0 }.mapIndexed { i, c -> Layer(c.node.with("id", "${i + 1}")) }.toTypedArray())
            val all = r.layers.map { it.id }
            val past = r.frameAt(r.layers.maxOf { it.endTime }) + 1
            for ((frame, end) in listOf(0 to false, 45 to false, 400 to false, past to true, past + 40 to true)) {
                val moved = r.withBlockAt(all, frame, end)
                for (layer in r.layers) r.assertSameFrames(layer, moved.layer(layer.id)!!, "fps $fps block at $frame end $end")
            }
        }
    }

    @Test fun pastingAtThePlayheadKeepsEveryCopysFrameCount() {
        for (fps in rates) {
            val s = scene(fps)
            val copied = s.clips().filterIndexed { i, _ -> i % 11 == 0 }
            for (frame in listOf(0, 1, 2, 17, 45)) for (source in copied) {
                val copy = copyLayers(listOf(source), s.frameStart(frame), move = s::movedClip).single()
                s.assertSameFrames(source, copy, "fps $fps paste at $frame")
            }
        }
    }
}
