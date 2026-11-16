package app.absentmotion.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.width
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskScale
import app.absentmotion.i18n.txt
import app.absentmotion.library.ImportReport
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.VersionHistory
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AdaptiveDesktopWindowsTest {
    private val saved = System.getProperty("am.data")
    private val sizes = listOf(1280 to 800, 1920 to 1080)

    @After fun restore() {
        if (saved == null) System.clearProperty("am.data") else System.setProperty("am.data", saved)
        DeskScale.reset()
    }

    private fun prepare(name: String): DeskAppState {
        System.setProperty("am.data", File("build/adaptive-desktop-$name").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        DeskScale.reset()
        return DeskAppState().also { it.settings.tutorialAsk = 0 }
    }

    private fun ComposeUiTest.settle() { mainClock.advanceTimeBy(800); waitForIdle() }

    private fun ComposeUiTest.shot(name: String, width: Int, height: Int) {
        settle()
        val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
        assertEquals(width, image.getWidth(null))
        assertEquals(height, image.getHeight(null))
        val out = File("build/ui-shots/adaptive").also { it.mkdirs() }
        ImageIO.write(image, "png", out.resolve("desktop-$name-${width}x$height.png"))
    }

    @Test fun importReportAndNestedSaveSizeDialog() {
        for ((width, height) in sizes) runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
            val app = prepare("home-$width")
            try {
                setContent { DeskApp(app) }
                settle()
                runOnIdle { app.newProjectTick++ }
                settle()
                onAllNodesWithText("Custom").onFirst().performClick()
                settle()
                onAllNodesWithText("+ Save size").onFirst().performClick()
                shot("save-size-dialog", width, height)
                val save = onAllNodesWithText("Save size").onLast().getBoundsInRoot()
                assertTrue(save.width.value < 440f)
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
                settle()
                assertEquals(1, app.settings.savedSizes.size)
                assertNull(app.editor)
                runOnIdle { assertTrue(DeskBack.back()) }
                settle()
                onAllNodesWithText("Create project").assertCountEquals(0)
                val scene = Scene.parse(File("../testdata/scenes/styles.xml").readText())
                val id = app.store.create(scene)
                runOnIdle {
                    app.imported = listOf(ImportReport(id, scene.title, false, listOf(txt(R.string.import_complete) to listOf(txt(R.string.ok)))))
                }
                shot("import-report", width, height)
                val open = onAllNodesWithText("Open").filter(hasClickAction()).onLast().getBoundsInRoot()
                val header = onAllNodesWithText("Import complete").onFirst().getBoundsInRoot()
                assertTrue(open.right.value - header.left.value > 900f)
                onAllNodesWithContentDescription("Close").onLast().performClick()
                settle()
                assertTrue(app.imported.isEmpty())
            } finally { app.engine.close() }
        }
    }

    @Test fun versionHistoryKeepsRestoreAndDeleteConfirmationsCompact() {
        for ((width, height) in sizes) runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
            val app = prepare("versions-$width")
            val scene = Scene.parse(File("../testdata/scenes/styles.xml").readText())
            val id = app.store.create(scene)
            app.store.versions.snapshot(id, scene.toXml())
            var show by mutableStateOf(true)
            var deleted = false
            try {
                setContent {
                    if (show) VersionHistory(app.store, id, "Styles", app.engine, {}, onDelete = { deleted = true }, damaged = true) { show = false }
                }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Restore").fetchSemanticsNodes().isNotEmpty() }
                shot("damaged-version-history", width, height)
                onAllNodesWithText("Restore").filter(hasClickAction()).onFirst().performClick()
                shot("restore-confirmation", width, height)
                val restore = onAllNodesWithText("Restore this version?").onLast().getBoundsInRoot()
                assertTrue(restore.width.value < 420f)
                onAllNodesWithText("Cancel").onLast().performClick()
                onAllNodesWithText("Delete").filter(hasClickAction()).onFirst().performClick()
                shot("delete-confirmation", width, height)
                onAllNodesWithText("Cancel").onLast().performClick()
                assertFalse(deleted)
                onAllNodesWithText("Close").filter(hasClickAction()).onLast().performClick()
                settle()
                assertFalse(show)
            } finally { runBlocking { app.closeEditor() }; app.engine.close() }
        }
    }
}
