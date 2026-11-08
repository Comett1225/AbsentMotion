package app.absentmotion.pc

import app.absentmotion.ui.home.WhatsNew
import app.absentmotion.ui.home.WhatsNewNotes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskExportMini
import app.absentmotion.desk.DeskInstance
import app.absentmotion.desk.DeskNotify
import app.absentmotion.desk.DeskOpen
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.Strings
import app.absentmotion.export.DeskExports
import app.absentmotion.export.ExportRun
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.renameLayer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.handleTurn
import app.absentmotion.ui.editor.wheelFactor
import app.absentmotion.ui.editor.wheelTurn
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import app.absentmotion.ui.tutorial.Tutorial
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskParityRoundTest {
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/parity-round").also { it.mkdirs() }
    private val sizes = listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)
    private val saved = listOf("am.data", "user.home", "am.exports", "am.onboarding", "am.fontOffer", "am.whatsNew").associateWith { System.getProperty(it) }

    @org.junit.After fun restoreProperties() {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        Tutorial.tour = null
    }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskAppState().settings.apply { tutorialAsk = 0; onboarding = ONBOARDING_DONE; tutorialStep = null; tutorialProject = null }
    }

    private fun scene(name: String): Scene {
        val xml = repo.resolve("testdata/scenes/$name").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        return Scene.parse(xml)
    }

    private fun session(width: Int, height: Int, body: ComposeUiTest.(DeskAppState) -> Unit) = runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
        val app = DeskAppState()
        setContent { DeskApp(app) }
        body(app)
        runBlocking { app.closeEditor() }
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.settle(ms: Long = 500) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun ComposeUiTest.text(id: Int) = Strings.get(id)

    private fun ComposeUiTest.openEditor(app: DeskAppState, name: String): app.absentmotion.editor.EditorState {
        val id = app.store.create(scene(name))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        settle(800)
        return app.editor!!
    }

    @Test fun whatsNewShowsOnceAfterAnUpdate() {
        val key = WhatsNewNotes.key(app.absentmotion.desk.DeskInfo.version)!!
        val notes = WhatsNewNotes.notesFor(key, null) { repo.resolve("shared/assets/whats-new/$it.md").isFile }!!
        val title = WhatsNew.parse(repo.resolve("shared/assets/whats-new/$notes.md").readText()).title
        System.clearProperty("am.whatsNew")
        prepare("whatsnew")
        session(1280, 800) { app ->
            settle(900)
            onNodeWithText(title).assertExists()
            shot("1280-whats-new")
            onAllNodesWithText(text(R.string.action_continue)).onFirst().performClick()
            settle()
            onNodeWithText(title).assertDoesNotExist()
            assertEquals(key, app.settings.whatsNewSeen)
        }
        session(1280, 800) {
            settle(900)
            onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test fun firstRunWelcomeThenTheTourOffer() {
        System.setProperty("am.onboarding", "language")
        try {
            for ((w, h) in sizes) {
                prepare("welcome")
                DeskAppState().settings.onboarding = 0
                Tutorial.offerDismissed = false
                session(w, h) { app ->
                    settle()
                    onNodeWithText(text(R.string.desk_welcome_title)).assertExists()
                    shot("$w-welcome-language")
                    onAllNodesWithText(text(R.string.action_continue)).onFirst().performClick()
                    settle()
                    onNodeWithText(text(R.string.desk_welcome_updates_title)).assertExists()
                    shot("$w-welcome-updates")
                    onNodeWithText(text(R.string.desk_welcome_start)).performClick()
                    settle(900)
                    assertEquals(ONBOARDING_DONE, app.settings.onboarding)
                    assertEquals(Tutorial.ASK, app.settings.tutorialAsk)
                    onNodeWithText(text(R.string.tutorial_offer_title)).assertExists()
                    shot("$w-welcome-offer")
                    onNodeWithText(text(R.string.tutorial_offer_later)).performClick()
                    settle()
                    onNodeWithText(text(R.string.tutorial_offer_title)).assertDoesNotExist()
                }
            }
        } finally {
            System.setProperty("am.onboarding", "done")
        }
    }

    @Test fun theGuidedTourRunsOnTheDesktopWorkspace() {
        for ((w, h) in listOf(1280 to 800, 1024 to 700, 1728 to 1080)) {
            prepare("tour")
            DeskAppState().settings.tutorialAsk = Tutorial.ASK
            Tutorial.offerDismissed = false
            Tutorial.tour = null
            session(w, h) { app ->
                settle(900)
                onNodeWithText(text(R.string.tutorial_offer_start)).performClick()
                waitUntil(timeoutMillis = 30_000) { app.editor != null && Tutorial.tour != null }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                settle(900)
                val tour = Tutorial.tour!!
                val seen = ArrayList<String>()
                var guard = 0
                while (Tutorial.tour != null && guard++ < 40) {
                    val step = Tutorial.tour!!.step
                    seen += step.id
                    shot("$w-tour-${"%02d".format(seen.size)}-${step.id}")
                    val label = if (tour.canDoIt) text(R.string.tutorial_do_it) else if (step.id == "finish") text(R.string.tutorial_finish) else text(R.string.tutorial_next)
                    onAllNodesWithText(label).onFirst().performClick()
                    settle(900)
                    waitUntil(timeoutMillis = 20_000) { app.editor?.addingMedia != true }
                }
                assertTrue("steps $seen", seen.size >= 15 && seen.first() == "welcome" && seen.last() == "finish")
                assertNull(Tutorial.tour)
                onNodeWithText(text(R.string.tutorial_delete_title)).assertExists()
                shot("$w-tour-finished")
                onNodeWithText(text(R.string.tutorial_delete_keep)).performClick()
                settle()
            }
        }
    }

    @Test fun savingFailureIsAskedBeforeClosing() {
        prepare("save-failed")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            val folders = app.context.filesDir.walkTopDown().filter { it.isDirectory || DeskOs.windows }.toList()
            folders.forEach { it.setWritable(false) }
            try {
                runOnIdle { editor.renameLayer(editor.scene.layers.first().id, "Renamed") }
                runBlocking { editor.saver.flush().get() }
                assertTrue(editor.saver.failing)
                app.closeSoon()
                waitUntil(timeoutMillis = 20_000) { app.saveFailed }
                settle()
                assertNotNull(app.editor)
                onNodeWithText(text(R.string.home_save_failed_retry)).assertExists()
                shot("save-failed-dialog")
                onNodeWithText(text(R.string.home_save_failed_retry)).performClick()
                waitUntil(timeoutMillis = 20_000) { app.saveFailed }
                assertNotNull(app.editor)
                onNodeWithText(text(R.string.home_save_failed_leave)).performClick()
                waitUntil(timeoutMillis = 20_000) { app.editor == null }
                assertFalse(app.saveFailed)
            } finally {
                folders.forEach { it.setWritable(true) }
                if (DeskOs.windows) File(app.context.filesDir, "recovery").deleteRecursively()
            }
        }
    }

    @Test fun missingGoogleFontsAreOffered() {
        prepare("fonts")
        System.setProperty("am.fontOffer", "on")
        session(1280, 800) { app ->
            openEditor(app, "text.xml")
            waitUntil(timeoutMillis = 20_000) { app.fontOffer != null }
            settle()
            onNodeWithText(text(R.string.desk_fonts_missing_title)).assertExists()
            shot("fonts-offer")
            onNodeWithText(text(R.string.update_not_now)).performClick()
            settle()
            assertNull(app.fontOffer)
            onNodeWithText(text(R.string.desk_fonts_missing_title)).assertDoesNotExist()
        }
    }

    @Test fun filesFromTheSystemOpenOrImport() {
        prepare("open-with")
        val sceneFile = File("build/open-with-files/sample.xml").also { it.parentFile.mkdirs(); it.writeText(repo.resolve("testdata/scenes/styles.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")) }
        val picture = repo.resolve("testdata/media/test-image.jpg")
        assertEquals(listOf(File("/a/b.absproj")), DeskOpen.fromArguments(arrayOf("--warmup", "/a/b.absproj")).filter { it.name.endsWith("proj") })
        assertTrue(DeskOpen.isProject(File("x/Name.AbsProj")) && DeskOpen.isProject(sceneFile) && !DeskOpen.isProject(picture))
        session(1280, 800) { app ->
            settle()
            val before = app.store.list().size
            DeskOpen.offer(listOf(sceneFile))
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            assertEquals(before + 1, app.store.list().size)
            runBlocking { app.closeEditor() }
            settle()
            DeskOpen.offer(listOf(picture))
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            waitUntil(timeoutMillis = 30_000) { app.editor!!.scene.layers.isNotEmpty() }
            assertEquals(before + 2, app.store.list().size)
            assertEquals("test-image", app.editor!!.project.title)
            shot("open-with-media")
        }
    }

    @Test fun anInstanceForwardsFilesToTheRunningOne() {
        prepare("instance")
        if (DeskOs.mac) return
        val got = java.util.concurrent.LinkedBlockingQueue<List<File>>()
        DeskInstance.serve { got.add(it) }
        val file = File("build/instance-file.absproj").also { it.writeText("x") }
        assertTrue(DeskInstance.forward(listOf(file)))
        assertEquals(listOf(file.absoluteFile), got.poll(3, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun anExportKeepsRunningWhenTheEditorCloses() {
        prepare("background-export")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.exportOpen = true }
            settle(900)
            shot("export-dialog")
            onAllNodesWithText(text(R.string.export_title)).onFirst().assertExists()
            onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
            waitUntil(timeoutMillis = 30_000) { ExportRun.running || DeskExports.finish != null }
            if (ExportRun.running) {
                onAllNodesWithText(text(R.string.desk_export_hide)).onFirst().assertExists()
                shot("export-running")
                onAllNodesWithText(text(R.string.desk_export_hide)).onFirst().performClick()
                settle()
                assertFalse(editor.exportOpen)
                val started = System.nanoTime()
                runBlocking { app.closeEditor() }
                assertNull(app.editor)
                shot("export-running-home")
                println("closing the editor during the export took ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            waitUntil(timeoutMillis = 120_000) { DeskExports.finish != null }
            assertEquals(true, DeskExports.finish!!.ok)
            assertTrue(DeskExports.finish!!.files.single().toFile()!!.length() > 1000)
            settle()
            shot("export-finished-home")
        }
    }

    @Test fun quittingDuringAnExportAsksFirst() {
        prepare("quit-ask")
        session(1280, 800) { app ->
            openEditor(app, "styles.xml")
            ExportRun.begin(app.context, R.string.export_running_video) {}
            ExportRun.progress = 0.42f
            try {
                settle()
                shot("export-pill")
                runOnIdle { app.requestQuit() }
                settle()
                onNodeWithText(text(R.string.desk_quit_title)).assertExists()
                shot("quit-ask")
                onNodeWithText(text(R.string.desk_quit_keep)).performClick()
                waitUntil(timeoutMillis = 20_000) { app.hidden }
                assertNull(app.editor)
            } finally {
                ExportRun.end(true)
            }
        }
        runSkikoComposeUiTest(size = Size(460f, 220f)) {
            val app = DeskAppState()
            ExportRun.begin(app.context, R.string.export_running_video) {}
            ExportRun.progress = 0.6f
            setContent { DeskExportMini(app) }
            settle()
            shot("export-mini-running")
            ExportRun.end(true)
            settle()
            shot("export-mini-finished")
        }
        assertTrue(DeskNotify.macCommand("T", "x").containsAll(listOf("osascript", "display notification (item 1 of argv) with title (item 2 of argv)")))
    }

    @Test fun wheelAndHandleMathScalesAndTurns() {
        assertTrue(wheelFactor(-1f) > 1f && wheelFactor(1f) < 1f)
        assertEquals(1f, wheelFactor(0f), 0f)
        assertEquals(1f / wheelFactor(-3f), wheelFactor(3f), 0.0001f)
        assertEquals(-5f, wheelTurn(1f), 0f)
        assertEquals(90f, handleTurn(Offset(10f, 0f), Offset(0f, -10f)), 0.01f)
        assertEquals(-90f, handleTurn(Offset(10f, 0f), Offset(0f, 10f)), 0.01f)
        assertEquals(0f, handleTurn(Offset(0f, 0f), Offset(0f, 10f)), 0f)
    }

    @Test fun altScrollScalesAndTurnsTheSelectedLayerAsOneUndoStep() {
        prepare("wheel")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            val layer = editor.scene.layers.first { it.kind != "audio" }
            runOnIdle { editor.selectedId = layer.id; editor.panel = EditorPanel.Tools }
            settle()
            val bounds = DeskPreviewBounds.bounds
            val centre = Offset(bounds.center.x, bounds.center.y)
            val scaleBefore = editor.scene.layer(layer.id)!!.scale[0]
            val rotationBefore = editor.scene.layer(layer.id)!!.rotation
            val undoBefore = editor.canUndo
            repeat(3) {
                onRoot().performMultiModalInput {
                    mouse { moveTo(centre) }
                    key { keyDown(Key.AltLeft) }
                    mouse { scroll(-1f) }
                    key { keyUp(Key.AltLeft) }
                }
            }
            val scaled = editor.scene.layer(layer.id)!!.scale[0]
            assertTrue("scale $scaleBefore -> $scaled", scaled > scaleBefore * 1.1f)
            mainClock.advanceTimeBy(900)
            waitForIdle()
            repeat(3) {
                onRoot().performMultiModalInput {
                    mouse { moveTo(centre) }
                    key { keyDown(Key.ShiftLeft); keyDown(Key.AltLeft) }
                    mouse { scroll(-1f) }
                    key { keyUp(Key.AltLeft); keyUp(Key.ShiftLeft) }
                }
            }
            val turned = editor.scene.layer(layer.id)!!.rotation
            assertTrue("rotation $rotationBefore -> $turned", turned > rotationBefore + 10f)
            mainClock.advanceTimeBy(900)
            waitForIdle()
            shot("wheel-turned")
            editor.undo()
            assertEquals(rotationBefore, editor.scene.layer(layer.id)!!.rotation, 0.001f)
            assertEquals(scaled, editor.scene.layer(layer.id)!!.scale[0], 0.001f)
            editor.undo()
            assertEquals(scaleBefore, editor.scene.layer(layer.id)!!.scale[0], 0.001f)
            assertEquals(undoBefore, editor.canUndo)
        }
    }
}
