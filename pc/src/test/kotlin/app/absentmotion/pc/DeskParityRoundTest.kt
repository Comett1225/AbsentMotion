package app.absentmotion.pc

import app.absentmotion.ui.home.WhatsNew
import app.absentmotion.ui.home.WhatsNewCard
import app.absentmotion.ui.home.WhatsNewNotes
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Density
import app.absentmotion.export.ExportName
import app.absentmotion.export.StorageStatus
import app.absentmotion.export.sequenceFrameName
import app.absentmotion.ui.export.ExportBar
import app.absentmotion.ui.export.FileNameField
import app.absentmotion.ui.export.FormatPage
import app.absentmotion.ui.export.InfoBlock
import app.absentmotion.ui.theme.Am
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskExportMini
import app.absentmotion.desk.DeskInstance
import app.absentmotion.desk.DeskNotify
import app.absentmotion.desk.DeskOpen
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.Strings
import app.absentmotion.ai.AiSmooth
import app.absentmotion.export.DeskExports
import app.absentmotion.export.ExportAudio
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportPrefs
import app.absentmotion.export.ExportRecord
import app.absentmotion.export.ExportRun
import app.absentmotion.export.ExportTimes
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.exportSize
import app.absentmotion.export.frameCount
import app.absentmotion.export.SmoothLevel
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.renameLayer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.handleTurn
import app.absentmotion.ui.editor.wheelFactor
import app.absentmotion.ui.editor.wheelTurn
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import app.absentmotion.ui.tutorial.Tutorial
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.absentmotion.export.CommunityThumbnail
import androidx.compose.ui.test.performScrollTo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test fun whatsNewKeepsTheSectionAndGroupHeadersPinned() {
        val notes = WhatsNew.parse(repo.resolve("shared/assets/whats-new/5.0.3.md").readText())
        runSkikoComposeUiTest(size = Size(420f, 860f)) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A3A40)).padding(horizontal = 16.dp, vertical = 24.dp), Alignment.Center) {
                    WhatsNewCard(notes, "Continue", { null }, {})
                }
            }
            onNodeWithText("Bug fixes").performClick()
            settle()
            onNodeWithText("Android update").assertIsDisplayed()
            shot("420-whats-new-1-before-scroll")
            val list = onNode(hasScrollAction())
            list.performScrollToNode(hasText("Undo and redo"))
            list.performMouseInput { scroll(3f) }
            settle()
            shot("420-whats-new-2-pinned")
            val view = list.getBoundsInRoot()
            val head = onNodeWithText("Bug fixes").assertIsDisplayed().getBoundsInRoot()
            onAllNodesWithText("Editor").assertCountEquals(1)
            val group = onNodeWithText("Editor").assertIsDisplayed().getBoundsInRoot()
            onNodeWithText("Android update").assertDoesNotExist()
            assertEquals(view.top.value, head.top.value, 1f)
            assertTrue("${group.top} under ${head.bottom}", group.top >= head.bottom && group.top < head.bottom + 16.dp)
            onNodeWithText("Bug fixes").performClick()
            settle(800)
            shot("420-whats-new-3-collapsed")
            onNodeWithText("Undo and redo").assertDoesNotExist()
            onNodeWithText("Editor").assertDoesNotExist()
            val after = onNodeWithText("Bug fixes").assertIsDisplayed().getBoundsInRoot()
            assertTrue("$after in $view", after.top >= view.top && after.bottom <= view.bottom)
            onNodeWithText("Continue").assertIsDisplayed()
        }
    }

    @Test fun firstRunWelcomeThenTheTourOffer() {
        System.setProperty("am.onboarding", "language")
        try {
            for ((w, h) in sizes) {
                prepare("welcome")
                DeskAppState().settings.onboarding = 0
                DeskAppState().settings.lookChosen = false
                Tutorial.offerDismissed = false
                session(w, h) { app ->
                    settle()
                    onNodeWithText(text(R.string.desk_welcome_title)).assertExists()
                    shot("$w-welcome-language")
                    onAllNodesWithText(text(R.string.action_continue)).onFirst().performClick()
                    settle()
                    onNodeWithText(text(R.string.theme_select)).assertExists()
                    shot("$w-welcome-look")
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
        System.setProperty("am.fontOffer", "offline")
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
            if (ExportRun.running) runCatching {
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
            }.onFailure { if (DeskExports.finish == null) throw it }
            waitUntil(timeoutMillis = 120_000) { DeskExports.finish != null }
            assertEquals(true, DeskExports.finish!!.ok)
            assertTrue(DeskExports.finish!!.files.single().toFile()!!.length() > 1000)
            settle()
            shot("export-finished-home")
        }
    }

    private fun ComposeUiTest.timeLeft(percent: String): Int? {
        val row = onNodeWithText(percent).onParent().onChildren().fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        return row.getOrNull(row.indexOf(percent) + 1)?.split(":")?.takeIf { it.size == 2 }?.let { (minutes, seconds) -> minutes.toInt() * 60 + seconds.toInt() }
    }

    private fun ComposeUiTest.startAiExport() {
        onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text(R.string.ai_download_and_start)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(text(R.string.ai_download_and_start)).performClick()
    }

    @Test fun theAiSmoothDownloadShowsBeforeTheExportStarts() {
        prepare("ai-smooth-download")
        val fetch = AiSmooth.fetch
        val mix = ExportAudio.mixFor
        val fetched = CompletableDeferred<Unit>()
        val held = CountDownLatch(1)
        val stuck = CountDownLatch(1)
        AiSmooth.fetch = { _, _, progress ->
            for (k in 1..12) { delay(200); progress(k / 32f) }
            fetched.await()
            progress(1f)
        }
        try {
            session(1280, 800) { app ->
                val editor = openEditor(app, "styles.xml")
                val prefs = ExportPrefs(app.context)
                val video = prefs.load(ExportKind.Video, editor.scene).copy(interpolation = FrameInterpolation.Ai, interpolationLevel = SmoothLevel.Low)
                prefs.save(ExportKind.Video, video)
                val (width, height) = exportSize(editor.scene, video.shortSide, even = true)
                ExportTimes(app.context).record(ExportRecord(video.codec, width, height, video.fps, frameCount(editor.scene, video.fps), 120_000, FrameInterpolation.Ai))
                DeskExports.engine()
                val sound = ExportAudio.mixFor
                ExportAudio.mixFor = { scene, rate -> held.await(60, TimeUnit.SECONDS); sound?.invoke(scene, rate) }
                runOnIdle { editor.exportOpen = true }
                settle(900)
                startAiExport()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("37%").fetchSemanticsNodes().isNotEmpty() && timeLeft("37%") != null }
                onNodeWithText(text(R.string.export_running_ai_smooth)).assertExists()
                onAllNodesWithText(text(R.string.export_running_video)).assertCountEquals(0)
                assertEquals(R.string.export_running_ai_smooth, DeskExports.title)
                assertEquals(0.375f, DeskExports.fraction)
                assertTrue(timeLeft("37%")!! <= 5)
                val row = onNodeWithText("37%").getBoundsInRoot().top
                shot("ai-smooth-downloading")
                Thread.sleep(4_000)
                fetched.complete(Unit)
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text(R.string.export_running_video)).fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("0%").fetchSemanticsNodes().isNotEmpty() && timeLeft("0%") != null }
                onAllNodesWithText(text(R.string.export_running_ai_smooth)).assertCountEquals(0)
                assertEquals(R.string.export_running_video, DeskExports.title)
                assertEquals(0f, DeskExports.fraction)
                assertEquals(row, onNodeWithText("0%").getBoundsInRoot().top)
                assertTrue("${timeLeft("0%")} s left", timeLeft("0%")!! >= 117)
                shot("ai-smooth-exporting")
                onAllNodesWithText(text(R.string.cancel)).filter(hasClickAction()).onFirst().performClick()
                held.countDown()
                waitUntil(timeoutMillis = 60_000) { !ExportRun.running }
                settle()
                AiSmooth.fetch = { _, _, progress ->
                    progress(0.25f)
                    withContext(Dispatchers.IO) {
                        stuck.await(60, TimeUnit.SECONDS)
                        error(text(R.string.ai_model_download_failed))
                    }
                }
                startAiExport()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("25%").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(text(R.string.cancel)).filter(hasClickAction()).onFirst().performClick()
                settle()
                stuck.countDown()
                waitUntil(timeoutMillis = 30_000) { !ExportRun.running }
                settle()
                onAllNodesWithText(text(R.string.ai_smooth_download_failed)).assertCountEquals(0)
                onAllNodesWithText(text(R.string.export_running_ai_smooth)).assertCountEquals(0)
                assertEquals(null, DeskExports.finish!!.ok)
                AiSmooth.fetch = { _, _, _ -> error(text(R.string.ai_model_download_failed)) }
                startAiExport()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text(R.string.ai_smooth_download_failed)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(text(R.string.ai_model_download_failed)).assertExists()
                onAllNodesWithText(text(R.string.export_failed_title)).assertCountEquals(0)
                shot("ai-smooth-download-failed")
                waitUntil(timeoutMillis = 30_000) { !ExportRun.running }
                assertEquals(false, DeskExports.finish!!.ok)
            }
            runSkikoComposeUiTest(size = Size(460f, 220f)) {
                setContent { DeskExportMini(DeskAppState()) }
                settle()
                onNodeWithText(text(R.string.ai_smooth_download_failed)).assertExists()
                onAllNodesWithText(text(R.string.export_notify_failed)).assertCountEquals(0)
            }
        } finally {
            held.countDown()
            stuck.countDown()
            AiSmooth.fetch = fetch
            ExportAudio.mixFor = mix
        }
    }

    @Test fun theCloseButtonClosesTheFinishedExport() {
        prepare("export-finished-close")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.exportOpen = true }
            settle(900)
            onAllNodesWithText(text(R.string.export_kind_frame_png)).onFirst().performClick()
            settle()
            onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText(text(R.string.saved_to_storage)).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("export-finished")
            onNode(hasContentDescription(text(R.string.cancel)) and hasClickAction()).performClick()
            settle()
            assertFalse(editor.exportOpen)
        }
    }

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun gifAndSequenceUseTheVideoControlsAndExportStraightFromTheirPage() {
        prepare("export-formats")
        session(1280, 800) { app ->
            val editor = openEditor(app, "text.xml")
            val prefs = ExportPrefs(app.context)
            prefs.save(ExportKind.Gif, prefs.load(ExportKind.Gif, editor.scene).copy(shortSide = 180, fps = 10))
            prefs.save(ExportKind.Sequence, prefs.load(ExportKind.Sequence, editor.scene).copy(shortSide = 180, fps = 24))
            runOnIdle { editor.exportOpen = true }
            settle(900)
            for ((kind, label, fps) in listOf(Triple(ExportKind.Gif, R.string.gif, 30), Triple(ExportKind.Sequence, R.string.export_kind_sequence, 10))) {
                DeskExports.clearFinish()
                onAllNodesWithText(text(label)).onFirst().performClick()
                settle(900)
                onNode(hasText("180p") and hasClickAction()).assertExists()
                onAllNodesWithText(if (kind == ExportKind.Gif) "12fps" else "48fps").assertCountEquals(0)
                onAllNodes(slider).assertCountEquals(if (kind == ExportKind.Gif) 1 else 2)
                onAllNodes(slider).onFirst().performScrollTo().performTouchInput { click(if (fps == 30) centerRight else centerLeft) }
                settle()
                onAllNodesWithText("${fps}fps").assertCountEquals(2)
                shot("export-${kind.name.lowercase()}")
                onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
                waitUntil(timeoutMillis = 120_000) { DeskExports.finish != null }
                assertEquals(true, DeskExports.finish!!.ok)
                assertEquals(fps, prefs.load(kind, editor.scene).fps)
                val files = DeskExports.finish!!.files.map { it.toFile()!!.name }
                if (kind == ExportKind.Gif) assertTrue(files.toString(), files.single().endsWith(".gif"))
                else assertEquals(List(frameCount(editor.scene, fps)) { sequenceFrameName(it, "png") }, files.sorted())
                settle()
                onAllNodesWithText(text(R.string.ok)).filter(hasClickAction()).onLast().performClick()
                settle()
            }
        }
    }

    @Test fun gifAndSequencePagesOnAPhoneATabletAndWithLargeText() {
        prepare("export-format-pages")
        val app = DeskAppState()
        val scene = scene("text.xml")
        for (kind in listOf(ExportKind.Gif, ExportKind.Sequence)) for ((name, size, density) in listOf(
            Triple("phone", Size(720f, 1560f), Density(2f)),
            Triple("phone-large-text", Size(720f, 1560f), Density(2f, 1.6f)),
            Triple("tablet", Size(2560f, 1600f), Density(2f)),
        )) runSkikoComposeUiTest(size = size, density = density) {
            var s by mutableStateOf(ExportPrefs(app.context).load(kind, scene).copy(shortSide = 360, fps = if (kind == ExportKind.Gif) 15 else 30))
            var exported = 0
            val tablet = name == "tablet"
            setContent {
                Box(Modifier.fillMaxSize().background(if (tablet) Am.Scrim else Am.SheetBg), Alignment.Center) {
                    Box(if (tablet) Modifier.size(980.dp, 752.dp).clip(RoundedCornerShape(18.dp)) else Modifier.fillMaxSize()) {
                        FormatPage(kind, scene, s, { InfoBlock(48L shl 30, 2_400_000, 6_000, false, StorageStatus.Enough) }, { ExportBar(true, null, tablet) { exported++ } },
                            { FileNameField(ExportName(scene.title), kind) }, { s = it }, {}, {}, {})
                    }
                }
            }
            settle()
            onNode(hasText("360p") and hasClickAction()).assertExists()
            onAllNodes(slider).assertCountEquals(if (kind == ExportKind.Gif) 1 else 2)
            onAllNodesWithText(text(R.string.ok)).assertCountEquals(0)
            shot("page-${kind.name.lowercase()}-$name")
            onAllNodes(slider).onFirst().performScrollTo().performTouchInput { click(centerRight) }
            settle()
            assertEquals(if (kind == ExportKind.Gif) 30 else 60, s.fps)
            onNode(hasText(text(R.string.export)) and hasClickAction()).performClick()
            assertEquals(1, exported)
        }
    }

    @Test fun theCurrentFrameExportIsItsOwnWindowAndItsCloseButtonWorksAfterExporting() {
        prepare("frame-window")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.exportOpen = true }
            settle(900)
            onAllNodesWithText(text(R.string.export_kind_frame_as)).onFirst().performClick()
            settle()
            assertTrue(onAllNodesWithText(text(R.string.export_kind_frame_png)).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText(text(R.string.export_format)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText(text(R.string.export_transparent_becomes)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText(text(R.string.also_keep_a_copy_as_a)).fetchSemanticsNodes().isEmpty())
            shot("frame-window")
            onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText(text(R.string.saved_to_storage)).fetchSemanticsNodes().isNotEmpty() }
            settle()
            onNode(hasContentDescription(text(R.string.cancel)) and hasClickAction()).performClick()
            settle()
            assertTrue(editor.exportOpen)
            assertTrue(onAllNodesWithText(text(R.string.export_kind_frame_png)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText(text(R.string.export_transparent_becomes)).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText(text(R.string.also_keep_a_copy_as_a)).fetchSemanticsNodes().isNotEmpty())
            onAllNodesWithText(text(R.string.export_kind_frame_as)).onFirst().performClick()
            settle()
            assertTrue(onAllNodesWithText(text(R.string.export_transparent_becomes)).fetchSemanticsNodes().isNotEmpty())
            runOnIdle { DeskBack.back() }
            settle()
            assertTrue(editor.exportOpen)
            assertTrue(onAllNodesWithText(text(R.string.export_transparent_becomes)).fetchSemanticsNodes().isEmpty())
            onNode(hasContentDescription(text(R.string.cancel)) and hasClickAction()).performClick()
            settle()
            assertFalse(editor.exportOpen)
        }
    }

    @Test fun valueBoxesSelectOnOneClickAndTypeOnADoubleClick() {
        prepare("double-click-values")
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.settings.basicTransform = false; editor.selectedId = editor.scene.layers.last().id; editor.panel = EditorPanel.MoveTransform }
            settle(600)
            onAllNodes(hasText(text(R.string.a11y_tab_skew)) and hasClickAction()).onFirst().performClick()
            settle(600)
            val skew = onAllNodes(hasText("º", substring = true) and hasClickAction())
            skew[1].performMouseInput { click() }
            settle()
            assertNull(editor.keypad)
            skew[0].performMouseInput { doubleClick() }
            settle()
            assertEquals(0, editor.keypad?.active)
            assertEquals(2, editor.keypad?.fields?.size)
            runOnIdle { editor.keypad = null }
            settle()
            skew[1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            settle()
            assertEquals(1, editor.keypad?.active)
            runOnIdle { editor.keypad = null; editor.editSelected { it.withRotation(30f) } }
            onAllNodes(hasText(text(R.string.a11y_tab_rotate)) and hasClickAction()).onFirst().performClick()
            settle(600)
            onNode(hasText("30º") and hasClickAction()).performMouseInput { click() }
            settle()
            assertNull(editor.keypad)
            onNode(hasText("30º") and hasClickAction()).performMouseInput { doubleClick() }
            settle()
            assertNotNull(editor.keypad)
            assertEquals(30f, editor.selected!!.rotation, 1e-3f)
            runOnIdle { editor.keypad = null }
            settle()
            onNode(hasText("30º") and hasClickAction()).performTouchInput {
                val pad = Offset(-80f, height / 2f)
                down(pad)
                up()
                advanceEventTime(80)
                down(pad)
                moveBy(Offset(2f, 3f))
                moveBy(Offset(1f, 2f))
                up()
            }
            settle()
            assertNull(editor.keypad)
            assertEquals(0f, editor.selected!!.rotation, 0f)
        }
    }

    @Test fun aCommunityBundleCarriesItsThumbnailAndDetails() {
        prepare("community-bundle")
        DeskExports.clearFinish()
        session(1280, 800) { app ->
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.exportOpen = true }
            settle(900)
            onAllNodesWithText(text(R.string.project_bundle)).onFirst().performClick()
            settle()
            onAllNodesWithText(text(R.string.bundle_community)).onFirst().performClick()
            settle(1500)
            fun refused(message: Int) {
                onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
                settle()
                onNodeWithText(text(message)).assertExists()
                assertNull(DeskExports.finish)
                onNodeWithText(text(R.string.ok)).performClick()
                settle()
            }
            refused(R.string.community_need_confirm)
            onAllNodesWithText(text(R.string.community_confirm)).onFirst().performScrollTo().performClick()
            settle()
            refused(R.string.community_need_guidelines)
            val sentence = text(R.string.community_follows_guidelines).replace("%1\$s", text(R.string.community_guidelines_link))
            val consent = onNodeWithText(sentence).performScrollTo()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            consent.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val position = layouts.single().getBoundingBox(sentence.indexOf(text(R.string.community_guidelines_link)) + 1).center
            consent.performTouchInput { click(position) }
            settle()
            onNodeWithText("Community Guidelines").assertExists()
            shot("community-guidelines")
            onNodeWithContentDescription(text(R.string.action_close)).performClick()
            settle()
            onNodeWithText(sentence).performScrollTo().onParent().onChildren().filter(isToggleable()).onFirst().performClick()
            settle()
            onAllNodesWithText(text(R.string.community_confirm)).onFirst().performScrollTo().performClick()
            settle()
            refused(R.string.community_need_confirm)
            onAllNodesWithText(text(R.string.community_confirm)).onFirst().performScrollTo().performClick()
            settle()
            shot("community-bundle")
            onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
            waitUntil(timeoutMillis = 60_000) { DeskExports.finish != null }
            assertEquals(true, DeskExports.finish!!.ok)
            java.util.zip.ZipFile(DeskExports.finish!!.files.single().toFile()!!).use { zip ->
                val manifest = org.json.JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString())
                val community = manifest.getJSONObject("community")
                assertEquals(CommunityThumbnail, community.getString("thumbnail"))
                assertTrue(community.getString("title").isNotBlank())
                assertTrue(zip.getEntry(CommunityThumbnail).size > 1000)
            }
        }
    }

    @Test fun aBundleWhoseFolderIsGoneSavesToDocumentsAndSaysSo() {
        prepare("bundle-folder-gone")
        val blocked = File("build/bundle-folder-gone-home/not-a-folder").also { it.writeText("x") }
        session(1280, 800) { app ->
            app.settings.bundlesFolder = blocked.absolutePath
            val editor = openEditor(app, "styles.xml")
            runOnIdle { editor.exportOpen = true }
            settle(900)
            onAllNodesWithText(text(R.string.project_bundle)).onFirst().performClick()
            settle(1500)
            onAllNodesWithText(text(R.string.bundle_folder_lost).substringBefore("%1"), substring = true).onFirst().performScrollTo().assertExists()
            shot("bundle-folder-gone")
            onAllNodesWithText(text(R.string.export)).filter(hasClickAction()).onLast().performClick()
            waitUntil(timeoutMillis = 60_000) { DeskExports.finish != null }
            assertEquals(true, DeskExports.finish!!.ok)
            val saved = DeskExports.finish!!.files.single().toFile()!!
            assertTrue(saved.path, saved.isFile && saved.path.contains("Documents"))
            assertTrue(blocked.isFile)
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
