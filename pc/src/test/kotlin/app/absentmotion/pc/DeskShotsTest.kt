package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.resetValue
import app.absentmotion.model.PropRef
import app.absentmotion.model.formatFloats
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
        app.absentmotion.desk.DeskLibrary.link(home.resolve("Pictures"))
        app.absentmotion.desk.DeskLibrary.link(home.resolve("Movies"))
        val exports = File("build/desk-shots-exports").also { it.deleteRecursively() }
        System.setProperty("am.exports", exports.absolutePath)
        runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
            val app = DeskAppState()
            val shots = File("build/desk-shots").also { it.mkdirs() }
            fun shot(name: String) { waitForIdle(); ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve(name)) }
            setContent { DeskApp(app) }
            shot("01-home-empty.png")
            onAllNodesWithText("New project", substring = false).onFirst().performClick()
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
            onAllNodesWithText("Media", substring = false).filter(hasClickAction()).onFirst().assertExists()
            mainClock.advanceTimeBy(3000)
            Thread.sleep(3000)
            mainClock.advanceTimeBy(1000)
            shot("06b-add-gallery.png")
            runOnIdle { editor.addOpen = false; editor.exportOpen = true }
            mainClock.advanceTimeBy(1000)
            shot("07-export.png")
            fun exportAndShoot(prefix: String) {
                onAllNodesWithText("Export").onLast().performClick()
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
            runOnIdle { editor.exportOpen = false }
            runBlocking { app.closeEditor() }
            val repo = File("..").canonicalFile
            val mediaXml = File("../testdata/scenes/media.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
            val mediaId = app.store.create(Scene.parse(mediaXml))
            runBlocking { app.openNow(mediaId) }
            runOnIdle { app.editor!!.frame = 45 }
            mainClock.advanceTimeBy(2000)
            Thread.sleep(3000)
            mainClock.advanceTimeBy(1000)
            shot("11-media-editor.png")
            runOnIdle { app.editor!!.canvasZoom = 2f }
            mainClock.advanceTimeBy(500); Thread.sleep(1500); mainClock.advanceTimeBy(500)
            shot("12-zoom-200.png")
            runOnIdle { app.editor!!.frame = 50 }
            mainClock.advanceTimeBy(500); Thread.sleep(1500); mainClock.advanceTimeBy(500)
            shot("13-zoom-200-moved.png")
            runOnIdle { app.editor!!.canvasZoom = 0.5f }
            mainClock.advanceTimeBy(500); Thread.sleep(1500); mainClock.advanceTimeBy(500)
            shot("14-zoom-50.png")
            runOnIdle { app.editor!!.canvasZoom = 1f; app.editor!!.selectedId = 8004L }
            for (factor in listOf(2f, 3f)) {
                runOnIdle { app.editor!!.edit { sc -> sc.update(8004L) { l -> l.resetValue(PropRef.Scale, formatFloats(floatArrayOf(factor, factor))) } } }
                mainClock.advanceTimeBy(500); Thread.sleep(1500); mainClock.advanceTimeBy(500)
                shot("15-video-scale-${factor.toInt()}.png")
            }
            runOnIdle { app.editor!!.playing = true }
            Thread.sleep(700); mainClock.advanceTimeBy(700)
            shot("16-video-scaled-playing.png")
            runOnIdle { app.editor!!.playing = false }
            val written = exports.walk().filter { it.isFile }.map { it.relativeTo(exports).path }.sorted().toList()
            println("exported: $written")
            check(written.any { it.endsWith(".mp4") } && written.any { it.endsWith(".gif") }) { "missing exports: $written" }
        }
    }
}
