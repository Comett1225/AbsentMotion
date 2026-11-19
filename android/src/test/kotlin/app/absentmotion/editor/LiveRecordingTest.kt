package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import app.absentmotion.model.withKeyframeToggled
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withValueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiveRecordingTest {
    private fun sample(frame: Int, value: Float) = RecordedSample(frame, frame.toFloat(), value.toString(), frame - 0.49f, frame + 0.49f)

    @Test fun straightLineNeedsOnlyItsEnds() {
        val samples = (0..20).map { sample(it, it * 2f) }
        assertEquals(listOf(samples.first(), samples.last()), simplifyRecording(samples, 0.5f))
    }

    @Test fun curvedMotionStaysWithinTolerance() {
        val samples = (0..40).map { sample(it, it * it / 80f) }
        val thin = simplifyRecording(samples, 0.25f)
        assertTrue(thin.size in 3 until samples.size)
        for (point in samples) {
            val a = thin.last { it.time <= point.time }
            val b = thin.first { it.time >= point.time }
            val u = if (a.time == b.time) 0f else (point.time - a.time) / (b.time - a.time)
            val expected = a.value.toFloat() + (b.value.toFloat() - a.value.toFloat()) * u
            assertTrue(abs(expected - point.value.toFloat()) <= 0.25f)
        }
    }

    @Test fun rotationWrapRetainsTheDiscontinuity() {
        val samples = listOf(350f, 355f, 0f, 5f).mapIndexed(::sample)
        assertEquals(samples, simplifyRecording(samples, 0.25f))
    }

    @Test fun overwriteOnlyRemovesKeysInsideRecordedSpan() {
        val old = listOf(Keyframe(0f, "old0"), Keyframe(2f, "old2"), Keyframe(4f, "old4"), Keyframe(6f, "old6"))
        val samples = listOf(sample(2, 10f), sample(3, 11f), sample(4, 12f))
        val result = mergeRecording(old, samples, true, 0.5f)
        assertEquals(listOf("old0", "10.0", "12.0", "old6"), result.map { it.v })
    }

    @Test fun mixingRetainsOldKeysBetweenSimplifiedSamples() {
        val old = listOf(Keyframe(2f, "old2"), Keyframe(3f, "old3"), Keyframe(4f, "old4"))
        val samples = listOf(sample(2, 10f), sample(3, 11f), sample(4, 12f))
        assertEquals(listOf("10.0", "old3", "12.0"), mergeRecording(old, samples, false, 0.5f).map { it.v })
    }

    private val keyed = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect">
        <transform><scale><kf t="0" v="1,1" /><kf t="1" v="2,2" /></scale></transform>
        <effect id="absent.bulge"><property name="center" type="vec2"><kf t="0" v="0.5,0.5" /><kf t="1" v="0.5,0.5" /></property></effect></shape></scene>""")

    private fun Scene.edited(frame: Int, element: String, change: (XmlNode, Layer) -> XmlNode): Scene = update(1, frameTime(frame)) { l ->
        fun walk(n: XmlNode): XmlNode = if (n.name == element) change(n, l) else n.copy(children = n.children.map(::walk))
        Layer(walk(l.node))
    }

    @Test fun onlyAnEditAtThePlayheadIsRecordedWhilePlaying() {
        val held = keyed.edited(15, "scale") { n, l -> n.withValueAt(l.t, "3,3", l.snap) }
        assertTrue(LiveRecording(keyed, "drag", true).capture(keyed, held, 15))
        val flipped = keyed.edited(15, "scale") { n, _ -> n.withKeyframes(n.keyframes.map { it.copy(v = "-" + it.v) }) }
        assertFalse(LiveRecording(keyed, null, true).capture(keyed, flipped, 15))
        val onKey = keyed.edited(15, "scale") { n, l -> n.withValueAt(l.t, "3,3", l.snap) }
        val removed = onKey.edited(15, "scale") { n, l -> n.withKeyframeToggled(l.t, l.snap) }
        assertFalse(LiveRecording(onKey, null, true).capture(onKey, removed, 15))
    }

    private fun circle(normalized: Boolean): Int {
        val recording = LiveRecording(keyed, "point", true) { _, _ -> normalized }
        var scene = keyed
        for (frame in 1..24) {
            val a = frame * Math.PI / 12
            val next = scene.edited(frame, "property") { n, l -> n.withValueAt(l.t, "${0.5 + 0.2 * kotlin.math.cos(a)},${0.5 + 0.2 * kotlin.math.sin(a)}", l.snap) }
            assertTrue(recording.capture(scene, next, frame))
            scene = recording.render(next, false)
        }
        return recording.render(scene, true).layer(1)!!.node.children.single { it.name == "effect" }.children.single().keyframes.size
    }

    @Test fun aRecordedEffectPointKeepsItsShapeWhenItIsNotInPixels() {
        assertTrue(circle(normalized = true) > circle(normalized = false) + 5)
    }

    @Test fun aNewLapKeepsHoldingTheLastRecordedValue() {
        val recording = LiveRecording(keyed, "drag", true)
        val held = keyed.edited(20, "scale") { n, l -> n.withValueAt(l.t, "3,3", l.snap) }
        assertTrue(recording.capture(keyed, held, 20))
        val lap = recording.nextLap(recording.render(held, true), 0)
        assertTrue(lap.active)
        val next = lap.hold(recording.render(held, true), 2)
        val layer = next.layer(1)!!.at(next.frameTime(2), 30)
        assertEquals(listOf(3f, 3f), layer.node.child("transform")!!.child("scale")!!.valueAt(layer.t)!!.split(',').map { it.trim().toFloat() })
    }

    @Test fun recordedEditsShareOneUndoAndKeepTheirNote() {
        var scene = Scene.create("before", 400, 400, 30, 0)
        val history = EditHistory({ scene }) { next, _ -> scene = next }
        val key = Any()
        history.edit(key, scene.withTitle("one"), app.absentmotion.R.string.history_record_keyframes)
        history.edit(key, scene.withTitle("two"), app.absentmotion.R.string.history_record_keyframes)
        history.endGesture()
        assertEquals("before", history.undo()?.title)
        assertEquals(app.absentmotion.R.string.history_record_keyframes, history.lastActionNote)
        assertFalse(history.canUndo)
        assertEquals("two", history.redo()?.title)
        assertEquals(app.absentmotion.R.string.history_record_keyframes, history.lastActionNote)
    }
}
