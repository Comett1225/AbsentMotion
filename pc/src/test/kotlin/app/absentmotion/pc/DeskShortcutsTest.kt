package app.absentmotion.pc

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskJump
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskShortcuts
import app.absentmotion.desk.DeskTyping
import app.absentmotion.desk.MenuKey
import app.absentmotion.desk.Shortcut
import app.absentmotion.desk.shortcutText
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.DeskShortcutsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent.VK_B
import java.awt.event.KeyEvent.VK_C
import java.awt.event.KeyEvent.VK_D
import java.awt.event.KeyEvent.VK_J
import java.awt.event.KeyEvent.VK_K
import java.awt.event.KeyEvent.VK_V
import java.io.File
import java.util.Properties

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DeskShortcutsTest {
    private val command = if (DeskOs.mac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
    private val commandKey = if (DeskOs.mac) Key.MetaLeft else Key.CtrlLeft
    private val shift = InputEvent.SHIFT_DOWN_MASK
    private val cmdD = MenuKey(Key.D)
    private val cmdK = MenuKey(Key.K)

    private fun event(vk: Int, down: Boolean, mods: Int) = KeyEvent(
        Key(vk), if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
        isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0, isMetaPressed = mods and InputEvent.META_DOWN_MASK != 0,
        isAltPressed = mods and InputEvent.ALT_DOWN_MASK != 0, isShiftPressed = mods and InputEvent.SHIFT_DOWN_MASK != 0,
    )

    private fun data(name: String): File = File("build/shortcuts-$name-data").also {
        it.deleteRecursively()
        System.setProperty("am.data", it.absolutePath)
        DeskShortcuts.reload()
    }

    private class Session(val app: DeskAppState, val state: EditorState)

    private fun session(name: String): Session {
        data(name)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        return Session(app, runBlocking { app.openNow(id)!! })
    }

    private fun Session.press(vk: Int, mods: Int = 0): Boolean {
        val handled = DeskKeys.handle(app, event(vk, true, mods))
        DeskKeys.handle(app, event(vk, false, mods))
        return handled
    }

    private fun Session.close() {
        MultiSelect.clear()
        DeskKeys.releaseAll()
        DeskShortcuts.resetAll()
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun bindingsAreSavedAndLoadedAtStart() {
        val dir = data("store")
        try {
            DeskShortcuts.bind(Shortcut.Split, cmdK)
            DeskShortcuts.bind(Shortcut.AddBookmark, MenuKey(Key.F5, command = false, alt = true, shift = true))
            val saved = DeskShortcuts.custom
            assertEquals(listOf(cmdK), saved[Shortcut.Split])
            val stored = Properties().apply { File(dir, "prefs/desk.properties").inputStream().use(::load) }.getProperty("shortcuts")
            assertEquals(saved, DeskShortcuts.decode(stored))
            DeskShortcuts.reload()
            assertEquals(saved, DeskShortcuts.custom)
            assertEquals(listOf(cmdK), DeskShortcuts.keys(Shortcut.Split))
            assertEquals(Shortcut.Duplicate.defaults, DeskShortcuts.keys(Shortcut.Duplicate))
            assertEquals(emptyMap<Shortcut, List<MenuKey>>(), DeskShortcuts.decode("{not json"))
            assertEquals(if (DeskOs.mac) "⌘K" else "Ctrl+K", DeskShortcuts.text(Shortcut.Split))
            DeskShortcuts.resetAll()
            assertEquals(emptyMap<Shortcut, List<MenuKey>>(), DeskShortcuts.custom)
            assertNull(Properties().apply { File(dir, "prefs/desk.properties").inputStream().use(::load) }.getProperty("shortcuts"))
        } finally { DeskShortcuts.resetAll() }
    }

    @Test fun conflictsAreFoundAndAReplacedKeyLeavesTheOtherAction() {
        data("conflict")
        try {
            val all = Shortcut.entries.flatMap { it.defaults }
            assertEquals("no two defaults share a key", all.size, all.toSet().size)
            assertEquals(Shortcut.Duplicate, DeskShortcuts.conflict(Shortcut.Split, cmdD))
            assertNull(DeskShortcuts.conflict(Shortcut.Duplicate, cmdD))
            assertNull(DeskShortcuts.conflict(Shortcut.Split, cmdK))
            assertEquals(Shortcut.TimelineZoomIn, DeskShortcuts.conflict(Shortcut.Split, MenuKey(Key.Equals, command = false, shift = true)))
            assertEquals(Shortcut.Settings, DeskShortcuts.conflict(Shortcut.Split, MenuKey(Key.Comma)))
            DeskShortcuts.bind(Shortcut.Split, cmdD)
            assertEquals(listOf(cmdD), DeskShortcuts.keys(Shortcut.Split))
            assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Duplicate))
            assertEquals("", DeskShortcuts.text(Shortcut.Duplicate))
            DeskShortcuts.reset(Shortcut.Duplicate)
            assertEquals(listOf(cmdD), DeskShortcuts.keys(Shortcut.Duplicate))
            assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Split))
            DeskShortcuts.reset(Shortcut.Split)
            assertEquals(Shortcut.Split.defaults, DeskShortcuts.keys(Shortcut.Split))
            assertEquals(emptyMap<Shortcut, List<MenuKey>>(), DeskShortcuts.custom)
        } finally { DeskShortcuts.resetAll() }
    }

    @Test fun onlyKeysThatCanFireAreCaptured() {
        assertTrue(DeskKeys.bindable(mac = true, windows = false, ctrl = false, meta = true, alt = true))
        assertFalse(DeskKeys.bindable(mac = true, windows = false, ctrl = true, meta = false, alt = false))
        assertTrue(DeskKeys.bindable(mac = false, windows = true, ctrl = true, meta = false, alt = false))
        assertFalse(DeskKeys.bindable(mac = false, windows = true, ctrl = true, meta = false, alt = true))
        assertFalse(DeskKeys.bindable(mac = false, windows = true, ctrl = false, meta = true, alt = false))
        assertTrue(DeskKeys.bindable(mac = false, windows = false, ctrl = true, meta = false, alt = true))
        assertFalse(DeskKeys.bindable(mac = false, windows = false, ctrl = false, meta = true, alt = false))
    }

    @Test fun aForeignModifierRunsNothingAndTheBookmarkKeyOnlyAdds() {
        val s = session("foreign")
        try {
            val foreign = if (DeskOs.mac) InputEvent.CTRL_DOWN_MASK else InputEvent.META_DOWN_MASK
            s.state.selectedId = s.state.scene.layers.last().id
            val count = s.state.scene.layers.size
            assertFalse(s.press(java.awt.event.KeyEvent.VK_DELETE, foreign))
            assertEquals(count, s.state.scene.layers.size)
            s.state.frame = 10
            assertTrue(s.press(java.awt.event.KeyEvent.VK_M))
            assertTrue(s.press(java.awt.event.KeyEvent.VK_M))
            assertEquals(1, s.state.scene.bookmarks.count { s.state.scene.frameAt(it) == 10 })
        } finally { s.close() }
    }

    @Test fun jumpsStayInsideTheClipsAndParkAtTheEnd() {
        data("jumps")
        val app = DeskAppState()
        val id = app.store.create(Scene.parse("""<scene width="640" height="360" fps="30" totalTime="1999"><shape id="7" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location><kf t="-1" v="0,0,0"/><kf t="0.5" v="10,0,0"/><kf t="1.5" v="20,0,0"/></location></transform><property name="size" type="vec2" value="20,20"/></shape></scene>"""))
        val state = runBlocking { app.openNow(id)!! }
        val s = Session(app, state)
        try {
            state.frame = 10
            DeskJump.previousKey(state)
            assertEquals(10, state.frame)
            val inside = state.scene.frameAt(state.scene.layers.single().keyframeTimes().single { it in 0..999 })
            DeskJump.nextKey(state)
            assertEquals(inside, state.frame)
            DeskJump.nextKey(state)
            assertEquals(inside, state.frame)
            DeskJump.next(state)
            assertTrue(state.atEnd)
            assertEquals(state.scene.lastFrame + 1, state.playheadFrame)
        } finally { s.close() }
    }

    @Test fun earlierModifierVariantsStillWork() {
        val s = session("variants")
        try {
            assertEquals(Shortcut.PreviousBookmark, DeskShortcuts.find(MenuKey(Key.DirectionLeft, command = false, alt = true, shift = true)))
            assertEquals(Shortcut.NextBookmark, DeskShortcuts.find(MenuKey(Key.DirectionRight, command = false, alt = true, shift = true)))
            s.state.frame = 50
            assertTrue(s.press(java.awt.event.KeyEvent.VK_HOME, command))
            assertEquals(0, s.state.frame)
            assertTrue(s.press(java.awt.event.KeyEvent.VK_END, command))
            assertEquals(s.state.scene.lastFrame, s.state.frame)
            s.state.selectedId = s.state.scene.layers.last().id
            val count = s.state.scene.layers.size
            assertTrue(s.press(java.awt.event.KeyEvent.VK_DELETE, shift))
            assertEquals(count - 1, s.state.scene.layers.size)
            val zoom = app.absentmotion.desk.DeskScale.percent
            try {
                assertTrue(app.absentmotion.desk.DeskGlobalKeys.handle(s.app, event(java.awt.event.KeyEvent.VK_MINUS, true, command or shift)))
                assertTrue(app.absentmotion.desk.DeskScale.percent < zoom)
            } finally { app.absentmotion.desk.DeskScale.set(zoom) }
        } finally { s.close() }
    }

    @Test fun shortcutsSplitAndCopyAndPasteKeyframesAtThePlayhead() {
        val s = session("dispatch")
        try {
            val scene = s.state.scene
            val split = scene.layers.last()
            s.state.selectedId = split.id
            s.state.frame = 30
            assertTrue(s.press(VK_B, command))
            assertEquals(scene.layers.size + 1, s.state.scene.layers.size)

            val target = s.state.scene.layers.first().id
            s.state.selectedId = target
            s.state.keyTarget = KeyTarget(PropRef.Location, "0,0,0")
            s.state.frame = 10
            assertTrue(s.press(VK_K, InputEvent.ALT_DOWN_MASK))
            s.state.frame = 40
            s.state.editSelected { it.withTransform("location", 700f, 900f, 0f) }
            s.state.frame = 10
            val start = s.state.selected!!.location.toList()
            assertTrue(s.press(VK_C, command or shift))
            s.state.frame = 70
            assertTrue(s.press(VK_V, command or shift))
            val layer = s.state.scene.layer(target)!!
            assertEquals(listOf(10, 40, 70), layer.keyframes(PropRef.Location).map { s.state.scene.frameAt(layer.timeOf(it.t)) })
            s.state.selected!!.location.toList().zip(start).forEach { (now, was) -> assertEquals(was, now, 0.01f) }
            s.state.undo()
            assertEquals(listOf(10, 40), s.state.scene.layer(target)!!.keyframes(PropRef.Location).map { s.state.scene.frameAt(layer.timeOf(it.t)) })

            s.state.frame = 50
            s.press(VK_J)
            assertEquals(40, s.state.frame)
            s.press(VK_J)
            assertEquals(10, s.state.frame)
            s.press(VK_K)
            assertEquals(40, s.state.frame)

            DeskShortcuts.bind(Shortcut.Split, cmdK)
            s.state.selectedId = target
            val before = s.state.scene.layers.size
            assertFalse(s.press(VK_B, command))
            assertEquals(before, s.state.scene.layers.size)
            assertTrue(s.press(VK_K, command))
            assertEquals(before + 1, s.state.scene.layers.size)
        } finally { s.close() }
    }

    @Test fun typingInAFieldNeverTriggersShortcuts() {
        val s = session("typing")
        try {
            val layer = s.state.scene.layers.last()
            s.state.selectedId = layer.id
            s.state.keyTarget = KeyTarget(PropRef.Location, "0,0,0")
            s.state.frame = 20
            runSkikoComposeUiTest(size = Size(800f, 300f)) {
                val root = FocusRequester()
                setContent {
                    var text by remember { mutableStateOf("") }
                    DeskTyping.Track {
                        Column(Modifier.fillMaxSize().focusRequester(root).onPreviewKeyEvent { DeskKeys.handle(s.app, it) }.focusable()) {
                            BasicTextField(text, { text = it }, Modifier.testTag("field").fillMaxWidth().height(30.dp))
                        }
                    }
                    LaunchedEffect(Unit) { root.requestFocus() }
                }
                waitForIdle()
                val scene = s.state.scene
                onNodeWithTag("field").performClick()
                waitForIdle()
                assertTrue(DeskTyping.active > 0)
                val zoom = app.absentmotion.desk.DeskScale.percent
                runOnIdle { assertFalse(app.absentmotion.desk.DeskGlobalKeys.handle(s.app, event(java.awt.event.KeyEvent.VK_EQUALS, true, command))) }
                assertEquals(zoom, app.absentmotion.desk.DeskScale.percent)
                onRoot().performKeyInput {
                    keyDown(commandKey); pressKey(Key.B); keyDown(Key.ShiftLeft); pressKey(Key.C); pressKey(Key.V); keyUp(Key.ShiftLeft); keyUp(commandKey)
                    keyDown(Key.AltLeft); pressKey(Key.K); keyUp(Key.AltLeft)
                    pressKey(Key.J); pressKey(Key.K); pressKey(Key.M); pressKey(Key.Spacebar); pressKey(Key.DirectionLeft)
                }
                waitForIdle()
                runOnIdle {
                    assertEquals("the project must not change while typing", scene, s.state.scene)
                    assertEquals(20, s.state.frame)
                    assertFalse(s.state.playing)
                    root.requestFocus()
                }
                waitForIdle()
                assertEquals(0, DeskTyping.active)
                onRoot().performKeyInput { keyDown(commandKey); pressKey(Key.B); keyUp(commandKey) }
                runOnIdle { assertEquals(scene.layers.size + 1, s.state.scene.layers.size) }
            }
        } finally { s.close() }
    }

    @Test fun theSettingsPageRebindsWarnsAndResets() {
        data("page")
        try {
            runSkikoComposeUiTest(size = Size(1100f, 2400f)) {
                setContent { DeskShortcutsPage() }
                waitForIdle()
                onNodeWithText(shortcutText(MenuKey(Key.B), DeskOs.mac)).performClick()
                waitForIdle()
                onNodeWithText("Press keys…").assertExists()
                val foreign = if (DeskOs.mac) InputEvent.CTRL_DOWN_MASK else InputEvent.META_DOWN_MASK
                runOnIdle { assertTrue(DeskKeys.viewer!!.invoke(event(java.awt.event.KeyEvent.VK_S, true, foreign))) }
                waitForIdle()
                onNodeWithText("Press keys…").assertExists()
                assertEquals(Shortcut.Split.defaults, DeskShortcuts.keys(Shortcut.Split))
                runOnIdle { assertTrue(DeskKeys.viewer!!.invoke(event(VK_D, true, command))) }
                waitForIdle()
                onNodeWithText("Already used by “Duplicate”.").assertExists()
                assertEquals(Shortcut.Split.defaults, DeskShortcuts.keys(Shortcut.Split))
                onNodeWithText("Replace").performClick()
                waitForIdle()
                assertEquals(listOf(cmdD), DeskShortcuts.keys(Shortcut.Split))
                assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Duplicate))
                assertNull(DeskKeys.viewer)
                onAllNodesWithContentDescription("Reset")[0].performClick()
                waitForIdle()
                onNodeWithText("Already used by “Split at playhead”.").assertExists()
                onNodeWithText("Cancel").performClick()
                waitForIdle()
                assertEquals(listOf(cmdD), DeskShortcuts.keys(Shortcut.Split))
                assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Duplicate))
                onAllNodesWithContentDescription("Reset")[0].performClick()
                waitForIdle()
                onNodeWithText("Replace").performClick()
                waitForIdle()
                assertEquals(Shortcut.Duplicate.defaults, DeskShortcuts.keys(Shortcut.Duplicate))
                assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Split))
                onNodeWithText(shortcutText(MenuKey(Key.E), DeskOs.mac)).performClick()
                waitForIdle()
                runOnIdle { DeskKeys.viewer!!.invoke(event(java.awt.event.KeyEvent.VK_F6, true, 0)) }
                waitForIdle()
                assertEquals(listOf(MenuKey(Key.F6, command = false)), DeskShortcuts.keys(Shortcut.Export))
                assertEquals(2, onAllNodesWithContentDescription("Reset").fetchSemanticsNodes().size)
                onNodeWithText("Reset all").performClick()
                waitForIdle()
                assertEquals(emptyMap<Shortcut, List<MenuKey>>(), DeskShortcuts.custom)
                onNodeWithText(shortcutText(MenuKey(Key.B), DeskOs.mac)).assertExists()
            }
        } finally { DeskShortcuts.resetAll() }
    }
    @Test fun removingAShortcutLeavesItEmptyAfterARestart() {
        val s = session("remove")
        try {
            DeskShortcuts.clear(Shortcut.Split)
            assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Split))
            DeskShortcuts.reload()
            assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Split))
            assertEquals("", DeskShortcuts.text(Shortcut.Split))
            assertFalse(s.press(VK_B, command))
            DeskShortcuts.reset(Shortcut.Split)
            assertEquals(Shortcut.Split.defaults, DeskShortcuts.keys(Shortcut.Split))
        } finally { s.close() }
    }

    @Test fun rightClickChangesRemovesAndResetsAShortcut() {
        data("rightclick")
        try {
            runSkikoComposeUiTest(size = Size(400f, 400f)) {
                setContent { app.absentmotion.ui.editor.DeskIcon(app.absentmotion.ui.editor.ChromeIcons.Undo, "Undo", shortcut = Shortcut.Undo) {} }
                onNodeWithContentDescription("Undo").performMouseInput { rightClick() }
                waitForIdle()
                onNodeWithText("Change shortcut").performClick()
                waitForIdle()
                onNodeWithText("Press keys…").assertExists()
                runOnIdle { DeskKeys.viewer!!.invoke(event(java.awt.event.KeyEvent.VK_F7, true, 0)) }
                waitForIdle()
                assertEquals(listOf(MenuKey(Key.F7, command = false)), DeskShortcuts.keys(Shortcut.Undo))
                assertNull(DeskKeys.viewer)
                onNodeWithContentDescription("Undo").performMouseInput { rightClick() }
                waitForIdle()
                onNodeWithText("Change shortcut").performClick()
                waitForIdle()
                runOnIdle { DeskKeys.viewer!!.invoke(event(VK_D, true, command)) }
                waitForIdle()
                onNodeWithText("Already used by “Duplicate”.").assertExists()
                onNodeWithText("Cancel").performClick()
                waitForIdle()
                assertEquals(listOf(MenuKey(Key.F7, command = false)), DeskShortcuts.keys(Shortcut.Undo))
                onNodeWithText("Remove shortcut").performClick()
                waitForIdle()
                assertEquals(emptyList<MenuKey>(), DeskShortcuts.keys(Shortcut.Undo))
                onNodeWithContentDescription("Undo").performMouseInput { rightClick() }
                waitForIdle()
                onNodeWithText("Reset to default").performClick()
                waitForIdle()
                assertEquals(Shortcut.Undo.defaults, DeskShortcuts.keys(Shortcut.Undo))
            }
        } finally { DeskShortcuts.resetAll() }
    }
}
