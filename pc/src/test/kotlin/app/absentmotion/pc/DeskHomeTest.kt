package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskGlobalKeys
import app.absentmotion.desk.DeskScale
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.SettingsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskHomeTest {
    private val tier = app.absentmotion.Tier.openGl

    private val sizes = listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/home").also { it.mkdirs() }
    private var focus = androidx.compose.ui.focus.FocusRequester()
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun prepare() {
        val data = File("build/home-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val home = File("build/home-home").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("user.home", home.absolutePath)
        System.setProperty("am.exports", File("build/home-exports").also { it.deleteRecursively() }.absolutePath)
        val clean = DeskAppState()
        clean.store.list(includeDamaged = true).forEach { clean.store.delete(it.id, null) }
        TemplateStore(clean.context).let { templates -> templates.list().forEach { templates.delete(it.id) } }
    }

    private fun scene(name: String): Scene {
        val xml = repo.resolve("testdata/scenes/$name").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        return Scene.parse(xml)
    }

    private fun session(width: Int, height: Int, body: ComposeUiTest.(DeskAppState) -> Unit) = runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
        val app = DeskAppState()
        focus = androidx.compose.ui.focus.FocusRequester()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(focus).focusable().onKeyEvent { DeskGlobalKeys.handle(app, it) }) { DeskApp(app) }
        }
        runOnIdle { runCatching { focus.requestFocus() } }
        body(app)
        runBlocking { app.closeEditor() }
    }

    private fun ComposeUiTest.shot(name: String, park: Boolean = true) {
        if (park) onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(3f, 3f)) }
        waitForIdle()
        mainClock.advanceTimeBy(700)
        mainClock.advanceTimeBy(400)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun DeskAppState.ioUpdateAnswer() {
        app.absentmotion.ui.home.Updates.answer = app.absentmotion.ui.home.UpdateAnswer.Available(app.absentmotion.ui.home.Release("5.1.0", "https://github.com/Comett1225/AbsentMotion/releases/latest"), external = true)
    }

    private fun ComposeUiTest.settle(ms: Long = 600) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun ComposeUiTest.press(key: androidx.compose.ui.input.key.Key) {
        runOnIdle { runCatching { focus.requestFocus() } }
        onAllNodes(isRoot()).onFirst().performKeyInput { withKeyDown(if (mac) androidx.compose.ui.input.key.Key.MetaLeft else androidx.compose.ui.input.key.Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    @Test fun homeScreens() {
        prepare()
        DeskScale.set(100)
        for ((w, h) in sizes) session(w, h) { app ->
            settle()
            shot("$w-01-home-empty")
            onAllNodesWithText("New project").onFirst().performClick()
            settle()
            shot("$w-09-new-project")
            DeskBack.back()
            settle()
            assertTrue(app.store.list().isEmpty())
        }
        val seed = DeskAppState()
        listOf("styles.xml", "media.xml", "text.xml", "shapes.xml", "easing.xml", "audit.xml").forEach { name ->
            val sc = scene(name)
            val id = seed.store.create(sc)
            runBlocking { seed.saveThumbnail(sc, sc.totalTime / 2, id) }
        }
        scene("shapes.xml").let { sc ->
            val asset = Scene(sc.node.with("type", "element"))
            val id = seed.store.create(asset)
            runBlocking { seed.saveThumbnail(asset, asset.totalTime / 2, id) }
        }
        runBlocking { TemplateStore(seed.context).add(scene("text.xml"), 1000, seed.engine) }
        for ((w, h) in sizes) session(w, h) { app ->
            runOnIdle { app.projectsChanged++ }
            settle(1200)
            shot("$w-02-home-projects")
            onNodeWithText("Assets").performClick()
            settle()
            shot("$w-02b-home-assets")
            onNodeWithText("Templates").performClick()
            settle(1000)
            shot("$w-02c-home-templates")
            onNodeWithText("Projects").performClick()
            settle()
            onAllNodes(hasText("Settings") or hasContentDescription("Settings")).filter(hasClickAction()).onFirst().performClick()
            settle()
            assertTrue(app.settingsOpen)
            shot("$w-03-settings-editor")
            DeskBack.back()
            settle()
            assertTrue(!app.settingsOpen)
            for (page in SettingsPage.entries) {
                runOnIdle { app.openSettings(page) }
                settle(900)
                shot("$w-03-settings-${page.name.lowercase()}")
                runOnIdle { app.settingsOpen = false }
                settle(200)
            }
            if (w == 1280) {
                runOnIdle { app.ioUpdateAnswer() }
                settle()
                shot("$w-16-update-dialog")
                DeskBack.back()
                settle()
            }
            runOnIdle { app.openSettings(SettingsPage.About) }
            settle()
            onNodeWithText("Open source licenses", substring = true).performClick()
            settle(900)
            shot("$w-04-licences")
            DeskBack.back()
            settle()
            DeskBack.back()
            settle()
            assertTrue(!app.settingsOpen)
            onAllNodesWithText("Media").onFirst().performMouseInput { rightClick() }
            settle()
            shot("$w-10-context-menu")
            DeskBack.back()
            settle()
            val first = app.store.list().first()
            runOnIdle { app.historyFor = Triple(first.id, first.title, false) }
            settle(900)
            shot("$w-11-version-history")
            runOnIdle { app.historyFor = null }
            settle(200)
            val scene = app.store.load(first.id)!!
            runOnIdle { app.exportingFromList = scene }
            settle(1500)
            shot("$w-12-export")
            if (w == 1280) {
                onNodeWithText("Audio Only").performMouseInput { moveTo(center) }
                settle(900)
                shot("$w-12b-export-disabled-hint", park = false)
            }
            runOnIdle { app.exportingFromList = null }
            settle(200)
            if (w == 1280 || w == 2560) {
                val templateId = TemplateStore(app.context).list().first().id
                runOnIdle { app.editingTemplate = templateId }
                settle(2500)
                Thread.sleep(1500)
                shot("$w-17-template-editor")
                runOnIdle { app.editingTemplate = null }
                settle(300)
            }
        }
    }

    @Test fun interactions() {
        prepare()
        DeskScale.set(100)
        val sc = listOf("styles.xml", "media.xml", "text.xml").map { scene(it) }
        session(1280, 800) { app ->
            sc.forEach { app.store.create(it) }
            runOnIdle { app.projectsChanged++ }
            settle(1200)
            val count = app.store.list().size
            assertEquals(3, count)
            onAllNodesWithText("Styles").onFirst().performMouseInput { doubleClick() }
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            assertNotNull(app.editor)
            runBlocking { app.closeEditor() }
            settle(1200)
            assertNull(app.editor)
            for (gap in listOf(330L, 10L)) {
                onAllNodesWithText("Text").onFirst().performMouseInput { click(); advanceEventTime(gap); click() }
                waitUntil(timeoutMillis = 30_000) { app.editor != null }
                assertNotNull(app.editor)
                runBlocking { app.closeEditor() }
                settle(1200)
                assertNull(app.editor)
            }
            onAllNodesWithText("Media").onFirst().performMouseInput { moveTo(center) }
            settle()
            shot("1280-16-card-hover", park = false)
            onAllNodesWithText("Media").onFirst().performClick()
            settle(600)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            assertEquals("Media", app.editor!!.project.title)
            runBlocking { app.closeEditor() }
            settle(1200)
            onAllNodesWithText("Text").onFirst().performClick()
            settle(600)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Delete) }
            settle()
            onNodeWithText("Delete this project?").assertExists()
            DeskBack.back()
            settle()
            onAllNodesWithText("Styles").onFirst().performMouseInput { rightClick() }
            settle()
            onNodeWithText("Delete").performClick()
            settle()
            onNodeWithText("Delete this project?").assertExists()
            shot("1280-13-delete-confirm")
            onAllNodesWithText("Delete").onLast().performClick()
            settle(1200)
            waitUntil(timeoutMillis = 5_000) { app.store.list().size == 2 }
            assertEquals(2, app.store.list().size)
            onAllNodes(hasText("Settings") or hasContentDescription("Settings")).filter(hasClickAction()).onFirst().performClick()
            settle()
            assertTrue(app.settingsOpen)
            DeskBack.back()
            settle()
            assertTrue(!app.settingsOpen)
            DeskScale.set(100)
            press(Key.Equals)
            assertEquals(110, DeskScale.percent)
            press(Key.Minus)
            press(Key.Minus)
            assertEquals(90, DeskScale.percent)
            press(Key.Zero)
            assertEquals(100, DeskScale.percent)
            repeat(10) { press(Key.Equals) }
            assertEquals(150, DeskScale.percent)
            repeat(10) { press(Key.Minus) }
            assertEquals(80, DeskScale.percent)
            DeskScale.set(100)
        }
    }

    @Test fun arrowNavigation() {
        prepare()
        DeskScale.set(100)
        val names = listOf("styles.xml", "media.xml", "text.xml", "shapes.xml", "easing.xml", "audit.xml")
        session(1280, 420) { app ->
            names.forEach { app.store.create(scene(it)) }
            app.settings.sortBy = "name"
            runOnIdle { app.projectsChanged++ }
            settle(1200)
            val titles = app.store.list().map { it.title }.sortedBy { it.lowercase() }
            assertEquals(6, titles.size)
            onAllNodesWithText(titles[0]).onFirst().performClick()
            settle(400)
            shot("1280-23-arrow-start")
            val columns = 4
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionDown) }
            settle(600)
            shot("1280-23-arrow-down")
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            assertEquals(titles[columns], app.editor!!.project.title)
            runBlocking { app.closeEditor() }
            settle(1200)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionUp) }
            settle(600)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            assertEquals(titles[0], app.editor!!.project.title)
            runBlocking { app.closeEditor() }
            settle(800)
        }
    }

    @Test fun scaled() {
        prepare()
        val sc = listOf("styles.xml", "media.xml", "text.xml", "shapes.xml").map { scene(it) }
        val seed = DeskAppState()
        sc.forEach { scene -> val id = seed.store.create(scene); runBlocking { seed.saveThumbnail(scene, scene.totalTime / 2, id) } }
        for (percent in listOf(80, 150)) {
            DeskScale.set(percent)
            for ((w, h) in sizes.take(3)) session(w, h) { app ->
                runOnIdle { app.projectsChanged++ }
                settle(1200)
                shot("$w-20-home-scale-$percent")
                runOnIdle { app.openSettings(SettingsPage.Playback) }
                settle(900)
                shot("$w-21-settings-scale-$percent")
            }
        }
        DeskScale.set(100)
        session(1280, 800) { app ->
            runOnIdle { app.projectsChanged++ }
            settle(1200)
            shot("1280-22-home-before-rescale")
            DeskScale.set(150)
            settle(900)
            shot("1280-22-home-rescaled-150")
            DeskScale.set(80)
            settle(900)
            shot("1280-22-home-rescaled-80")
            DeskScale.set(100)
        }
        DeskScale.set(100)
    }

    @Test fun exportFlow() {
        prepare()
        DeskScale.set(100)
        val seed = DeskAppState()
        val sc = scene("text.xml")
        val id = seed.store.create(sc)
        runBlocking { seed.saveThumbnail(sc, sc.totalTime / 2, id) }
        for ((w, h) in listOf(1280 to 800, 1024 to 700)) session(w, h) { app ->
            runOnIdle { app.projectsChanged++ }
            settle(1000)
            runOnIdle { app.exportingFromList = app.store.load(id)!! }
            settle(1500)
            onAllNodesWithText("Export").onLast().performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithText("Saved to storage").fetchSemanticsNodes().isNotEmpty() }
            shot("$w-14-export-running")
            waitUntil(timeoutMillis = 120_000) { onAllNodesWithText("Saved to storage").fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1500)
            Thread.sleep(800)
            shot("$w-15-export-done")
            onNodeWithText("OK").performClick()
            settle()
            runOnIdle { app.exportingFromList = null }
            settle(300)
        }
    }
}
