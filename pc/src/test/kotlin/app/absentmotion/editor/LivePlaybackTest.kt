package app.absentmotion.editor

import app.absentmotion.desk.DeskAppState
import app.absentmotion.library.AppSettings
import app.absentmotion.model.CameraFov
import app.absentmotion.model.PropRef
import app.absentmotion.model.withCameraFov
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.previewFrameOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LivePlaybackTest {
    @Test fun settingsRoundTrip() {
        val dir = File("build/live-playback-settings").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val app = DeskAppState()
        assertEquals(0, app.settings.countIn)
        assertEquals(4, app.settings.playbackSpeed)
        assertFalse(app.settings.overwriteRecording)
        app.settings.countIn = 3
        app.settings.playbackSpeed = 1
        app.settings.overwriteRecording = true
        val restored = AppSettings(app.context)
        assertEquals(3, restored.countIn)
        assertEquals(1, restored.playbackSpeed)
        assertTrue(restored.overwriteRecording)
        val scene = Scene.create("Countdown", 400, 400, 30, 0)
        val id = app.store.create(scene)
        val state = runBlocking { app.openNow(id)!! }
        state.togglePlayback()
        assertFalse(state.playing)
        assertEquals(3, state.countInRemaining)
        state.countInStep()
        state.countInStep()
        assertEquals(1, state.countInRemaining)
        state.countInStep()
        assertTrue(state.playing)
        state.togglePlayback()
        assertFalse(state.playing)
        state.togglePlayback()
        state.togglePlayback()
        assertEquals(0, state.countInRemaining)
        assertFalse(state.playing)
    }

    @Test fun speedChangesPreviewTimeStep() {
        assertEquals(30, previewFrameOffset(1_000_000_000L, 30, 4))
        assertEquals(15, previewFrameOffset(1_000_000_000L, 30, 2))
        assertEquals(7, previewFrameOffset(1_000_000_000L, 30, 1))
    }

    @Test fun dragRecordingUsesOneUndoAndReplacesOnlyItsSpan() {
        val dir = File("build/live-playback-record").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val app = DeskAppState()
        val scene = Scene.parse("""<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="Shape" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location><kf t="0.000000" v="0,0,0"/><kf t="0.166667" v="100,0,0"/><kf t="0.500000" v="200,0,0"/><kf t="1.000000" v="300,0,0"/></location></transform></shape></scene>""")
        val id = app.store.create(scene)
        val state = runBlocking { app.openNow(id)!! }
        state.selectedId = 1L
        state.settings.overwriteRecording = true
        state.playing = true
        state.frame = 2
        state.editSelected("drag") { it.withTransform("location", 10f, 0f, 0f) }
        state.frame = 10
        state.editSelected("drag") { it.withTransform("location", 20f, 0f, 0f) }
        state.endGesture()
        state.playing = false
        val keys = state.scene.layer(1L)!!.keyframes(PropRef.Location)
        assertTrue(keys.any { it.v == "0,0,0" })
        assertFalse(keys.any { it.v == "100,0,0" })
        assertTrue(keys.any { it.v == "200,0,0" })
        assertTrue(keys.any { it.v == "300,0,0" })
        state.undo()
        assertEquals(scene.layer(1L)!!.keyframes(PropRef.Location), state.scene.layer(1L)!!.keyframes(PropRef.Location))
        assertFalse(state.canUndo)
    }

    @Test fun dragWhilePlayingStartsKeyframesOnAPropertyWithoutAny() {
        val state = opened("unkeyed", """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="Shape" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location value="0,0,0"/><rotation value="0"/></transform></shape></scene>""")
        state.selectedId = 1L
        state.playing = true
        state.frame = 2
        state.editSelected("drag") { it.withTransform("location", 10f, 0f, 0f) }
        state.frame = 10
        state.editSelected("drag") { it.withTransform("location", 50f, 0f, 0f) }
        state.frame = 20
        state.editSelected("drag") { it.withTransform("location", 90f, 0f, 0f) }
        state.endGesture()
        state.playing = false
        val keys = state.scene.layer(1L)!!.keyframes(PropRef.Location)
        assertTrue(keys.size >= 2)
        val xs = keys.map { it.v.substringBefore(',').toFloat() }
        assertEquals(10f, xs.first(), 0.01f)
        assertEquals(90f, xs.last(), 0.01f)
        assertTrue(state.scene.layer(1L)!!.keyframes(PropRef.Rotation).isEmpty())
        state.undo()
        assertTrue(state.scene.layer(1L)!!.keyframes(PropRef.Location).isEmpty())
        assertFalse(state.canUndo)
    }

    @Test fun aTapWhilePlayingOnAPropertyWithoutKeyframesStaysStatic() {
        val state = opened("unkeyedtap", """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="Shape" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location value="0,0,0"/></transform></shape></scene>""")
        state.selectedId = 1L
        state.playing = true
        state.frame = 5
        state.editSelected { it.withTransform("location", 30f, 0f, 0f) }
        state.playing = false
        assertTrue(state.scene.layer(1L)!!.keyframes(PropRef.Location).isEmpty())
    }

    private fun opened(name: String, xml: String): EditorState {
        val dir = File("build/live-playback-$name").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(xml))
        return runBlocking { app.openNow(id)!! }
    }

    @Test fun pivotRecordingKeysThePivotAndKeepsTheChildInPlace() {
        val state = opened("pivot", """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="A" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location value="200,200,0"/><pivot><kf t="0.000000" v="0,0,0"/><kf t="1.000000" v="0,0,0"/></pivot></transform></shape><shape id="2" label="B" parent="1" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location value="30,10,0"/></transform></shape></scene>""")
        state.selectedId = 1L
        state.playing = true
        state.frame = 2
        state.setPivot(10f, 0f, "pivot")
        state.frame = 12
        state.setPivot(40f, 0f, "pivot")
        state.endGesture()
        state.playing = false
        val pivot = state.scene.layer(1L)!!.keyframes(PropRef.Pivot)
        assertTrue(pivot.any { it.v.startsWith("40") || it.v.startsWith("39.99") })
        assertTrue(state.scene.layer(1L)!!.keyframes(PropRef.Location).size > 1)
        assertTrue(state.scene.layer(2L)!!.keyframes(PropRef.Location).size > 1)
        state.undo()
        assertEquals(2, state.scene.layer(1L)!!.keyframes(PropRef.Pivot).size)
        assertFalse(state.canUndo)
    }

    @Test fun cameraFieldOfViewIsRecordedInOneUndo() {
        val state = opened("fov", """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><camera id="1" label="Camera" startTime="0" endTime="1999"><fov><kf t="0.000000" v="60"/><kf t="1.000000" v="60"/></fov></camera></scene>""")
        state.selectedId = 1L
        state.playing = true
        state.frame = 2
        state.editSelected("fov") { it.withCameraFov(70f) }
        state.frame = 12
        state.editSelected("fov") { it.withCameraFov(90f) }
        state.endGesture()
        state.playing = false
        assertTrue(state.scene.layer(1L)!!.keyframes(CameraFov).any { it.v.toFloat() == 90f })
        state.undo()
        assertEquals(2, state.scene.layer(1L)!!.keyframes(CameraFov).size)
        assertFalse(state.canUndo)
    }

    private val moving = """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="Cube" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location><kf t="0.000000" v="200,200,0"/><kf t="1.000000" v="200,200,0"/></location></transform></shape></scene>"""

    private fun EditorState.locationKeys(): List<Keyframe> = scene.layer(1L)!!.keyframes(PropRef.Location)

    private fun EditorState.fingerMove(dx: Float, dy: Float) =
        editSelected("preview-gesture") { layer -> scene.withWorldLocation(layer, sampleMs) { x, y -> x + dx to y + dy } }

    private fun EditorState.dragUpWiggling(frames: IntRange) {
        for (f in frames) { frame = f; fingerMove(if (f % 2 == 0) 12f else -12f, -6f) }
    }

    private fun EditorState.dragRightWiggling(frames: IntRange) {
        for (f in frames) { frame = f; fingerMove(6f, if (f % 2 == 0) 12f else -12f) }
    }

    private fun EditorState.undoSteps(): Int {
        var steps = 0
        while (canUndo) { undo(); steps++ }
        return steps
    }

    @Test fun eachFingerActionWhilePlayingIsOneUndoStep() {
        val state = opened("actions", moving)
        state.selectedId = 1L
        state.playing = true
        val original = state.locationKeys()
        state.dragUpWiggling(1..12)
        state.endGesture()
        val first = state.locationKeys()
        assertTrue(first.size > 3)
        state.dragRightWiggling(13..26)
        state.endGesture()
        val second = state.locationKeys()
        assertTrue(second != first)
        state.undo()
        assertTrue(state.playing)
        assertEquals(first, state.locationKeys())
        state.undo()
        assertEquals(original, state.locationKeys())
        assertFalse(state.canUndo)
        state.redo()
        assertEquals(first, state.locationKeys())
        state.redo()
        assertEquals(second, state.locationKeys())
        assertFalse(state.canRedo)
        state.playing = false
        state.undo()
        assertEquals(first, state.locationKeys())
        state.undo()
        assertEquals(original, state.locationKeys())
        assertFalse(state.canUndo)
    }

    @Test fun undoWhileTheFingerIsStillDownRemovesTheWholeCurrentAction() {
        val state = opened("finger-down", moving)
        state.selectedId = 1L
        state.playing = true
        state.dragUpWiggling(1..12)
        state.endGesture()
        val first = state.locationKeys()
        state.dragRightWiggling(13..26)
        state.undo()
        assertEquals(first, state.locationKeys())
        state.frame = 27
        state.frame = 28
        assertEquals(first, state.locationKeys())
        state.endGesture()
        assertEquals(first, state.locationKeys())
        state.playing = false
        assertEquals(1, state.undoSteps())
    }

    @Test fun loopingInTheMiddleOfOneDragStaysOneUndoStep() {
        val state = opened("loop", moving)
        state.selectedId = 1L
        state.settings.loopingPlay = true
        state.playing = true
        val original = state.locationKeys()
        state.dragUpWiggling(1..10)
        state.endGesture()
        val first = state.locationKeys()
        state.dragRightWiggling(48..state.scene.lastFrame)
        state.frame = 0
        state.dragRightWiggling(0..8)
        state.endGesture()
        val looped = state.locationKeys()
        assertTrue(looped.any { it.t > 0.75f && it.t < 1f })
        assertTrue(looped.filter { it.t < 0.2f } != first.filter { it.t < 0.2f })
        state.undo()
        assertEquals(first, state.locationKeys())
        state.undo()
        assertEquals(original, state.locationKeys())
        assertFalse(state.canUndo)
        state.redo()
        state.redo()
        assertEquals(looped, state.locationKeys())
    }

    @Test fun pivotDragAcrossALoopIsOneUndoStepAndTapsStaySeparate() {
        val state = opened("pivot-loop", """<scene title="Live" width="400" height="400" fps="30" totalTime="1999" bgcolor="#ff000000"><shape id="1" label="A" startTime="0" endTime="1999" fillType="color" s=".rect"><transform><location value="200,200,0"/><pivot><kf t="0.000000" v="0,0,0"/><kf t="1.000000" v="0,0,0"/></pivot><rotation><kf t="0.000000" v="0"/><kf t="1.000000" v="0"/></rotation></transform></shape></scene>""")
        state.selectedId = 1L
        state.playing = true
        for (f in 50..state.scene.lastFrame) { state.frame = f; state.setPivot(f - 40f, 0f, "pivot") }
        state.frame = 0
        for (f in 0..6) { state.frame = f; state.setPivot(30f + f, 5f, "pivot") }
        state.endGesture()
        state.frame = 10
        state.editSelected { it.withRotation(30f) }
        state.frame = 14
        state.editSelected { it.withRotation(60f) }
        state.playing = false
        assertEquals(3, state.undoSteps())
        assertEquals(2, state.scene.layer(1L)!!.keyframes(PropRef.Pivot).size)
    }
}
