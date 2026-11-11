package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskInfo
import app.absentmotion.ui.home.HomeScreen
import app.absentmotion.ui.home.SettingsPage
import app.absentmotion.ui.home.WhatsNew
import app.absentmotion.ui.home.WhatsNewNotes
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskChangelogTest {
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/changelog").also { it.mkdirs() }
    private val saved = listOf("am.data", "user.home", "am.exports").associateWith { System.getProperty(it) }
    private val key = WhatsNewNotes.key(DeskInfo.version)!!
    private val title = WhatsNew.parse(repo.resolve("shared/assets/whats-new/" + WhatsNewNotes.notesFor(key, null) { repo.resolve("shared/assets/whats-new/$it.md").isFile } + ".md").readText()).title

    @After fun restoreProperties() {
        saved.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
    }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskAppState().settings.apply { tutorialAsk = 0; onboarding = ONBOARDING_DONE; tutorialStep = null; tutorialProject = null; whatsNewSeen = null }
    }

    private fun ComposeUiTest.settle(ms: Long = 700) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun ComposeUiTest.shot(name: String) {
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(3f, 3f)) }
        settle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.readAndClose() {
        settle()
        onNodeWithText(title).assertExists()
        onAllNodesWithText("Continue").onLast().performClick()
        settle()
        onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun phoneHomeSettingsAndAboutOpenTheCurrentNotes() {
        prepare("changelog")
        runSkikoComposeUiTest(size = Size(400f, 860f)) {
            val app = DeskAppState()
            setContent { HomeScreen(app.store, app.settings, onOpen = {}) }
            settle()
            val icon = onNodeWithContentDescription("Changelog").assertIsDisplayed().getBoundsInRoot()
            val search = onNodeWithContentDescription("Search").assertIsDisplayed().getBoundsInRoot()
            assertTrue("$icon left of $search", icon.right <= search.left + 1.dp)
            assertTrue(WhatsNewNotes.unseen(app.context, app.settings))
            shot("400-home-changelog-dot")
            onNodeWithContentDescription("Changelog").performClick()
            readAndClose()
            assertEquals(key, app.settings.whatsNewSeen)
            assertFalse(WhatsNewNotes.unseen(app.context, app.settings))
            shot("400-home-changelog-seen")

            onNodeWithContentDescription("Settings").performClick()
            settle()
            onNodeWithText("Version ${DeskInfo.version}").assertIsDisplayed()
            onNodeWithText("Check for Update").assertIsDisplayed()
            onNodeWithText("Report issue").assertIsDisplayed()
            onNodeWithText("Suggest a feature").assertIsDisplayed()
            onNodeWithText("Check for a new version each time the app opens").assertDoesNotExist()
            val auto = app.settings.autoUpdateCheck
            onNodeWithText(if (auto) "Auto-check: On" else "Auto-check: Off").performClick()
            settle()
            assertEquals(!auto, app.settings.autoUpdateCheck)
            if (!app.settings.autoUpdateCheck) {
                onNodeWithText("Auto-check: Off").performClick()
                settle()
            }
            onNodeWithText("Auto-check: On").assertIsDisplayed()
            assertTrue(app.settings.autoUpdateCheck)
            shot("400-settings-compact")
            onNodeWithText("What's new").performClick()
            readAndClose()

            onNodeWithText("About").performScrollTo().performClick()
            settle()
            onNodeWithText("See what's new in $key").assertIsDisplayed()
            shot("400-about")
            onNodeWithText("See what's new in $key").performClick()
            readAndClose()
            onNodeWithText("See what's new in $key").assertIsDisplayed()
        }
    }

    @Test fun desktopHeaderKeepsRoomForTheChangelogButton() {
        for ((width, height) in listOf(820 to 560, 1024 to 700, 1280 to 800)) {
            prepare("changelog-desk")
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                settle(900)
                val icon = onNodeWithContentDescription("Changelog").assertIsDisplayed().getBoundsInRoot()
                val tools = onAllNodesWithText("Tools").onFirst().getBoundsInRoot()
                val search = onNode(hasSetTextAction()).getBoundsInRoot()
                val settings = onAllNodes(hasText("Settings") or hasContentDescription("Settings")).filter(hasClickAction()).onFirst().getBoundsInRoot()
                assertTrue("$width: $tools before $icon", tools.right <= icon.left)
                assertTrue("$width: $icon before $search", icon.right <= search.left)
                assertTrue("$width: $settings inside the window", settings.right.value <= width + 0.5f)
                shot("$width-desktop-home-changelog")
                onNodeWithContentDescription("Changelog").performClick()
                readAndClose()
                assertEquals(key, app.settings.whatsNewSeen)
                app.openSettings(SettingsPage.About)
                settle()
                if (width == 1280) shot("1280-desktop-about")
                onNodeWithText("See what's new in $key").performClick()
                readAndClose()
                runBlocking { app.closeEditor() }
            }
        }
    }
}
