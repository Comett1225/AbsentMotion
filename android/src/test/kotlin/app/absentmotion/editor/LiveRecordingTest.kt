package app.absentmotion.editor

import app.absentmotion.model.Keyframe
import app.absentmotion.model.Scene
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
