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

private fun DeskBackPress() { app.absentmotion.desk.DeskBack.back() }

@OptIn(ExperimentalTestApi::class)
class DeskShotsTest {
    @Test fun phoneUiOnDesktop() {
        val data = File("build/desk-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val home = File("build/desk-home").also { it.deleteRecursively(); it.resolve("Pictures/Trip").mkdirs(); it.resolve("Movies").mkdirs() }
        File("../testdata/media").listFiles().orEmpty().filter { it.isFile }.forEach { f -> f.copyTo(home.resolve(if (f.extension == "mp4") "Movies/${f.name}" else "Pictures/Trip/${f.name}")) }
        System.setProperty("user.home", home.absolutePath)
        runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
            val app = DeskAppState()
            val shots = File("build/desk-shots").also { it.mkdirs() }
            fun shot(name: String) { waitForIdle(); ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve(name)) }
            setContent { DeskApp(app) }
            shot("01-home-empty.png")
            onNodeWithText("New project", substring = false).performClick()
            mainClock.advanceTimeBy(1500)
            shot("01b-new-project.png")
            DeskBackPress()
            mainClock.advanceTimeBy(1000)
            onAllNodesWithText("Settings", substring = false).filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(1500)
            shot("01c-settings.png")
            DeskBackPress()
            mainClock.advanceTimeBy(1000)
            val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
            runOnIdle { app.projectsChanged++ }
            shot("02-home-project.png")
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(2000)
            shot("03-editor.png")
            val renders0 = app.engine.renders
            val t0 = System.nanoTime()
            var total = 0.0
            for (i in 1..60) {
                runOnIdle { app.editor!!.frame = i % 30 }
                val before = app.engine.renders
                val start = System.nanoTime()
                while (app.engine.renders == before && System.nanoTime() - start < 2_000_000_000) Thread.sleep(1)
                total += app.engine.lastRenderMs
            }
            val seconds = (System.nanoTime() - t0) / 1e9
            println("PREVIEW frames=${app.engine.renders - renders0} seconds=$seconds fps=${(app.engine.renders - renders0) / seconds} renderMs=${total / 60}")
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
            runOnIdle { editor.effectBrowserOpen = false; editor.panel = EditorPanel.Tools; editor.addOpen = true }
            mainClock.advanceTimeBy(1000)
            onNodeWithText("Media", substring = false).performClick()
            mainClock.advanceTimeBy(3000)
            Thread.sleep(3000)
            mainClock.advanceTimeBy(1000)
            shot("06b-add-gallery.png")
            runOnIdle { editor.addOpen = false; editor.exportOpen = true }
            mainClock.advanceTimeBy(1000)
            shot("07-export.png")
        }
    }
}
