package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import app.absentmotion.library.CommunityInfo
import app.absentmotion.library.ImportReport
import app.absentmotion.library.reportText
import app.absentmotion.ui.home.ImportDialog
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.ui.tutorial.OfferDialog
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.LocalTablet
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.TransitionSheet
import app.absentmotion.ui.home.CrashLogsScreen
import app.absentmotion.ui.home.SettingsDrawer
import app.absentmotion.ui.home.TrimScreen
import app.absentmotion.ui.home.ExtractAudioScreen
import app.absentmotion.ui.home.CompressScreen
import app.absentmotion.ui.home.GifScreen
import app.absentmotion.ui.home.DownloaderScreen
import app.absentmotion.ui.home.NewProjectSheet
import app.absentmotion.ui.home.ReportDialog
import app.absentmotion.ui.home.VersionHistory
import app.absentmotion.ui.home.ClosedDialog
import app.absentmotion.ui.home.OpenFailedDialog
import app.absentmotion.ui.home.UpdateAskDialog
import app.absentmotion.ui.home.WideUpdateCard
import app.absentmotion.ui.home.UpdateOfferContent
import app.absentmotion.ui.home.UpdateAnswer
import app.absentmotion.ui.home.Release
import app.absentmotion.ui.home.ApkAsset
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.home.WhatsNew
import app.absentmotion.ui.home.WhatsNewDialog
import app.absentmotion.ui.adaptive.AdaptiveWindow
import app.absentmotion.ui.onboarding.FirstRun
import app.absentmotion.ui.onboarding.Onboarding
import app.absentmotion.ui.onboarding.OnboardingStep
import app.absentmotion.ui.theme.Am
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AdaptivePagesShotsTest {
    init { System.setProperty("am.data", File("build/adaptive-pages-data").absolutePath) }
    private val sizes = listOf(1280 to 800, 1920 to 1080)

    private fun ComposeUiTest.shot(name: String, w: Int, h: Int) {
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        val dir = File("build/ui-shots/adaptive").also { it.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name-${w}x$h.png"))
    }

    @Test fun onboarding() {
        val app = DeskAppState()
        for ((w, h) in sizes) for ((step, label) in listOf(
            OnboardingStep.Language to "Choose your language",
            OnboardingStep.Look to "Select your theme",
            OnboardingStep.Check to "Check what this device can do",
        )) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent { Onboarding(app.settings, FirstRun(step), { error("Not started") }) {} }
                onNodeWithText(label).assertExists()
                shot("onboarding-${step.name.lowercase()}", w, h)
            }
        }
    }

    @Test fun settings() {
        val app = DeskAppState()
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                val drawer = rememberDrawerState(DrawerValue.Open)
                ModalNavigationDrawer(drawerContent = { SettingsDrawer(app.settings, drawer) }, drawerState = drawer) {
                    Box(Modifier.fillMaxSize())
                }
            }
            onNodeWithText("Settings").assertExists()
            shot("settings", w, h)
            onNodeWithText("Backup").performClick()
            shot("settings-backup", w, h)
        }
    }

    @Test fun crashLogs() {
        val folder = Context().filesDir.resolve("crashlogs")
        folder.mkdirs()
        val log = folder.resolve("1700000000000_Error.txt")
        log.writeText("Example crash detail")
        try {
            for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    CompositionLocalProvider(LocalWide provides true, LocalTablet provides true) {
                        Box(Modifier.fillMaxSize().background(Am.HomeBg))
                        CrashLogsScreen {}
                    }
                }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithText("It hit an error it could not recover from.").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("It hit an error it could not recover from.").onFirst().performClick()
                waitUntil(timeoutMillis = 10000) { onAllNodesWithText("Example crash detail").fetchSemanticsNodes().isNotEmpty() }
                shot("crash-logs", w, h)
            }
        } finally { log.delete() }
    }

    @Test fun tools() {
        val app = DeskAppState()
        for ((w, h) in sizes) for (page in listOf("trim", "extract", "compress", "gif", "downloader")) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    when (page) {
                        "trim" -> TrimScreen {}
                        "extract" -> ExtractAudioScreen {}
                        "compress" -> CompressScreen {}
                        "gif" -> GifScreen {}
                        else -> DownloaderScreen(app.store, app.settings, {}, {}, {})
                    }
                }
                shot("tool-$page", w, h)
            }
        }
    }

    @Test fun newProjectTablet() {
        val app = DeskAppState()
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                CompositionLocalProvider(LocalTablet provides true) {
                    NewProjectSheet(app.store, app.settings, emptyList(), false, 0, {}, {})
                }
            }
            onNodeWithText("New project").assertExists()
            shot("new-project-tablet", w, h)
        }
    }

    @Test fun report() {
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                ReportDialog("Example report") {}
            }
            onNodeWithText("Copy and Report").assertExists()
            shot("report", w, h)
        }
    }

    @Test fun projectSafety() {
        val app = DeskAppState()
        val xml = File("../testdata/scenes/styles.xml").readText()
        val id = app.store.create(Scene.parse(xml))
        val folder = app.store.versions.dir(id).also { it.mkdirs() }
        repeat(4) { folder.resolve("${1700000000000L + it * 60000}.xml").writeText(xml) }
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                VersionHistory(app.store, id, "Example project", app.engine, {}, damaged = true) {}
            }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithText("Restore").fetchSemanticsNodes().size == 4 }
            shot("project-safety", w, h)
        }
    }

    @Test fun recoveryNotices() {
        for ((w, h) in sizes) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    ClosedDialog(null, "Example project", null, {}, {}, {})
                }
                onNodeWithText("Restore").assertExists()
                shot("recovery-notice", w, h)
            }
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    OpenFailedDialog({}, null) {}
                }
                onNodeWithText("Version history").assertExists()
                shot("open-failed", w, h)
            }
        }
    }

    @Test fun updateWindows() {
        val app = DeskAppState()
        for ((w, h) in sizes) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    UpdateAskDialog(app.settings)
                }
                onNodeWithText("Check for a new version each time the app opens").assertExists()
                shot("update-ask", w, h)
            }
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    AdaptiveWindow(1000.dp, 440.dp) {
                        val release = Release("5.2.0", "https://example.com", ApkAsset("update.apk", "https://example.com", 120L * 1024 * 1024, null, null))
                        WideUpdateCard(HomeIcons.ArrowDown, "AbsentMotion 5.2.0 is available", null) {
                            UpdateOfferContent(UpdateAnswer.Available(release), "5.1.1", false, null, {}, {}, {})
                        }
                    }
                }
                onNodeWithText("Download update").assertExists()
                shot("update-available", w, h)
            }
        }
    }

    @Test fun whatsNew() {
        val notes = WhatsNew.parse(File("../shared/assets/whats-new/5.1.1.md").readText())
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                WhatsNewDialog(notes) {}
            }
            onNodeWithText(notes.title).assertExists()
            onNodeWithText("New features").assertExists()
            shot("whats-new", w, h)
            onNodeWithText("Improvements").performClick()
            shot("whats-new-expanded", w, h)
        }
    }

    @Test fun importSheets() {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val community = CommunityInfo("Example item", "Example creator", "An example community item description.", listOf("Motion", "Design"), bytes)
        val section = listOf(reportText("Import details") to listOf(reportText("Example imported project")))
        for ((w, h) in sizes) for (withCommunity in listOf(false, true)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val report = ImportReport("example", "Example project", false, section, community = community.takeIf { withCommunity })
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                ImportDialog(listOf(report), {}, {})
            }
            onNodeWithText("Import details").assertExists()
            if (withCommunity) onNodeWithText("Example item").assertExists()
            shot(if (withCommunity) "community-import" else "bundle-import", w, h)
        }
    }

    @Test fun templateEditor() {
        val app = DeskAppState()
        val folder = Context().filesDir.resolve("templates/adaptive-shot").also { it.mkdirs() }
        folder.resolve("scene.xml").writeText(File("../testdata/scenes/media.xml").readText())
        try {
            for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent { TemplateEditor("adaptive-shot", app.engine, app.settings, {}, {}) }
                waitUntil(timeoutMillis = 10000) { onAllNodesWithText("Media slots").fetchSemanticsNodes().isNotEmpty() }
                shot("template-editor", w, h)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun tutorialOffer() {
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                OfferDialog({}, {}, {})
            }
            onNodeWithText("Start the tour").assertExists()
            shot("tutorial-offer", w, h)
        }
    }

    @Test fun motion() {
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val ids = state.scene.layers.take(2).map { it.id }
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                CompositionLocalProvider(LocalWide provides true, LocalTablet provides true) {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    TransitionSheet(state, ids[0], ids[1]) {}
                }
            }
            onNodeWithText("Transition").assertExists()
            shot("motion", w, h)
        }
    }
    @Test fun editorPages() {
        val desk = DeskAppState()
        val id = desk.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { desk.openNow(id)!! }
        state.selectedId = state.scene.layers.first().id
        val ids = state.scene.layers.take(2).map { it.id }.toSet()
        val fonts = app.absentmotion.fonts.FontLibrary(Context())
        for ((w, h) in sizes) for (page in listOf("stretch", "voice", "captions", "presets", "effects", "colour", "fonts")) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    CompositionLocalProvider(LocalWide provides true, LocalTablet provides true) {
                        Box(Modifier.fillMaxSize().background(Am.HomeBg))
                        when (page) {
                            "stretch" -> app.absentmotion.ui.editor.StretchSheet(state, ids) {}
                            "voice" -> app.absentmotion.ui.editor.VoiceSheet(state) {}
                            "captions" -> app.absentmotion.ui.editor.CaptionsSheet(state) {}
                            else -> AdaptiveWindow {
                                when (page) {
                                    "presets" -> app.absentmotion.ui.editor.PresetScreen(state)
                                    "effects" -> app.absentmotion.ui.editor.effects.EffectBrowser(state)
                                    "colour" -> app.absentmotion.ui.editor.panels.FullColorPicker(state, app.absentmotion.editor.FullColor(0xFF4488CC.toInt()) { _, _ -> })
                                    "fonts" -> app.absentmotion.ui.editor.FontBrowser(fonts, {}, {})
                                }
                            }
                        }
                    }
                }
                when (page) {
                    "stretch" -> onNodeWithText("Stretch Timing").assertExists()
                    "voice" -> onNodeWithText("Text to Speech").assertExists()
                    "captions" -> onNodeWithText("Generate").assertExists()
                    "presets" -> onNodeWithText("Try a Preset").assertExists()
                    "effects" -> onNodeWithText("Effects Library").assertExists()
                    "colour" -> onNodeWithContentDescription("Color sliders").assertExists()
                    "fonts" -> onNodeWithText("All Fonts").assertExists()
                }
                shot("editor-$page", w, h)
                if (page == "captions") {
                    onNodeWithText("Appearance").performClick()
                    shot("editor-captions-appearance", w, h)
                }
                if (page == "colour") {
                    onNodeWithContentDescription("Color sliders").performClick()
                    shot("editor-colour-sliders", w, h)
                }
            }
        }
    }

}
