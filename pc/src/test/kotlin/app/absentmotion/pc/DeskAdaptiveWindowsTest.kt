package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskScale
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.addText
import app.absentmotion.editor.startFreehand
import app.absentmotion.editor.leaveDrawing
import app.absentmotion.model.Scene
import app.absentmotion.ui.adaptive.AdaptiveSpace
import app.absentmotion.ui.adaptive.LocalAdaptiveSpace
import app.absentmotion.ui.camera.CameraScreen
import app.absentmotion.ui.editor.*
import app.absentmotion.ui.home.*
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskAdaptiveWindowsTest {
    private val sizes = listOf(1280 to 800, 1920 to 1080)
    private val shots = File("build/ui-shots/adaptive").also { it.mkdirs() }
    private val saved = listOf("am.data", "user.home", "am.exports").associateWith { System.getProperty(it) }

    @After fun restore() {
        saved.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        DeskScale.reset()
    }

    private fun prepare(name: String) {
        for (property in listOf("am.data", "user.home", "am.exports")) {
            val folder = File("build/adaptive-$name-${property.substringAfterLast('.')}").also { it.deleteRecursively(); it.mkdirs() }
            System.setProperty(property, folder.absolutePath)
        }
        DeskScale.reset()
        DeskAppState().settings.apply { tutorialAsk = 0; tutorialStep = null; tutorialProject = null }
    }

    private fun ComposeUiTest.settle() { mainClock.advanceTimeBy(900); waitForIdle() }

    private fun ComposeUiTest.shot(name: String, w: Int, h: Int) {
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(2f, 2f)) }
        settle()
        val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val failures = mutableSetOf<String>()
        fun inspect(node: SemanticsNode) {
            val bounds = node.boundsInWindow
            if (bounds.width > 1 && bounds.height > 1) {
                val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                val results = mutableListOf<TextLayoutResult>()
                if (action?.invoke(results) == true) results.forEach { layout ->
                    val ellipsis = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                    val lineWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
                    if (!ellipsis && (layout.multiParagraph.didExceedMaxLines || lineWidth > node.size.width * 1.05f + 2 || layout.multiParagraph.height > node.size.height * 1.05f + 2)) failures.add("${layout.layoutInput.text.text}: node=${node.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, line=$lineWidth")
                }
                val controls = node.children.filter { it.config.contains(SemanticsActions.OnClick) && it.boundsInWindow.width > 1 && it.boundsInWindow.height > 1 }
                for (i in controls.indices) for (j in i + 1 until controls.size) {
                    val overlap = controls[i].boundsInWindow.intersect(controls[j].boundsInWindow)
                    if (overlap.width > 4 && overlap.height > 4) failures.add("overlapping controls: ${controls[i].config} and ${controls[j].config}")
                }
            }
            node.children.forEach(::inspect)
        }
        roots.lastOrNull()?.let(::inspect)
        shots.mkdirs()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("${w}x$h-$name.png"))
        assertTrue("$name at ${w}x$h: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun fullPageAndQuestionFramesFollowTheirRoles() {
        for ((w, h) in sizes) {
            prepare("frames-$w")
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                var mode by mutableIntStateOf(0)
                var space: AdaptiveSpace? = null
                var enters = 0
                var closes = 0
                setContent {
                    val content: @Composable () -> Unit = {
                        val current = LocalAdaptiveSpace.current
                        SideEffect { space = current }
                        Box(Modifier.fillMaxWidth().then(if (mode >= 3) Modifier.height(96.dp) else Modifier.fillMaxSize()).testTag("content").focusable())
                    }
                    when (mode) {
                        0 -> DeskDialog("AbsentMotion", onDismiss = { closes++ }, onConfirm = { enters++ }, content = content)
                        1 -> DeskSheetHost({ closes++ }, content)
                        2 -> DeskModal({ closes++ }) { content() }
                        3 -> DeskModal({ closes++ }, 440.dp, onEnter = { enters++ }) { content() }
                        else -> DeskDialog("AbsentMotion", 440.dp, { closes++ }, height = 240.dp, content = content)
                    }
                }
                for (page in 0..2) {
                    runOnIdle { mode = page }
                    settle()
                    val bounds = onNodeWithTag("content").getBoundsInRoot()
                    if (page != 1) assertTrue("landscape content $bounds", bounds.width > bounds.height)
                    assertEquals(if (page == 1) 720f else 1100f, bounds.width.value, 1f)
                    assertTrue(space!!.wide)
                    assertEquals(bounds.width, space!!.width)
                    assertEquals(bounds.height, space!!.height)
                    shot(listOf("editor-frame", "sheet-frame", "home-frame")[page], w, h)
                }
                runOnIdle { mode = 0 }
                settle()
                onNodeWithTag("content").performSemanticsAction(SemanticsActions.RequestFocus)
                onNodeWithTag("content").performKeyInput { pressKey(Key.Enter) }
                assertEquals(1, enters)
                onNodeWithContentDescription("Close (Esc)").performClick()
                assertEquals(1, closes)
                runOnIdle { mode = 3 }
                settle()
                assertEquals(440f, onNodeWithTag("content").getBoundsInRoot().width.value, 1f)
                assertNull(space)
                onNodeWithTag("content").performSemanticsAction(SemanticsActions.RequestFocus)
                onNodeWithTag("content").performKeyInput { pressKey(Key.Enter) }
                assertEquals(2, enters)
                runOnIdle { DeskBack.back() }
                assertEquals(2, closes)
                shot("question-frame", w, h)
                runOnIdle { mode = 4 }
                settle()
                assertEquals(440f, onNodeWithTag("content").getBoundsInRoot().width.value, 1f)
                assertNull(space)
                shot("editor-question-frame", w, h)
            }
        }
    }

    @Test fun homeWindowsAtBothResolutions() {
        for ((w, h) in sizes) {
            prepare("home-$w")
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
                setContent { DeskApp(app) }
                settle()
                onAllNodesWithText("New project").onFirst().performClick()
                shot("new-project", w, h)
                onNodeWithText("Custom").performClick()
                shot("new-project-custom", w, h)
                onNodeWithText("Asset").performClick()
                shot("new-element", w, h)
                runOnIdle { DeskBack.back() }
                settle()
                for (page in SettingsPage.entries) {
                    runOnIdle { app.openSettings(page) }
                    shot("settings-${page.name.lowercase()}", w, h)
                    if (page == SettingsPage.About) {
                        onNodeWithText("Open source licenses", substring = true).performClick()
                        shot("licences", w, h)
                    }
                    runOnIdle { app.settingsOpen = false }
                    settle()
                }
                runOnIdle { app.historyFor = Triple(id, "Styles", false) }
                shot("version-history-home", w, h)
                runOnIdle { app.historyFor = null }
                settle()
                runOnIdle { app.exportingFromList = app.store.load(id)!! }
                shot("export-home", w, h)
                runOnIdle { app.exportingFromList = null }
                settle()
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                runOnIdle { editor.historyOpen = true }
                shot("version-history-editor", w, h)
                runOnIdle { editor.historyOpen = false; editor.exportOpen = true }
                shot("export-editor", w, h)
                runOnIdle { editor.exportOpen = false; editor.effectBrowserOpen = true }
                shot("effects", w, h)
                runOnIdle { editor.effectBrowserOpen = false; editor.selectedId = editor.scene.layers.first().id; editor.presetsOpen = true }
                shot("presets", w, h)
                runOnIdle { editor.presetsOpen = false }
                for ((tool, name) in listOf("compress", "download", "trim", "extract-audio", "gif").withIndex()) {
                    runOnIdle { editor.toolOpen = tool + 1 }
                    shot(name, w, h)
                    runOnIdle { editor.toolOpen = 0 }
                    settle()
                }
                runOnIdle { editor.fullColor = FullColor(0xffee6633.toInt()) { _, _ -> } }
                shot("colour", w, h)
                runOnIdle { editor.fullColor = null; editor.startFreehand() }
                shot("drawing", w, h)
                runOnIdle { editor.leaveDrawing(); editor.addText() }
                settle()
                onAllNodesWithText("Roboto Regular").onFirst().performClick()
                onNodeWithText("Show All Fonts").performClick()
                shot("fonts", w, h)
                onNodeWithContentDescription("Close (Esc)").assertIsDisplayed().performClick()
                runBlocking { app.closeEditor() }
                app.engine.close()
            }
        }
    }

    @Test fun editorSheetsAtBothResolutions() {
        for ((w, h) in sizes) {
            prepare("sheets-$w")
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
                runBlocking { app.openNow(id) }
                val state = app.editor!!
                var page by mutableIntStateOf(-1)
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.TopBar)) {
                        CompositionLocalProvider(LocalDeskEditor provides true, LocalWide provides true) {
                            val close = { page = -1 }
                            when (page) {
                                0 -> AnimationSheet(state, state.scene.layers.first().id, close)
                                1 -> TransitionSheet(state, state.scene.layers[0].id, state.scene.layers[1].id, close)
                                2 -> StretchSheet(state, state.scene.layers.take(2).map { it.id }.toSet(), close)
                                3 -> VoiceSheet(state, close)
                                4 -> CaptionsSheet(state, onClose = close)
                                5 -> DeskSoundBrowser(state, emptyList(), remember { SoundPreview(app.context) }, {}, {}, close)
                                6 -> CameraScreen(16f / 9f, close) { _, _ -> }
                                7 -> DeskProject(state, close)
                                8 -> CameraMovesSheet(state, state.scene.layers.first().id, close)
                            }
                        }
                    }
                }
                for ((index, name) in listOf("animation", "transition", "stretch", "voice", "captions", "sounds", "camera", "project-settings", "camera-moves").withIndex()) {
                    runOnIdle { page = index }
                    if (index == 3) waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Loading voices…").fetchSemanticsNodes().isEmpty() }
                    shot(name, w, h)
                    if (index == 5) for (group in listOf("Albums", "Artists", "Folders")) {
                        onNodeWithText(group).performClick()
                        shot("sounds-${group.lowercase()}", w, h)
                    }
                    runOnIdle { page = -1 }
                    settle()
                }
                runBlocking { app.closeEditor() }
                app.engine.close()
            }
        }
    }
}
