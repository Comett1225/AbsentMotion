package app.absentmotion.editor

import app.absentmotion.desk.DeskAppState
import app.absentmotion.library.AppSettings
import app.absentmotion.model.CameraFov
import app.absentmotion.model.PropRef
import app.absentmotion.model.withCameraFov
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
}
