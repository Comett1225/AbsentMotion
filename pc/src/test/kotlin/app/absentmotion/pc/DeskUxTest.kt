package app.absentmotion.pc

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskLibrary
import app.absentmotion.desk.DeskScale
import app.absentmotion.export.ExportKind
import app.absentmotion.model.Scene
import app.absentmotion.ui.export.DeskExportPreview
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DeskUxTest {
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/ux").also { it.mkdirs() }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/ux-$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/ux-$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/ux-$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskScale.set(100)
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.space() {
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(50)
    }

    @Test fun spaceAlwaysTogglesPlayback() {
        prepare("space")
        DeskLibrary.close()
        DeskLibrary.link(File("../testdata/media").canonicalFile)
        runSkikoComposeUiTest(size = Size(1728f, 1080f)) {
            val app = DeskAppState()
            val focus = androidx.compose.ui.focus.FocusRequester()
            setContent { Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { DeskKeys.handle(app, it) }) { DeskApp(app) } }
            runOnIdle { focus.requestFocus() }
            val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            fun clickRail(name: String) {
                onAllNodesWithText(name, substring = false).filter(hasClickAction()).onFirst().performClick()
                mainClock.advanceTimeBy(250)
            }
            mainClock.autoAdvance = false
            fun toggles(where: String) {
                runOnIdle { assertFalse("$where start", editor.playing) }
                space()
                runOnIdle { assertTrue("$where first space", editor.playing) }
                mainClock.advanceTimeBy(100)
                space()
                runOnIdle { assertFalse("$where second space", editor.playing) }
            }
            clickRail("Audio")
            toggles("audio rail")
            clickRail("Media")
            toggles("media rail")
            onAllNodesWithText("test-image", substring = true).filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(250)
            toggles("media tile")
            onAllNodesWithContentDescription("Project settings").onFirst().performClick()
            mainClock.advanceTimeBy(250)
            runOnIdle { assertTrue(DeskModals.open > 0) }
            DeskBack.back()
            mainClock.advanceTimeBy(300)
            toggles("after dialog")
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun homeSelectionIsInstant() {
        prepare("home")
        val seed = DeskAppState()
        listOf("styles.xml", "text.xml").forEach { seed.store.create(Scene.parse(repo.resolve("testdata/scenes/$it").readText())) }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            runOnIdle { app.projectsChanged++ }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Styles").fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1500)
            val accent = Am.HomeAccent
            fun accentPixels(): Int {
                val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                var n = 0
                for (y in 70 until image.height) for (x in 0 until image.width) {
                    val p = image.getRGB(x, y)
                    if (Math.abs((p shr 16 and 255) - (accent.red * 255).toInt()) < 6 && Math.abs((p shr 8 and 255) - (accent.green * 255).toInt()) < 6 && Math.abs((p and 255) - (accent.blue * 255).toInt()) < 6) n++
                }
                return n
            }
            val before = accentPixels()
            onAllNodesWithText("Styles").onFirst().performClick()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val after = accentPixels()
            shot("home-selected")
            assertTrue("outline after two frames: $before -> $after", after > before + 200)
        }
    }

    @Test fun toolsTab() {
        prepare("tools")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(800)
            onNodeWithText("Tools").performClick()
            mainClock.advanceTimeBy(600)
            onNodeWithText("Compress").assertExists()
            shot("tools-tab")
            onNodeWithText("Compress").performClick()
            mainClock.advanceTimeBy(800)
            shot("tools-compress")
        }
    }

    @Test fun exportViewer() {
        prepare("viewer")
        val video = File("../testdata/media/test-video.mp4").canonicalFile
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val focus = androidx.compose.ui.focus.FocusRequester()
            val keyFocus = androidx.compose.ui.focus.FocusRequester()
            setContent { Box(Modifier.fillMaxSize().focusRequester(keyFocus).focusable().onPreviewKeyEvent { if (it.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else if (it.key == Key.Escape) DeskBack.back() else DeskKeys.viewer?.invoke(it) == true }) { DeskExportPreview(ExportKind.Video, listOf(Uri.fromFile(video))) } }
            runOnIdle { keyFocus.requestFocus() }
            mainClock.advanceTimeBy(500)
            onAllNodesWithContentDescription("Close").assertCountEquals(0)
            onAllNodes(isRoot()).onFirst().performMouseInput { click(androidx.compose.ui.geometry.Offset(400f, 300f)) }
            mainClock.advanceTimeBy(500)
            onNodeWithContentDescription("Close").assertExists()
            runOnIdle { assertEquals(1, DeskModals.open) }
            Thread.sleep(1500)
            mainClock.advanceTimeBy(500)
            shot("viewer-video")
            val wasPlaying = onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
            runOnIdle { assertTrue(DeskKeys.viewer!!.invoke(androidx.compose.ui.input.key.KeyEvent(Key.Spacebar, androidx.compose.ui.input.key.KeyEventType.KeyDown))) }
            mainClock.advanceTimeBy(300)
            onNodeWithContentDescription(if (wasPlaying) "Play" else "Pause").assertExists()
            runOnIdle { assertTrue(DeskBack.back()) }
            mainClock.advanceTimeBy(300)
            onAllNodesWithContentDescription("Close").assertCountEquals(0)
            runOnIdle { assertEquals(0, DeskModals.open) }
        }
    }

    @Test fun compressWorksOnDesktop() = runBlocking {
        prepare("compress")
        val context = android.content.Desk.context
        val results = mutableListOf<String>()
        for (name in listOf("test-video.mp4", "test-image.jpg")) {
            val picked = app.absentmotion.tools.readPicked(context, Uri.fromFile(File("../testdata/media/$name").canonicalFile))
            org.junit.Assert.assertNotNull("read $name", picked)
            val source = picked!!.source
            val done = if (source.video) app.absentmotion.tools.compressVideo(context, picked, app.absentmotion.tools.planVideo(source, app.absentmotion.tools.CompressLevel.High)) { _, _ -> }
                else app.absentmotion.tools.compressPicture(context, picked, app.absentmotion.tools.planPicture(app.absentmotion.tools.CompressLevel.High))
            results += "$name -> ${done.file.name} ${done.file.length()}"
            assertTrue(done.file.length() > 0)
            results += "saved " + app.absentmotion.tools.saveToGallery(context, done)
            app.absentmotion.tools.shareCompressed(context, done)
        }
        println("COMPRESS " + results)
    }
}
