package app.absentmotion.model

import app.absentmotion.ui.editor.TimelineMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeWindowTest {
    private val rates = listOf(24, 25, 30, 60)

    private fun scene(fps: Int, vararg layers: Layer) = Scene.create("t", 100, 100, fps, 0).withLayers(layers.toList())

    private fun keyed(start: Int, end: Int, vararg times: Float) = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "$start", "endTime" to "$end"), listOf(
        XmlNode("transform", children = listOf(XmlNode("opacity").withKeyframes(times.map { Keyframe(it, "1") }))),
    )))

    @Test fun keysOnTheFirstAndLastFrameOfAClipAreFoundOnThoseFrames() {
        for (fps in rates) {
            val s = scene(fps)
            for (first in 0..60) for (count in listOf(1, 2, 5)) {
                val last = first + count - 1
                val layer = keyed(s.frameStart(first), s.frameEnd(last), 0f, 1f)
                val where = "fps $fps clip $first..$last"
                assertTrue(where, layer.at(s.frameTime(first), fps).onKeyframe(PropRef.Opacity))
                assertTrue(where, layer.at(s.frameTime(last), fps).onKeyframe(PropRef.Opacity))
                if (count > 2) assertFalse(where, layer.at(s.frameTime(first + 1), fps).onKeyframe(PropRef.Opacity))
                val edited = layer.at(s.frameTime(first), fps).withValue(PropRef.Opacity, "0.5")
                assertEquals(where, listOf(0f to "0.5", 1f to "1"), edited.keyframes(PropRef.Opacity).map { it.t to it.v })
                assertEquals(where, listOf(1f), layer.at(s.frameTime(first), fps).withKeyframeToggled(PropRef.Opacity, "1").keyframes(PropRef.Opacity).map { it.t })
            }
        }
    }

    @Test fun everyKeyBelongsToExactlyTheFrameItsDiamondIsDrawnOn() {
        for (fps in rates) {
            val s = scene(fps)
            val start = s.frameStart(3)
            val end = s.frameEnd(40)
            for (ms in start..end) {
                val layer = keyed(start, end, (ms - start).toFloat() / (end - start))
                val hits = (0..45).filter { layer.at(s.frameTime(it), fps).onKeyframe(PropRef.Opacity) }
                assertEquals("fps $fps key at $ms", listOf(s.frameAt(layer.keyframeTimes().single())), hits)
            }
        }
    }

    @Test fun aSpeedKeyAtTheClipStartIsFoundOnTheStartFrame() {
        for (fps in rates) {
            val s = scene(fps)
            for (first in 0..40) {
                val keyed = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "${s.frameStart(first)}", "endTime" to "${s.frameEnd(first + 30)}",
                    "fillType" to "media", "fillVideo" to "v", "inTime" to "0"), listOf(XmlNode("speedMap").withKeyframes(listOf(Keyframe(0f, "1"), Keyframe(5e8f, "2"))))))
                assertEquals("fps $fps first $first", 1, keyed.withSpeedKeyframeToggled(s.frameTime(first), fps).node.child("speedMap")!!.keyframes.size)
                val window = keyed.atVideoTime(s.frameTime(first), fps)
                assertEquals(0, keyed.node.child("speedMap")!!.keyframeAt(window.snap))
            }
        }
    }

    @Test fun aKeyOnAClipsLastFrameIsASnapTargetOnThatFrame() {
        val s = scene(30, keyed(0, 999, 0f, 0.5f, 1f))
        val targets = TimelineMath.snapTargets(s, 99, 0)
        assertTrue(29 in targets)
        assertTrue(s.frameAt(s.layers.single().keyframeTimes()[1]) in targets)
        assertEquals(setOf(0, 15, 29, 30), targets)
    }
}
