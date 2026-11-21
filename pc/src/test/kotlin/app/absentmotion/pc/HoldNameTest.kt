package app.absentmotion.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.ui.NameSpot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.ui.HoldName
import app.absentmotion.ui.NAME_LINGER_MS
import app.absentmotion.ui.editor.BarButton
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.DeskTip
import app.absentmotion.ui.editor.MultiTopBar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class HoldNameTest {
    private val hold = 700L

    @After fun clear() = MultiSelect.clear()

    private fun ComposeUiTest.shown(name: String) = onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.bounds(name: String): Rect = onNodeWithText(name).fetchSemanticsNode().boundsInWindow

    private fun ComposeUiTest.press(description: String, holdMs: Long) {
        onNodeWithContentDescription(description).performTouchInput { down(center) }
        mainClock.advanceTimeBy(holdMs)
    }

    private fun ComposeUiTest.lift(description: String) {
        onNodeWithContentDescription(description).performTouchInput { up() }
        mainClock.advanceTimeBy(50)
    }

    private fun withEditor(body: (DeskAppState, EditorState) -> Unit) {
        System.setProperty("am.data", File("build/hold-name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        val shapes = (1..3).joinToString("") { """<shape id="${7000 + it}" label="S$it" startTime="0" endTime="999" fillType="color" mediaFillMode="fill" s=".rect"><fillColor value="#ff1d6bd6" /></shape>""" }
        val id = app.store.create(Scene.parse("""<?xml version='1.0' encoding='UTF-8' ?><scene title="Hold" width="1080" height="1920" totalTime="999" fps="30" bgcolor="#ff000000">$shapes</scene>"""))
        val state = runBlocking { app.openNow(id)!! }
        try { body(app, state) } finally { MultiSelect.clear(); app.engine.close() }
    }

    @Test fun holdingAMultiSelectButtonShowsItsNameAndReleasingDoesNotRunIt() = withEditor { _, state ->
        runSkikoComposeUiTest(size = Size(420f, 300f)) {
            MultiSelect.active = true
            MultiSelect.ids = state.scene.layers.map { it.id }.toSet()
            setContent { Box(Modifier.fillMaxSize()) { MultiTopBar(state, Modifier.fillMaxWidth().height(48.dp)) } }
            waitForIdle()
            mainClock.autoAdvance = false
            val layers = state.scene.layers.size

            press("Group options", hold)
            assertTrue(shown("Group options"))
            val button = onNodeWithContentDescription("Group options").fetchSemanticsNode().boundsInWindow
            val label = bounds("Group options")
            assertTrue("label $label beside the button $button", label.top < button.bottom && label.bottom > button.top && (label.left >= button.right || label.right <= button.left))
            lift("Group options")
            assertTrue(shown("Group options"))
            mainClock.advanceTimeBy(NAME_LINGER_MS + 100)
            assertTrue(!shown("Group options"))
            assertEquals(layers, state.scene.layers.size)
            assertEquals(3, MultiSelect.ids.size)

            val delete = "Delete selected layers"
            press(delete, hold)
            assertTrue(shown(delete))
            lift(delete)
            mainClock.advanceTimeBy(NAME_LINGER_MS + 100)
            assertEquals(layers, state.scene.layers.size)

            onNodeWithContentDescription(delete).performTouchInput { down(center); up() }
            mainClock.advanceTimeBy(hold)
            assertTrue(!shown(delete))
            assertEquals(0, state.scene.layers.size)
        }
    }

    @Test fun aQuickTapRunsTheActionAndShowsNoName() = runSkikoComposeUiTest(size = Size(300f, 200f)) {
        var taps = 0
        setContent { Box(Modifier.fillMaxSize(), Alignment.Center) { BarButton(ChromeIcons.Trash, "Delete", Modifier.size(48.dp)) { taps++ } } }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Delete").performTouchInput { down(center); up() }
        mainClock.advanceTimeBy(hold)
        assertEquals(1, taps)
        assertTrue(!shown("Delete"))
        onNodeWithContentDescription("Delete").performMouseInput { moveTo(center); press() }
        mainClock.advanceTimeBy(hold)
        onNodeWithContentDescription("Delete").performMouseInput { release() }
        mainClock.advanceTimeBy(50)
        assertTrue(!shown("Delete"))
        assertEquals(2, taps)
        press("Delete", hold)
        onNodeWithContentDescription("Delete").performTouchInput { moveBy(Offset(0f, 200f)) }
        mainClock.advanceTimeBy(50)
        assertTrue(!shown("Delete"))
        lift("Delete")
        assertEquals(2, taps)
        mainClock.advanceTimeBy(NAME_LINGER_MS)
        press("Delete", hold)
        onNodeWithContentDescription("Delete").performTouchInput { moveBy(Offset(12f, 6f)) }
        mainClock.advanceTimeBy(50)
        assertTrue(shown("Delete"))
        lift("Delete")
        assertEquals(2, taps)
    }

    @Test fun aButtonWithItsOwnLongPressKeepsIt() = runSkikoComposeUiTest(size = Size(300f, 200f)) {
        var taps = 0
        var longs = 0
        setContent { Box(Modifier.fillMaxSize(), Alignment.Center) { BarButton(ChromeIcons.Play, "Play", Modifier.size(48.dp), onLongClick = { longs++ }) { taps++ } } }
        waitForIdle()
        mainClock.autoAdvance = false
        press("Play", hold)
        lift("Play")
        assertEquals(1, longs)
        assertEquals(0, taps)
        assertTrue(!shown("Play"))
    }

    @Test fun desktopHoverTipsStillShowBesideAHoldName() = runSkikoComposeUiTest(size = Size(300f, 200f)) {
        var taps = 0
        setContent {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Box(Modifier.size(48.dp).clickable { taps++ }, Alignment.Center) {
                    DeskTip("Reset") { Icon(ChromeIcons.Undo, "Reset", Modifier.size(24.dp)) }
                    HoldName("Reset")
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Reset").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(hold)
        assertEquals(1, onAllNodesWithText("Reset").fetchSemanticsNodes().size)
        onNodeWithContentDescription("Reset").performMouseInput { moveTo(Offset(-200f, -200f)) }
        mainClock.advanceTimeBy(hold)
        press("Reset", hold)
        assertTrue(shown("Reset"))
        lift("Reset")
        assertEquals(0, taps)
    }

    @Test fun theNameStaysOnScreenNearAnEdge() = runSkikoComposeUiTest(size = Size(320f, 240f)) {
        val long = "Exit to the project list and keep every change"
        setContent {
            Box(Modifier.fillMaxSize()) {
                BarButton(ChromeIcons.Exit, long, Modifier.align(Alignment.TopEnd).size(40.dp))
                BarButton(ChromeIcons.Undo, "Undo", Modifier.align(Alignment.BottomStart).size(40.dp))
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        val window = Rect(0f, 0f, 320f, 240f)
        press(long, hold)
        val top = bounds(long)
        val exit = onNodeWithContentDescription(long).fetchSemanticsNode().boundsInWindow
        assertTrue("label $top inside $window", top.left >= window.left && top.right <= window.right && top.top >= window.top && top.bottom <= window.bottom)
        assertTrue("label $top beside $exit", top.right <= exit.left && top.top < exit.bottom)
        lift(long)
        mainClock.advanceTimeBy(NAME_LINGER_MS + 100)
        press("Undo", hold)
        val bottom = bounds("Undo")
        val undo = onNodeWithContentDescription("Undo").fetchSemanticsNode().boundsInWindow
        assertTrue("label $bottom inside $window", bottom.left >= window.left && bottom.right <= window.right && bottom.top >= window.top)
        assertTrue("label $bottom above $undo", bottom.bottom <= undo.top)
        lift("Undo")
    }

    @Test fun theNameGoesBesideOnTheStartSideInRightToLeftAndBelowOnlyWhenNothingElseFits() {
        val spot = NameSpot(8)
        val window = IntSize(400, 800)
        val button = IntRect(180, 10, 220, 50)
        val label = IntSize(100, 30)
        val ltr = spot.calculatePosition(button, window, LayoutDirection.Ltr, label)
        assertEquals(IntOffset(228, 15), ltr)
        val rtl = spot.calculatePosition(button, window, LayoutDirection.Rtl, label)
        assertEquals(IntOffset(72, 15), rtl)
        val wide = spot.calculatePosition(button, window, LayoutDirection.Ltr, IntSize(300, 30))
        assertEquals(IntOffset(50, 58), wide)
        val low = spot.calculatePosition(IntRect(180, 400, 220, 440), window, LayoutDirection.Rtl, label)
        assertEquals(IntOffset(150, 362), low)
        val underStatusBar = NameSpot(8, top = 136).calculatePosition(IntRect(180, 150, 220, 190), window, LayoutDirection.Ltr, label)
        assertEquals(IntOffset(228, 155), underStatusBar)
    }

    @Test fun slidingIntoTheButtonPaddingAfterTheNameShowedStillDoesNotPressIt() = runSkikoComposeUiTest(size = Size(300f, 200f)) {
        var clicks = 0
        setContent {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Box(Modifier.height(56.dp).clickable { clicks++ }.padding(horizontal = 20.dp), Alignment.Center) {
                    Icon(ChromeIcons.Gear, "Custom", Modifier.size(31.dp))
                    HoldName("Custom")
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        press("Custom", hold)
        assertTrue(shown("Custom"))
        onNodeWithContentDescription("Custom").performTouchInput { moveBy(Offset(25f, 0f)) }
        mainClock.advanceTimeBy(50)
        assertTrue(!shown("Custom"))
        lift("Custom")
        assertEquals(0, clicks)
        onNodeWithContentDescription("Custom").performTouchInput { down(center); up() }
        mainClock.advanceTimeBy(hold)
        assertEquals(1, clicks)
    }

    @Test fun aSwipeThatAParentTakesOverAfterTheNameShowedStillEndsAsASwipe() = runSkikoComposeUiTest(size = Size(400f, 200f)) {
        var drag by mutableFloatStateOf(0f)
        var ended = 0
        var cancelled = 0
        var clicks = 0
        setContent {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Box(
                    Modifier.size(60.dp)
                        .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { ended++ }, onDragCancel = { cancelled++ }) { change, amount -> change.consume(); drag += amount } }
                        .graphicsLayer { translationX = drag }
                        .clickable { clicks++ },
                    Alignment.Center,
                ) {
                    Icon(ChromeIcons.Transition, "Transition", Modifier.size(24.dp))
                    HoldName("Transition")
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        press("Transition", hold)
        assertTrue(shown("Transition"))
        repeat(6) {
            onNodeWithContentDescription("Transition").performTouchInput { moveBy(Offset(10f, 0f)) }
            mainClock.advanceTimeBy(16)
        }
        assertTrue(!shown("Transition"))
        lift("Transition")
        assertEquals(1, ended)
        assertEquals(0, cancelled)
        assertEquals(0, clicks)
        assertTrue(drag > 0f)
    }
}
