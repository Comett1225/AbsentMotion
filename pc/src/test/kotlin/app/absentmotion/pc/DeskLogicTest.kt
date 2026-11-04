package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskTyping
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.copySelected
import app.absentmotion.editor.renameLayer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.Timeline
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.home.DeskModal
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.io.File

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DeskLogicTest {
    private val commandMask = if (DeskOs.mac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
    private val commandKey = if (DeskOs.mac) Key.MetaLeft else Key.CtrlLeft

    private fun awt(id: Int, vk: Int, mods: Int) = KeyEvent(
        androidx.compose.ui.input.key.Key(vk), if (id == java.awt.event.KeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp,
        isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0, isMetaPressed = mods and InputEvent.META_DOWN_MASK != 0,
        isAltPressed = mods and InputEvent.ALT_DOWN_MASK != 0, isShiftPressed = mods and InputEvent.SHIFT_DOWN_MASK != 0,
    )

    private class Session(val app: DeskAppState, val state: EditorState)

    private fun session(name: String): Session {
        System.setProperty("am.data", File("build/logic-$name-data").also { it.deleteRecursively() }.absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        return Session(app, runBlocking { app.openNow(id)!! })
    }

    private fun Session.press(vk: Int, mods: Int = 0, release: Boolean = true): Boolean {
        val handled = DeskKeys.handle(app, awt(java.awt.event.KeyEvent.KEY_PRESSED, vk, mods))
        if (release) DeskKeys.handle(app, awt(java.awt.event.KeyEvent.KEY_RELEASED, vk, mods))
        return handled
    }

    private fun Session.hold(vk: Int, times: Int, mods: Int = 0) {
        repeat(times) { DeskKeys.handle(app, awt(java.awt.event.KeyEvent.KEY_PRESSED, vk, mods)) }
        DeskKeys.handle(app, awt(java.awt.event.KeyEvent.KEY_RELEASED, vk, mods))
    }

    private fun Session.close() {
        MultiSelect.clear()
        DeskKeys.releaseAll()
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun commandKeyIgnoresAltGrOnWindows() {
        assertTrue(DeskKeys.commandKey(mac = false, windows = false, ctrl = true, meta = false, alt = false))
        assertTrue(DeskKeys.commandKey(mac = false, windows = true, ctrl = true, meta = false, alt = false))
        assertFalse(DeskKeys.commandKey(mac = false, windows = true, ctrl = true, meta = false, alt = true))
        assertTrue(DeskKeys.commandKey(mac = false, windows = false, ctrl = true, meta = false, alt = true))
        assertTrue(DeskKeys.commandKey(mac = true, windows = false, ctrl = false, meta = true, alt = true))
        assertFalse(DeskKeys.commandKey(mac = true, windows = false, ctrl = true, meta = false, alt = false))
        assertTrue(DeskKeys.altGr(windows = true, ctrl = true, alt = true))
        assertFalse(DeskKeys.altGr(windows = false, ctrl = true, alt = true))
        assertFalse(DeskKeys.altGr(windows = true, ctrl = true, alt = false))
    }

    @Test fun linuxShowsTheFileThroughTheFileManager() {
        val command = DeskOs.showItemsCommand(File("/tmp/a b,c.mp4"))
        assertEquals("org.freedesktop.FileManager1.ShowItems", command[command.size - 3])
        assertTrue(command[command.size - 2].startsWith("array:string:file:///") && command[command.size - 2].endsWith("/tmp/a%20b%2Cc.mp4"))
    }

    @Test fun heldKeysActOnce() {
        val s = session("repeat")
        try {
            val vk = java.awt.event.KeyEvent.VK_SPACE
            s.hold(vk, 5)
            assertTrue(s.state.playing)
            s.hold(vk, 3)
            assertFalse(s.state.playing)
            s.state.selectedId = s.state.scene.layers.last().id
            val layers = s.state.scene.layers.size
            s.hold(java.awt.event.KeyEvent.VK_D, 4, commandMask)
            assertEquals(layers + 1, s.state.scene.layers.size)
            s.state.copySelected()
            val before = s.state.scene.layers.size
            s.hold(java.awt.event.KeyEvent.VK_V, 4, commandMask)
            assertEquals(before + 1, s.state.scene.layers.size)
            s.state.selectedId = null
            s.state.frame = 0
            s.hold(java.awt.event.KeyEvent.VK_RIGHT, 4)
            assertEquals(4, s.state.frame)
            s.press(java.awt.event.KeyEvent.VK_SPACE, release = false)
            assertTrue(s.state.playing)
            DeskKeys.handle(s.app, awt(java.awt.event.KeyEvent.KEY_PRESSED, vk, 0))
            assertTrue(s.state.playing)
            DeskKeys.handle(s.app, awt(java.awt.event.KeyEvent.KEY_RELEASED, vk, 0))
            s.press(vk)
            assertFalse(s.state.playing)
        } finally { s.close() }
    }

    @Test fun modalsBlockEditorShortcuts() {
        val s = session("modal")
        try {
            val space = java.awt.event.KeyEvent.VK_SPACE
            val layer = s.state.scene.layers.last()
            s.state.selectedId = layer.id
            DeskModals.open++
            try {
                assertFalse(s.press(space))
                assertFalse(s.press(java.awt.event.KeyEvent.VK_DELETE))
                assertFalse(s.press(java.awt.event.KeyEvent.VK_B, commandMask))
                assertFalse(s.state.playing)
                assertTrue(s.state.scene.layer(layer.id) != null)
            } finally { DeskModals.open-- }
            s.state.exportOpen = true
            assertFalse(s.press(space))
            s.state.exportOpen = false
            s.state.presetsOpen = true
            assertFalse(s.press(java.awt.event.KeyEvent.VK_DELETE))
            assertTrue(s.state.scene.layer(layer.id) != null)
            s.state.presetsOpen = false
            assertTrue(s.press(space))
            assertTrue(s.state.playing)
            s.state.playing = false
            assertTrue(s.press(java.awt.event.KeyEvent.VK_DELETE))
            assertNull(s.state.scene.layer(layer.id))
        } finally { s.close() }
    }

    @Test fun closeShortcutClosesTheEditor() {
        val s = session("close")
        try {
            assertTrue(s.press(java.awt.event.KeyEvent.VK_W, commandMask))
            val end = System.nanoTime() + 20_000_000_000L
            while (s.app.editor != null && System.nanoTime() < end) Thread.sleep(20)
            assertNull(s.app.editor)
        } finally { s.close() }
    }

    @Test fun shutdownSavesTheLastEdit() {
        val s = session("shutdown")
        try {
            val layer = s.state.scene.layers.last()
            val id = s.state.projectId
            s.state.renameLayer(layer.id, "Saved on quit")
            runBlocking { s.app.shutdown() }
            assertNull(s.app.editor)
            assertEquals("Saved on quit", Scene.parse(s.app.store.text(id)!!).layer(layer.id)!!.label)
        } finally { s.close() }
    }

    @Test fun shortcutsStayOutOfTextFields() {
        val s = session("text")
        try {
            val layer = s.state.scene.layers.last()
            s.state.selectedId = layer.id
            runSkikoComposeUiTest(size = Size(1100f, 700f)) {
                val root = FocusRequester()
                setContent {
                    var legacy by remember { mutableStateOf("") }
                    var value by remember { mutableStateOf(TextFieldValue()) }
                    val fieldState = rememberTextFieldState()
                    DeskTyping.Track {
                        CompositionLocalProvider(LocalWide provides true) {
                            Column(Modifier.fillMaxSize().background(Am.TimelineBg).focusRequester(root).onKeyEvent { DeskKeys.handle(s.app, it) }.focusable()) {
                                BasicTextField(legacy, { legacy = it }, Modifier.testTag("legacy").fillMaxWidth().height(30.dp))
                                BasicTextField(value, { value = it }, Modifier.testTag("value").fillMaxWidth().height(30.dp))
                                BasicTextField(fieldState, Modifier.testTag("state").fillMaxWidth().height(30.dp))
                                OutlinedTextField("", {}, Modifier.testTag("material").fillMaxWidth())
                                Box(Modifier.weight(1f)) { Timeline(s.state, Modifier.fillMaxSize()) }
                            }
                        }
                    }
                    LaunchedEffect(Unit) { root.requestFocus() }
                }
                waitForIdle()
                runOnIdle { s.state.timelineZoom = 500f; DeskKeys.releaseAll() }
                val zoom = s.state.timelineZoom
                val start = s.state.scene.firstShownFrame(s.state.scene.layer(layer.id)!!.startTime)
                assertEquals(0, DeskTyping.active)
                for (tag in listOf("legacy", "value", "state", "material")) {
                    onNodeWithTag(tag).performClick()
                    waitForIdle()
                    assertTrue("$tag has the text input session", DeskTyping.active > 0)
                    onRoot().performKeyInput {
                        pressKey(Key.Spacebar); pressKey(Key.Minus); pressKey(Key.Equals); pressKey(Key.Delete); pressKey(Key.Backspace); pressKey(Key.DirectionRight)
                        keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft)
                        keyDown(Key.AltLeft); pressKey(Key.DirectionRight); keyUp(Key.AltLeft)
                    }
                    waitForIdle()
                    runOnIdle {
                        assertFalse("$tag: playback must not start", s.state.playing)
                        assertEquals("$tag: zoom must not change", zoom, s.state.timelineZoom, 0.001f)
                        assertEquals("$tag: frame must not move", 0, s.state.frame)
                        assertTrue("$tag: layer must stay", s.state.scene.layer(layer.id) != null)
                        assertEquals("$tag: layer must not nudge", start, s.state.scene.firstShownFrame(s.state.scene.layer(layer.id)!!.startTime))
                        root.requestFocus()
                    }
                    waitForIdle()
                    assertEquals("$tag: session ends with focus", 0, DeskTyping.active)
                }
                onRoot().performKeyInput { pressKey(Key.Spacebar) }
                runOnIdle { assertTrue(s.state.playing); s.state.playing = false }
                onRoot().performKeyInput { pressKey(Key.Minus) }
                runOnIdle { assertTrue(s.state.timelineZoom < zoom) }
            }
        } finally { s.close() }
    }

    @Test fun layerRenameFieldBlocksShortcuts() {
        val s = session("rename")
        try {
            runSkikoComposeUiTest(size = Size(1100f, 400f)) {
                val root = FocusRequester()
                setContent {
                    DeskTyping.Track {
                        CompositionLocalProvider(LocalWide provides true) {
                            Box(Modifier.fillMaxSize().background(Am.TimelineBg).focusRequester(root).onKeyEvent { DeskKeys.handle(s.app, it) }.focusable()) { Timeline(s.state, Modifier.fillMaxSize()) }
                        }
                    }
                    LaunchedEffect(Unit) { root.requestFocus() }
                }
                waitForIdle()
                onRoot().performMouseInput { moveTo(Offset(250f, 57f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
                onNodeWithText("Rename").performClick()
                waitForIdle()
                mainClock.advanceTimeBy(500)
                assertTrue("rename dialog field has the text input session", DeskTyping.active > 0)
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Spacebar); pressKey(Key.Minus) }
                runOnIdle { assertFalse(s.state.playing); DeskBack.back() }
                waitForIdle()
                assertEquals(0, DeskTyping.active)
            }
        } finally { s.close() }
    }

    @Test fun escapeNeverLeavesTheEditor() {
        System.setProperty("am.data", File("build/logic-escape-data").also { it.deleteRecursively() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(500)
            val editor = app.editor!!
            runOnIdle { editor.selectedId = editor.scene.layers.last().id }
            mainClock.advanceTimeBy(300)
            repeat(6) {
                runOnIdle { DeskBack.back() }
                mainClock.advanceTimeBy(300)
                waitForIdle()
                assertTrue("still editing after Esc #${it + 1}", app.editor === editor)
            }
            assertNull(editor.selectedId)
            runOnIdle { app.closeSoon() }
            waitUntil(timeoutMillis = 20_000) { app.editor == null }
            app.engine.close()
        }
    }

    @Test fun enterLeavesFocusedButtonsAlone() {
        runSkikoComposeUiTest(size = Size(900f, 500f)) {
            var enters = 0
            var cancels = 0
            setContent {
                DeskTyping.Track {
                    DeskModal({ }, 500.dp, onEnter = { enters++ }) {
                        DeskButton("Cancel", { cancels++ })
                        DeskButton("Confirm", { enters++ }, primary = true)
                    }
                }
            }
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("Enter on the dialog confirms", 1, enters)
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("Enter on the focused Cancel button presses Cancel", 1, cancels)
            assertEquals("and does not confirm", 1, enters)
        }
    }

    @Test fun exportDialogKeepsRunningAndIgnoresEnterInFields() {
        System.setProperty("am.data", File("build/logic-export-data").also { it.deleteRecursively() }.absolutePath)
        System.setProperty("am.exports", File("build/logic-export-out").also { it.deleteRecursively() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val xml = File("../testdata/scenes/styles.xml").readText().replace(Regex("totalTime=\"\\d+\""), "totalTime=\"9999\"")
            runBlocking { app.openNow(app.store.create(Scene.parse(xml))) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            runOnIdle { editor.exportOpen = true }
            mainClock.advanceTimeBy(800)
            onNodeWithContentDescription("Filename").performClick()
            waitForIdle()
            assertTrue(DeskTyping.active > 0)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            mainClock.advanceTimeBy(500)
            assertEquals("Enter in the filename field must not start the export", 0, onAllNodesWithText("Cancel").fetchSemanticsNodes().size)
            runOnIdle { DeskKeys.releaseAll() }
            onAllNodesWithText("Export").onLast().performClick()
            mainClock.advanceTimeBy(500)
            assertEquals("the export runs", 1, onAllNodesWithText("Cancel").fetchSemanticsNodes().size)
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(20f, 400f)); press(); release() }
            mainClock.advanceTimeBy(500)
            runOnIdle { assertTrue("a click outside must not close a running export", editor.exportOpen) }
            assertEquals("and the export keeps running", 1, onAllNodesWithText("Cancel").fetchSemanticsNodes().size)
            onNodeWithText("Cancel").performClick()
            mainClock.advanceTimeBy(1500)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty() }
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(20f, 400f)); press(); release() }
            mainClock.advanceTimeBy(500)
            runOnIdle { assertFalse("an idle export dialog closes on an outside click", editor.exportOpen) }
            runBlocking { app.closeEditor() }
            app.engine.close()
        }
    }
}
