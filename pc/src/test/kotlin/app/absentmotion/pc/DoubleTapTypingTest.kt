package app.absentmotion.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.ui.editor.panels.DoubleTapToType
import app.absentmotion.ui.editor.panels.holdAndDoubleTap
import app.absentmotion.ui.editor.panels.typeOnDoubleTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class DoubleTapTypingTest {
    @Test fun aDoubleTapTypesOnTheValueBoxItLandsOnAndOtherwiseGoesToThePadAlone() = runSkikoComposeUiTest(size = Size(600f, 400f)) {
        var resets = 0
        var turned = 0f
        var cells = 0
        var selects = 0
        var types = 0
        setContent {
            val timing = remember { LongArray(2) }
            Box(
                Modifier.fillMaxSize().holdAndDoubleTap(timing) { resets++ }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        drag(down.id) { turned += it.positionChange().getDistance() }
                    }
                },
            ) {
                Box(Modifier.align(Alignment.Center).size(120.dp, 60.dp).testTag("value").typeOnDoubleTap(onSelect = { selects++ }) { types++ })
                Box(Modifier.align(Alignment.BottomStart).size(60.dp).testTag("cell").clickable { cells++ })
            }
        }
        mainClock.advanceTimeBy(1000)
        onRoot().performTouchInput {
            down(Offset(40f, 40f))
            up()
            advanceEventTime(80)
            down(Offset(40f, 40f))
            moveBy(Offset(2f, 3f))
            moveBy(Offset(1f, 2f))
            up()
        }
        waitForIdle()
        assertEquals(1, resets)
        assertEquals(0f, turned)

        mainClock.advanceTimeBy(1000)
        onNodeWithTag("cell").performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(2, resets)
        assertEquals(1, cells)

        mainClock.advanceTimeBy(1000)
        onNodeWithTag("value").performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(1, selects)
        assertEquals(1, types)
        assertEquals(2, resets)

        mainClock.advanceTimeBy(1000)
        onNodeWithTag("value").performMouseInput { click() }
        waitForIdle()
        assertEquals(2, selects)
        assertEquals(1, types)
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("value").performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(3, selects)
        assertEquals(2, types)
        assertEquals(2, resets)

        onNodeWithTag("value").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("value").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(3, types)
        onNodeWithTag("value").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(4, types)
        assertEquals(3, selects)
    }

    @Test fun aNumberFieldStartsTypingOnlyOnADoubleTap() = runSkikoComposeUiTest(size = Size(400f, 300f)) {
        setContent {
            var text by remember { mutableStateOf("12") }
            Column {
                DoubleTapToType(text, { text = it }, Modifier.size(120.dp, 40.dp).testTag("field")) { gate, typed, onTyped -> BasicTextField(typed, onTyped, gate) }
                Box(Modifier.size(120.dp, 40.dp).testTag("elsewhere").clickable {})
            }
        }
        val field = onNode(hasSetTextAction())
        onNodeWithTag("field").performMouseInput { click() }
        waitForIdle()
        field.assertIsNotFocused()
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("field").performMouseInput { doubleClick() }
        waitForIdle()
        field.assertIsFocused()
        field.performTextReplacement("34")
        field.assertTextEquals("34")
        onNodeWithTag("elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        field.assertIsNotFocused()
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("field").performTouchInput { click() }
        waitForIdle()
        field.assertIsNotFocused()
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("field").performTouchInput { doubleClick() }
        waitForIdle()
        field.assertIsFocused()
    }

    @Test fun aDoubleTapSelectsTheWholeValueSoTheFirstDigitReplacesItAndALaterTapPlacesTheCaret() = runSkikoComposeUiTest(size = Size(400f, 300f)) {
        var text by mutableStateOf("1080")
        setContent {
            Column {
                DoubleTapToType(text, { if (it.length <= 4 && it.all(Char::isDigit)) text = it }, Modifier.size(160.dp, 40.dp).testTag("field")) { gate, typed, onTyped ->
                    BasicTextField(typed, onTyped, gate, singleLine = true)
                }
                Box(Modifier.size(120.dp, 40.dp).testTag("elsewhere").clickable {})
            }
        }
        val field = onNode(hasSetTextAction())
        fun selection() = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        for (mouse in listOf(true, false)) {
            text = "1080"
            mainClock.advanceTimeBy(1000)
            if (mouse) onNodeWithTag("field").performMouseInput { doubleClick() } else onNodeWithTag("field").performTouchInput { doubleClick() }
            waitForIdle()
            field.assertIsFocused()
            assertEquals(TextRange(0, 4), selection())
            field.performTextInput("a")
            waitForIdle()
            assertEquals("1080", text)
            assertEquals(TextRange(0, 4), selection())
            if (!mouse) {
                field.performKeyInput { pressKey(Key.Backspace) }
                waitForIdle()
                assertEquals("", text)
            }
            field.performTextInput("5")
            waitForIdle()
            assertEquals("5", text)
            assertEquals(TextRange(1), selection())
            field.performTextInput("4")
            waitForIdle()
            assertEquals("54", text)
            mainClock.advanceTimeBy(1000)
            if (mouse) onNodeWithTag("field").performMouseInput { click(Offset(2f, centerY)) } else onNodeWithTag("field").performTouchInput { click(Offset(2f, centerY)) }
            waitForIdle()
            field.assertIsFocused()
            assertEquals(TextRange(0), selection())
            field.performTextInput("1")
            waitForIdle()
            assertEquals("154", text)
            onNodeWithTag("elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            field.assertIsNotFocused()
        }
    }

    @Test fun twoEditsBeforeTheNextFrameBothReachTheCaller() = runSkikoComposeUiTest(size = Size(400f, 300f)) {
        var text by mutableStateOf("10")
        setContent { DoubleTapToType(text, { text = it }, Modifier.size(160.dp, 40.dp)) { gate, typed, onTyped -> BasicTextField(typed, onTyped, gate, singleLine = true) } }
        val field = onNode(hasSetTextAction())
        val set = field.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        runOnUiThread { set(AnnotatedString("610")); set(AnnotatedString("10")) }
        waitForIdle()
        assertEquals("10", text)
        field.assertTextEquals("10")
    }

    @Test fun theNewProjectCustomSizeFieldsSelectTheirValueOnADoubleTap() {
        System.setProperty("am.data", File("build/double-tap-size-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            setContent { DeskApp(DeskAppState()) }
            onAllNodesWithText("New project", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(1500)
            onAllNodesWithText("Custom", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(1500)
            val texts = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.EditableText].text }
            val width = texts.indexOfFirst { it.isNotEmpty() && it.all(Char::isDigit) }
            for ((index, mouse) in listOf(width to true, width + 1 to false)) {
                val field = onAllNodes(hasSetTextAction())[index]
                val node = field.fetchSemanticsNode().config
                assertTrue(node[SemanticsProperties.EditableText].text.all(Char::isDigit))
                mainClock.advanceTimeBy(1000)
                if (mouse) field.performMouseInput { doubleClick() } else field.performTouchInput { doubleClick() }
                waitForIdle()
                field.assertIsFocused()
                assertEquals(TextRange(0, node[SemanticsProperties.EditableText].length), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                field.performTextInput("5")
                waitForIdle()
                field.assertTextEquals("5")
            }
        }
    }

    @Test fun everyDoubleTapFieldShowsTheValueDoubleTapToTypeHandsIt() {
        val calls = listOf("../shared/kotlin", "src/standins").flatMap { File(it).walk().filter { file -> file.extension == "kt" && file.name != "PanelParts.kt" }.toList() }
            .flatMap { file -> file.readText().split("DoubleTapToType(").drop(1).map { file.name to it.substringAfter("BasicTextField(").trimStart() } }
        assertEquals(5, calls.size)
        calls.forEach { (name, call) -> assertTrue(name, call.startsWith("typed, onTyped,")) }
    }
}
