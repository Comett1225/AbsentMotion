package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.LocalTablet
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.TransitionSheet
import app.absentmotion.ui.home.CrashLogsScreen
import app.absentmotion.ui.home.SettingsDrawer
import app.absentmotion.ui.home.TrimScreen
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
        for ((w, h) in sizes) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                Box(Modifier.fillMaxSize().background(Am.HomeBg))
                TrimScreen {}
            }
            onAllNodesWithText("Trim").onFirst().assertExists()
            shot("trim", w, h)
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
}
