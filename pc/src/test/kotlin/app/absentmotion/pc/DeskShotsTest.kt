package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskShotsTest {
    @Test fun phoneUiOnDesktop() {
        val data = File("build/desk-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val exports = File("build/desk-shots-exports").also { it.deleteRecursively() }
        System.setProperty("am.exports", exports.absolutePath)
        runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
            val app = DeskAppState()
            val shots = File("build/desk-shots").also { it.mkdirs() }
            fun shot(name: String) { waitForIdle(); ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve(name)) }
            setContent { DeskApp(app) }
            shot("01-home-empty.png")
            val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
            runOnIdle { app.projectsChanged++ }
            shot("02-home-project.png")
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(2000)
            shot("03-editor.png")
            val editor = app.editor!!
            runOnIdle { editor.selectedId = editor.scene.layers.last().id }
            mainClock.advanceTimeBy(1000)
            shot("04-layer-selected.png")
            runOnIdle { editor.panel = EditorPanel.Effects }
            mainClock.advanceTimeBy(1000)
            shot("05-effects-panel.png")
            runOnIdle { editor.effectBrowserOpen = true }
            mainClock.advanceTimeBy(2000)
            shot("06-effect-browser.png")
            runOnIdle { editor.effectBrowserOpen = false; editor.panel = EditorPanel.Tools; editor.exportOpen = true }
            mainClock.advanceTimeBy(1000)
            shot("07-export.png")
            fun exportAndShoot(prefix: String) {
                onAllNodesWithText("Export").onLast().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Saved to storage").fetchSemanticsNodes().isEmpty() }
                mainClock.advanceTimeBy(300)
                shot("$prefix-running.png")
                waitUntil(timeoutMillis = 120_000) { onAllNodesWithText("Saved to storage").fetchSemanticsNodes().isNotEmpty() }
                mainClock.advanceTimeBy(1500)
                Thread.sleep(800)
                mainClock.advanceTimeBy(200)
                shot("$prefix-done.png")
                onNodeWithText("OK").performClick()
                mainClock.advanceTimeBy(1000)
            }
            exportAndShoot("08-video")
            onNodeWithText("GIF").performClick()
            mainClock.advanceTimeBy(1000)
            shot("09-gif-selected.png")
            exportAndShoot("10-gif")
            val written = exports.walk().filter { it.isFile }.map { it.relativeTo(exports).path }.sorted().toList()
            println("exported: $written")
            check(written.any { it.endsWith(".mp4") } && written.any { it.endsWith(".gif") }) { "missing exports: $written" }
        }
    }
}
