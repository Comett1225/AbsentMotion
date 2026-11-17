package app.absentmotion.pc

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import app.absentmotion.desk.DeskGlobalKeys
import app.absentmotion.desk.DeskAppState
import app.absentmotion.ui.home.AlightLinkEntry
import app.absentmotion.ui.home.AlightLinkHelper
import app.absentmotion.ui.home.DeskHome
import app.absentmotion.ui.home.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class AlightLinkHelperTest {
    private class TestClipboard : ClipboardManager {
        private var value: AnnotatedString? = null
        override fun getText() = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    private val link = "https://alight.link/Example123"
    private val shots = File("build/ui-shots/alight-links").also { it.mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(700)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    @Test fun entryValidatesAndOpensWithoutFetching() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        var opened: String? = null
        setContent { AlightLinkEntry(onDismiss = {}, onLink = { opened = it }) }
        onNodeWithText("Open").assertIsNotEnabled()
        val button = onNodeWithText("Open").fetchSemanticsNode().boundsInRoot
        onNode(hasSetTextAction()).performTextInput("https://alight.link.evil.com/" + "wrapped".repeat(20))
        onNodeWithText("Open").assertIsNotEnabled()
        assertEquals(button, onNodeWithText("Open").fetchSemanticsNode().boundsInRoot)
        onNode(hasSetTextAction()).performTextReplacement(link)
        onNodeWithText("Open").assertIsEnabled()
        assertEquals(button, onNodeWithText("Open").fetchSemanticsNode().boundsInRoot)
        shot("paste-dialog")
        onNodeWithText("Open").performClick()
        assertEquals(link, opened)
    }

    @Test fun offlineHelperReservesPreviewAndKeepsInstructionsAtLargeText() {
        for ((name, size, fontScale) in listOf(Triple("small", Size(360f, 740f), 1f), Triple("tablet-large-text", Size(900f, 1000f), 1.6f), Triple("desktop", Size(1280f, 800f), 1f))) {
            runSkikoComposeUiTest(size = size) {
                val clipboard = TestClipboard()
                setContent {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalDensity provides Density(1f, fontScale)) { AlightLinkHelper(link, {}, loadPreview = false) }
                }
                onNodeWithText(link).assertExists()
                onNodeWithText("The preview is unavailable. You can still use the link and follow these steps.").assertExists()
                onNodeWithText("Copy link").performScrollTo().performClick()
                runOnIdle { assertEquals(link, clipboard.getText()?.text) }
                shot("$name-card")
                onNodeWithText("3. Export the project from Alight Motion as XML and choose AbsentMotion to open it.").performScrollTo().assertIsDisplayed()
                shot("$name-steps")
            }
        }
    }

    @Test fun phoneAddMenuOffersAlightLink() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        val app = DeskAppState()
        setContent { HomeScreen(app.store, app.settings, onOpen = {}) }
        onNodeWithContentDescription("New project").performClick()
        onNodeWithText("Alight link").performClick()
        onNodeWithText("Alight Motion link").assertExists()
        onNode(hasSetTextAction()).assertExists()
    }

    @Test fun desktopImportAndHomePasteOpenTheSameEntry() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val app = DeskAppState()
        val focus = FocusRequester()
        val clipboard = TestClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { Box(Modifier.focusRequester(focus).focusable().onKeyEvent { DeskGlobalKeys.handle(app, it) }) {
                DeskHome(app.store, app.settings, {}, {}, emptyList(), {}, {}, {}, {}, {}, {}, 0, 0)
            } }
        }
        onAllNodesWithText("Import").onFirst().performClick()
        onNodeWithText("Alight link").performClick()
        onNodeWithText("Alight Motion link").assertExists()
        onNodeWithText("Cancel").performClick()
        runOnIdle { clipboard.setText(AnnotatedString("Shared from a browser: $link")); focus.requestFocus() }
        onAllNodes(isRoot()).onFirst().performKeyInput {
            withKeyDown(if (System.getProperty("os.name").lowercase().contains("mac")) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) }
        }
        onNodeWithText("Alight Motion link").assertExists()
        onNode(hasSetTextAction() and hasText("Alight Motion link")).assertTextContains(link)
        shot("home-paste")
    }
}
