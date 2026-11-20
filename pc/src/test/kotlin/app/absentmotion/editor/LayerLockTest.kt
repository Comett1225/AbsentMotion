package app.absentmotion.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.Shortcut
import app.absentmotion.i18n.txt
import app.absentmotion.library.Importer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.Timeline
import app.absentmotion.ui.editor.tapTarget
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val LAYERS = """<shape id="1" label="Back" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="200,200,0"/></transform><property name="size" type="vec2" value="50,50"/></shape>
<shape id="2" label="Timing" lock="timing" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="350,50,0"/></transform><property name="size" type="vec2" value="20,20"/></shape>
<shape id="3" label="Top" lock="all" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="200,200,0"/></transform><property name="size" type="vec2" value="200,200"/></shape>"""

private const val GROUP = """<embedScene id="5" label="Group" lock="all" startTime="0" endTime="2999"><scene width="400" height="400" fps="30" totalTime="2999"><shape id="6" label="Inner" startTime="0" endTime="2999" fillType="color" s=".rect"/></scene></embedScene>
<embedScene id="7" label="Open group" startTime="0" endTime="2999"><scene width="400" height="400" fps="30" totalTime="2999"><shape id="8" label="Inner locked" lock="all" startTime="0" endTime="2999" fillType="color" s=".rect"/><shape id="9" label="Inner free" startTime="0" endTime="2999" fillType="color" s=".rect"/></scene></embedScene>"""

class LayerLockTest {
    private var app: DeskAppState? = null

    private fun open(name: String, extra: String = ""): EditorState {
        System.setProperty("am.data", File("build/$name").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val desk = DeskAppState().also { app = it }
        val scene = Scene.parse("""<scene title="Locks" width="400" height="400" fps="30" totalTime="2999" bgcolor="#ff000000">$LAYERS$extra</scene>""")
        return runBlocking { desk.openNow(desk.store.create(scene))!! }
    }

    private fun EditorState.lastHint() = historyNotes.lastOrNull()?.text

    @After fun close() {
        MultiSelect.clear()
        app?.engine?.close()
    }

    @Test fun lockRoundTripsThroughSaveLoadCopyAndImport() {
        val state = open("lock-roundtrip")
        state.setLock(1L, "timing")
        state.saver.flush().get()
        val loaded = app!!.store.load(state.projectId)!!
        assertEquals("timing", loaded.layer(1L)!!.node["lock"])
        assertEquals("timing", loaded.layer(2L)!!.node["lock"])
        assertEquals("all", loaded.layer(3L)!!.node["lock"])
        assertEquals("all", Scene.parse(state.scene.toXml()).layer(3L)!!.node["lock"])
        state.selectedId = 2L
        state.duplicateSelected()
        assertEquals("timing", state.scene.layer(state.selectedId!!)!!.node["lock"])
        state.selectedId = 2L
        state.copySelected()
        val before = state.scene.layers.map { it.id }.toSet()
        state.pasteLayer()
        assertEquals("timing", state.scene.layers.single { it.id !in before }.node["lock"])
        val report = Importer(app!!.store).import { state.scene.toXml().byteInputStream() }.single()
        assertTrue(report.sections.toString(), report.sections.isEmpty())
    }

    @Test fun guardRejectsLockedEditsAndKeepsToggles() {
        val state = open("lock-guard", GROUP)
        val top = state.scene.layer(3L)!!
        state.edit { it.update(3L) { l -> l.withTransform("location", 10f, 10f, 0f) } }
        assertEquals(top.node, state.scene.layer(3L)!!.node)
        assertEquals(txt(R.string.layer_locked_hint), state.lastHint())
        assertFalse(state.canUndo)
        state.edit { it.remove(3L) }
        assertTrue(state.scene.layer(3L) != null)
        state.toggleHidden(3L)
        assertTrue(state.scene.layer(3L)!!.hidden)
        state.setLock(3L, "timing")
        assertEquals("timing", state.scene.layer(3L)!!.node["lock"])
        state.edit { it.update(3L) { l -> l.withTransform("location", 10f, 10f, 0f) } }
        assertTrue(state.scene.layer(3L)!!.node != top.node)
        state.edit { it.update(2L) { l -> l.movedTo(500) } }
        assertEquals(0, state.scene.layer(2L)!!.startTime)
        assertEquals(txt(R.string.layer_timing_locked_hint), state.lastHint())
        state.edit { it.update(2L) { l -> l.withTimes(0, 1500) } }
        assertEquals(2999, state.scene.layer(2L)!!.endTime)
        state.edit { it.update(2L) { l -> l.withTransform("location", 50f, 50f, 0f) } }
        assertEquals(50f, state.scene.layer(2L)!!.location[0], 0.01f)
        state.edit { it.update(5L) { l -> l.withLabel("Renamed") } }
        assertEquals("Group", state.scene.layer(5L)!!.label)
        state.enterGroup(5L)
        assertFalse(state.inGroup)
        state.enterGroup(7L)
        assertTrue(state.inGroup)
        state.edit { it.update(8L) { l -> l.withLabel("Renamed") } }
        assertEquals("Inner locked", state.scene.layer(8L)!!.label)
        state.edit { it.update(9L) { l -> l.withLabel("Renamed") } }
        assertEquals("Renamed", state.scene.layer(9L)!!.label)
        state.exitGroup()
        state.setLock(7L, "all")
        state.undo()
        state.enterGroup(7L)
        state.redo()
        assertTrue(state.inGroup)
        state.edit { it.update(9L) { l -> l.withLabel("Again") } }
        assertEquals("Renamed", state.scene.layer(9L)!!.label)
        assertEquals(txt(R.string.layer_locked_hint), state.lastHint())
    }

    @Test fun projectWideChangesKeepWorkingWithLockedLayers() {
        val state = open("lock-project")
        state.changeSettings(800, 600, 60, 0xFF112233.toInt())
        assertEquals(60, state.scene.fps)
        assertEquals(800, state.scene.width)
        state.setElementOption("retime", "loop")
        assertEquals("loop", state.scene.retime)
        state.rename("Locked project")
        assertEquals("Locked project", state.scene.title)
        assertEquals("all", state.scene.layer(3L)!!.node["lock"])
        assertTrue(state.canUndo)
    }

    @Test fun timingLockBlocksSplitNudgeAndTrimButNotSelection() {
        val state = open("lock-timing")
        state.selectedId = 2L
        assertEquals(2L, state.selectedId)
        state.frame = 30
        val layer = state.scene.layer(2L)!!
        state.split()
        assertEquals(layer.node, state.scene.layer(2L)!!.node)
        assertEquals(3, state.scene.layers.size)
        state.selectedId = 2L
        state.trimChosen(start = true)
        assertEquals(0, state.scene.layer(2L)!!.startTime)
        DeskShortcuts.run(state, Shortcut.NudgeLater) {}
        assertEquals(0, state.scene.layer(2L)!!.startTime)
        assertEquals(txt(R.string.layer_timing_locked_hint), state.lastHint())
        state.selectedId = 1L
        DeskShortcuts.run(state, Shortcut.NudgeLater) {}
        assertTrue(state.scene.layer(1L)!!.startTime > 0)
    }

    @Test fun lockedLayersStayOutOfSelection() {
        val state = open("lock-select")
        state.selectedId = 3L
        assertNull(state.selectedId)
        assertEquals(txt(R.string.layer_locked_hint), state.lastHint())
        state.selectAll()
        assertEquals(setOf(1L, 2L), MultiSelect.ids)
        MultiSelect.clear()
        assertTrue(DeskShortcuts.run(state, Shortcut.SelectAll) {})
        assertEquals(setOf(1L, 2L), MultiSelect.ids)
        state.setLock(2L, "all")
        assertEquals(setOf(1L), MultiSelect.ids)
        MultiSelect.clear()
        state.selectedId = 1L
        state.toggleLock(1L)
        assertNull(state.selectedId)
        assertEquals("all", state.scene.layer(1L)!!.node["lock"])
    }

    @Test fun undoAndRedoALockToggle() {
        val state = open("lock-undo")
        state.selectedId = 1L
        state.toggleLock(1L)
        assertEquals("all", state.scene.layer(1L)!!.node["lock"])
        assertNull(state.selectedId)
        state.undo()
        assertNull(state.scene.layer(1L)!!.node["lock"])
        assertEquals(txt(R.string.history_undid, txt(R.string.history_lock)), state.lastHint())
        state.selectedId = 1L
        state.redo()
        assertEquals("all", state.scene.layer(1L)!!.node["lock"])
        assertNull(state.selectedId)
        state.toggleLock(1L)
        assertNull(state.scene.layer(1L)!!.node["lock"])
    }

    @Test fun canvasTapFallsThroughALockedLayer() {
        val state = open("lock-tap")
        runBlocking {
            assertEquals(3L, state.engine.hitTest(200f, 200f, 0))
            assertEquals(1L, state.tapTarget(200f, 200f))
            assertEquals(3L, state.engine.hitTest(120f, 120f, 0))
            assertEquals(0L, state.tapTarget(120f, 120f))
        }
        state.setLock(3L, null)
        runBlocking { assertEquals(3L, state.tapTarget(200f, 200f)) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun desktopTimelineRespectsLocks() {
        val state = open("lock-timeline")
        runSkikoComposeUiTest(size = Size(1280f, 400f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            waitForIdle()
            runOnIdle { state.timelineZoom = 100f }
            onRoot().performMouseInput { moveTo(Offset(230f, 57f)); press(); release() }
            runOnIdle { assertNull(state.selectedId); assertEquals(txt(R.string.layer_locked_hint), state.lastHint()) }
            onRoot().performMouseInput { moveTo(Offset(230f, 57f)); press(); moveTo(Offset(300f, 57f)); release() }
            runOnIdle { assertEquals(0, state.scene.layer(3L)!!.startTime); assertNull(state.selectedId) }
            onRoot().performMouseInput { moveTo(Offset(250f, 57f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
            assertTrue(onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
            onNodeWithText("Unlock").performClick()
            runOnIdle {
                assertFalse(state.scene.layer(3L)!!.locked)
                state.setLock(3L, "all")
            }
            onRoot().performMouseInput { moveTo(Offset(230f, 87f)); press(); moveTo(Offset(320f, 87f)); release() }
            runOnIdle {
                assertEquals(2L, state.selectedId)
                assertEquals(0, state.scene.layer(2L)!!.startTime)
                assertEquals(txt(R.string.layer_timing_locked_hint), state.lastHint())
                state.historyNotes.clear()
            }
            onRoot().performMouseInput { moveTo(Offset(478f, 87f)); press(); moveTo(Offset(420f, 87f)); release() }
            runOnIdle { assertEquals(2999, state.scene.layer(2L)!!.endTime); assertEquals(txt(R.string.layer_timing_locked_hint), state.lastHint()) }
            onRoot().performMouseInput { moveTo(Offset(600f, 50f)); press(); moveTo(Offset(200f, 125f)); release() }
            runOnIdle { assertEquals(setOf(1L, 2L), MultiSelect.ids); MultiSelect.clear(); state.selectedId = 2L }
            waitForIdle()
            onRoot().performMouseInput { moveTo(Offset(47f, 117f)); press(); release() }
            runOnIdle { assertEquals("all", state.scene.layer(1L)!!.node["lock"]) }
            onRoot().performMouseInput { moveTo(Offset(47f, 117f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
            onNodeWithText("Lock timing only").performClick()
            runOnIdle { assertEquals("timing", state.scene.layer(1L)!!.node["lock"]) }
            onRoot().performMouseInput { moveTo(Offset(20f, 57f)); press(); release() }
            runOnIdle { assertTrue(state.scene.layer(3L)!!.hidden) }
        }
    }
}
