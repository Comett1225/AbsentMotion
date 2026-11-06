package app.absentmotion.pc

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskJump
import app.absentmotion.desk.DeskKeys
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskDividerMath
import app.absentmotion.ui.editor.DeskDrop
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.Timeline
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskEditorRoundTest {
    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val dir = File("build/ui-shots/editor-round").also { it.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name.png"))
    }

    @Test fun jumpOrder() {
        val marks = listOf(20, 50)
        assertEquals(20, DeskJump.previousFrame(marks, 35))
        assertEquals(0, DeskJump.previousFrame(marks, 20))
        assertEquals(0, DeskJump.previousFrame(marks, 0))
        assertEquals(0, DeskJump.previousFrame(emptyList(), 35))
        assertEquals(20, DeskJump.previousFrame(marks, 21))
        assertEquals(50, DeskJump.nextFrame(marks, 35, 89))
        assertEquals(50, DeskJump.nextFrame(marks, 20, 89))
        assertEquals(89, DeskJump.nextFrame(marks, 50, 89))
        assertEquals(89, DeskJump.nextFrame(emptyList(), 3, 89))
        assertEquals(89, DeskJump.nextFrame(marks, 89, 89))
    }

    @Test fun dividerMath() {
        assertEquals(0f, DeskDividerMath.stretch(300f, 240f, 440f), 0f)
        assertEquals(0f, DeskDividerMath.stretch(440f, 240f, 440f), 0f)
        assertEquals(DeskDividerMath.band(40f), DeskDividerMath.stretch(480f, 240f, 440f), 0.001f)
        assertEquals(-DeskDividerMath.band(40f), DeskDividerMath.stretch(200f, 240f, 440f), 0.001f)
        var last = 0f
        for (over in listOf(1f, 10f, 50f, 200f, 5000f)) {
            val b = DeskDividerMath.band(over)
            assertTrue(b > last && b < DeskDividerMath.Reach)
            last = b
        }
        assertEquals(440f, DeskDividerMath.limit(900f, 240f, 440f), 0f)
        assertEquals(240f, DeskDividerMath.limit(10f, 240f, 440f), 0f)
        assertEquals(300f, DeskDividerMath.limit(300f, 240f, 440f), 0f)
        assertEquals(240f, DeskDividerMath.limit(300f, 240f, 100f), 0f)
    }

    @Test fun altArrowsJumpBetweenBookmarks() {
        System.setProperty("am.data", File("build/editor-round-keys").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        state.edit { it.withBookmarks(listOf(it.frameStart(20), it.frameStart(50))) }
        val last = state.scene.lastFrame
        state.frame = 35
        state.playing = true
        runSkikoComposeUiTest(size = Size(800f, 300f)) {
            setContent {
                val focus = remember { FocusRequester() }
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { DeskKeys.handle(app, it) }.focusable()) { Timeline(state, Modifier.fillMaxSize()) }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            fun alt(key: Key) = onRoot().performKeyInput { keyDown(Key.AltLeft); keyDown(key); keyUp(key); keyUp(Key.AltLeft) }
            alt(Key.DirectionLeft)
            runOnIdle { assertEquals(20, state.frame); assertTrue(!state.playing) }
            alt(Key.DirectionLeft)
            runOnIdle { assertEquals(0, state.frame) }
            alt(Key.DirectionRight)
            runOnIdle { assertEquals(20, state.frame) }
            alt(Key.DirectionRight)
            runOnIdle { assertEquals(50, state.frame) }
            alt(Key.DirectionRight)
            runOnIdle { assertEquals(last, state.frame) }
            alt(Key.DirectionLeft)
            runOnIdle { assertEquals(50, state.frame) }
            onRoot().performKeyInput { keyDown(Key.AltLeft); keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft); keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft); keyUp(Key.AltLeft) }
            runOnIdle { assertEquals(0, state.frame) }
        }
        MultiSelect.clear()
        app.engine.close()
    }

    @Test fun browserKeysReachTheOpenLibraryAndAltSpaceLeavesPlayback() {
        System.setProperty("am.data", File("build/editor-round-browser-keys").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val seen = mutableListOf<Key>()
        DeskDrop.browserKey = { event: androidx.compose.ui.input.key.KeyEvent -> if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) { seen += event.key; true } else false }
        try {
            runSkikoComposeUiTest(size = Size(800f, 300f)) {
                setContent {
                    val focus = remember { FocusRequester() }
                    CompositionLocalProvider(LocalWide provides true) {
                        Box(Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { DeskKeys.handle(app, it) }.focusable()) { Timeline(state, Modifier.fillMaxSize()) }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
                val before = state.frame
                onRoot().performKeyInput { pressKey(Key.DirectionRight) }
                runOnIdle { assertEquals(listOf(Key.DirectionRight), seen); assertEquals(before, state.frame) }
                state.playing = false
                onRoot().performKeyInput { keyDown(Key.AltLeft); pressKey(Key.Spacebar); keyUp(Key.AltLeft) }
                runOnIdle { assertTrue(!state.playing) }
                onRoot().performKeyInput { pressKey(Key.Spacebar) }
                runOnIdle { assertTrue(state.playing) }
            }
        } finally {
            DeskDrop.browserKey = null
            MultiSelect.clear()
            app.engine.close()
        }
    }

    private fun ComposeUiTest.open(app: DeskAppState) {
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
    }

    @Test fun dividerHeldPastLimitBouncesBack() {
        System.setProperty("am.data", File("build/editor-round-divider").also { it.deleteRecursively() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1728f, 1080f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val before = DeskPreviewBounds.bounds.left
            val divider = Offset(before - 3f, 400f)
            onRoot().performMouseInput { moveTo(divider); press(); moveTo(Offset(divider.x + 100f, 400f), 100); moveTo(Offset(divider.x + 600f, 400f), 300) }
            mainClock.advanceTimeBy(200)
            assertEquals(before + 140f, DeskPreviewBounds.bounds.left, 0.5f)
            shot("divider-held-library")
            onRoot().performMouseInput { moveTo(Offset(divider.x + 700f, 400f), 100) }
            mainClock.advanceTimeBy(100)
            assertEquals(before + 140f, DeskPreviewBounds.bounds.left, 0.5f)
            onRoot().performMouseInput { moveTo(Offset(divider.x + 60f, 400f), 100) }
            mainClock.advanceTimeBy(100)
            assertEquals(before + 60f, DeskPreviewBounds.bounds.left, 0.5f)
            onRoot().performMouseInput { moveTo(Offset(divider.x + 600f, 400f), 100); release() }
            repeat(60) { mainClock.advanceTimeBy(25) }
            assertEquals(before + 140f, DeskPreviewBounds.bounds.left, 0.5f)
            val right = DeskPreviewBounds.bounds.right
            val inspectorDivider = Offset(right + 3f, 400f)
            onRoot().performMouseInput { moveTo(inspectorDivider); press(); moveTo(Offset(inspectorDivider.x - 800f, 400f), 300) }
            mainClock.advanceTimeBy(200)
            val heldRight = DeskPreviewBounds.bounds.right
            shot("divider-held-inspector")
            onRoot().performMouseInput { release() }
            repeat(60) { mainClock.advanceTimeBy(25) }
            assertEquals(heldRight, DeskPreviewBounds.bounds.right, 0.5f)
            assertTrue(right - heldRight in 100f..165f)
            val timelineY = DeskPreviewBounds.bounds.bottom + 40f + 3f
            onRoot().performMouseInput { moveTo(Offset(900f, timelineY)); press(); moveTo(Offset(900f, timelineY - 900f), 300) }
            mainClock.advanceTimeBy(200)
            shot("divider-held-timeline")
            onRoot().performMouseInput { release() }
            repeat(60) { mainClock.advanceTimeBy(25) }
            shot("divider-released"); println("STEP er-released")
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun stretchAndHookShots() {
        System.setProperty("am.data", File("build/editor-round-multi").also { it.deleteRecursively() }.absolutePath)
        for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            val layers = editor.scene.layers
            MultiSelect.ids = layers.take(2).map { it.id }.toSet()
            MultiSelect.active = true
            mainClock.advanceTimeBy(300)
            shot("multi-$w")
            onAllNodesWithText("Stretch Timing").onFirst().performScrollTo().performClick()
            mainClock.advanceTimeBy(400)
            shot("stretch-sheet-$w")
            val sheet = onAllNodesWithText("Apply", useUnmergedTree = true).fetchSemanticsNodes().size
            println("stretch sheet apply nodes at $w: $sheet")
            val ends = layers.take(2).map { it.endTime }
            if (sheet > 0) onAllNodesWithText("Apply", useUnmergedTree = true).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            runOnIdle { println("ends $ends -> ${editor.scene.layers.take(2).map { it.endTime }}") }
            runOnIdle { editor.undo() }
            MultiSelect.clear()
            mainClock.advanceTimeBy(300)
            shot("hook-$w")
            runBlocking { app.closeEditor() }
        }
    }
}
