package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskScale
import app.absentmotion.desk.DeskWindow
import app.absentmotion.desk.MenuKey
import app.absentmotion.desk.WindowButton
import app.absentmotion.desk.deskMenus
import app.absentmotion.desk.shortcutText
import app.absentmotion.editor.groupLayers
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskGear
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskWindowTest {
    private val shots = File("build/ui-shots/window").also { it.mkdirs() }
    private val windows = DeskWindow.Chrome(end = 138f)
    private val chromes = mapOf(
        "fallback" to DeskWindow.Chrome(),
        "windows" to windows,
        "mac" to DeskWindow.Chrome(start = 78f),
        "macstrip" to DeskWindow.Chrome(strip = 28f),
        "linux" to DeskWindow.Chrome(drawn = listOf(WindowButton.Minimize, WindowButton.Maximize, WindowButton.Close)),
        "linuxleft" to DeskWindow.Chrome(drawn = listOf(WindowButton.Close, WindowButton.Minimize, WindowButton.Maximize), drawnLeft = true),
    )

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage() as BufferedImage
        val chrome = DeskWindow.chrome
        val scale = DeskScale.percent / 100f
        val bar = (DeskWindow.BAR * scale).toInt()
        image.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            if (chrome.end > 0f) {
                color = java.awt.Color(128, 128, 128)
                val x = image.width - chrome.end.toInt()
                val y = bar / 2
                drawLine(x + 18, y, x + 28, y)
                drawRect(x + 46 + 18, y - 5, 10, 10)
                drawLine(x + 92 + 18, y - 5, x + 92 + 28, y + 5)
                drawLine(x + 92 + 18, y + 5, x + 92 + 28, y - 5)
            }
            val lights = if (chrome.start > 0f) bar / 2 else if (chrome.strip > 0f) chrome.strip.toInt() / 2 else -1
            if (lights >= 0) listOf(java.awt.Color(255, 95, 87), java.awt.Color(254, 188, 46), java.awt.Color(40, 200, 64)).forEachIndexed { i, c -> color = c; fillOval(14 + 20 * i, lights - 6, 12, 12) }
            dispose()
        }
        ImageIO.write(image, "png", shots.resolve("$name.png"))
    }

    private fun data(name: String) = System.setProperty("am.data", File("build/window-test-$name").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)

    private fun ComposeUiTest.open(app: DeskAppState) {
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
    }

    private fun ComposeUiTest.burger() {
        Thread.sleep(350)
        onAllNodesWithContentDescription("Menu").onFirst().performClick()
        mainClock.advanceTimeBy(300)
    }

    private fun ComposeUiTest.keys(vararg names: String) {
        val keys = names.map { mapOf("down" to Key.DirectionDown, "up" to Key.DirectionUp, "left" to Key.DirectionLeft, "right" to Key.DirectionRight, "enter" to Key.Enter, "esc" to Key.Escape).getValue(it) }
        keys.forEach { key -> onAllNodesWithText("File", substring = false).onFirst().performKeyInput { pressKey(key) }; mainClock.advanceTimeBy(100) }
    }

    @Test fun shortcutsRead() {
        assertEquals("Ctrl+N", shortcutText(MenuKey(Key.N), mac = false))
        assertEquals("⌘N", shortcutText(MenuKey(Key.N), mac = true))
        assertEquals("Ctrl+,", shortcutText(MenuKey(Key.Comma), mac = false))
        assertEquals("Ctrl+=", shortcutText(MenuKey(Key.Equals), mac = false))
        assertEquals("Alt+←", shortcutText(MenuKey(Key.DirectionLeft, command = false, alt = true), mac = false))
        assertEquals("⌥→", shortcutText(MenuKey(Key.DirectionRight, command = false, alt = true), mac = true))
    }

    @Test fun linuxButtonLayout() {
        val all = listOf(WindowButton.Minimize, WindowButton.Maximize, WindowButton.Close)
        assertEquals(all to false, DeskOs.buttonLayout("'appmenu:minimize,maximize,close'\n"))
        assertEquals(listOf(WindowButton.Close) to false, DeskOs.buttonLayout("'appmenu:close'"))
        assertEquals(listOf(WindowButton.Close, WindowButton.Minimize, WindowButton.Maximize) to true, DeskOs.buttonLayout("'close,minimize,maximize:'"))
        assertEquals(listOf(WindowButton.Close) to true, DeskOs.buttonLayout("close:appmenu"))
        assertEquals(all to false, DeskOs.buttonLayout(null))
        assertEquals(all to false, DeskOs.buttonLayout("'appmenu:'"))
        assertEquals(all to false, DeskOs.kdeButtonLayout("MS", "HIAX"))
        assertEquals(listOf(WindowButton.Close, WindowButton.Maximize, WindowButton.Minimize) to true, DeskOs.kdeButtonLayout("XAI", "H"))
    }

    @Test fun windowPlacement() {
        val left = Rectangle(0, 0, 1920, 1040)
        val right = Rectangle(1920, -200, 2560, 1400)
        val screens = listOf(left, right)
        assertEquals(Rectangle(2200, 100, 1600, 900), DeskWindow.place(Rectangle(2200, 100, 1600, 900), screens, left, 800, 560))
        assertEquals(Rectangle(160, 70, 1600, 900), DeskWindow.place(Rectangle(9000, 100, 1600, 900), screens, left, 800, 560))
        assertEquals(Rectangle(0, 0, 1920, 1040), DeskWindow.place(Rectangle(-7, 0, 2560, 1400), screens, left, 800, 560))
        assertEquals(Rectangle(1920, 500, 1000, 700), DeskWindow.place(Rectangle(1800, 1000, 1000, 700), screens, left, 800, 560))
        assertEquals(Rectangle(96, 52, 1728, 936), DeskWindow.place(null, screens, left, 800, 560))
        assertEquals(Rectangle(0, 0, 640, 480), DeskWindow.place(Rectangle(0, 0, 1600, 900), listOf(Rectangle(0, 0, 640, 480)), Rectangle(0, 0, 640, 480), 800, 560))
    }

    @Test fun chromeChoice() {
        assertEquals(DeskWindow.Mode.Custom, DeskWindow.mode(mac = false, chosen = false, decorations = true))
        assertEquals(DeskWindow.Mode.Custom, DeskWindow.mode(mac = true, chosen = false, decorations = true))
        assertEquals(DeskWindow.Mode.MacStrip, DeskWindow.mode(mac = true, chosen = false, decorations = false))
        assertEquals(DeskWindow.Mode.System, DeskWindow.mode(mac = true, chosen = true, decorations = true))
        assertEquals(DeskWindow.Mode.System, DeskWindow.mode(mac = false, chosen = true, decorations = true))
        assertEquals(DeskWindow.Mode.System, DeskWindow.mode(mac = false, chosen = false, decorations = false))
    }

    @Test fun runtimeDetection() {
        val jetbrains = System.getProperty("java.vendor").orEmpty().contains("JetBrains")
        val supported = runCatching { com.jetbrains.JBR.isWindowDecorationsSupported() }.getOrDefault(false)
        println("RUNTIME ${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")} decorations=$supported")
        assertEquals(jetbrains && DeskOs.mac || jetbrains && DeskOs.windows, supported && !DeskOs.linux)
        assertEquals(if (supported) DeskWindow.Mode.Custom else if (DeskOs.mac) DeskWindow.Mode.MacStrip else DeskWindow.Mode.System, DeskWindow.mode(DeskOs.mac, false, supported))
    }

    @Test fun windowIcons() {
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 128, 256), DeskWindow.icons.map { (it as BufferedImage).width })
    }

    @Test fun menuModel() {
        data("model")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            DeskScale.reset()
            var labels = emptyMap<String, List<String>>()
            setContent { labels = deskMenus(app, kotlinx.coroutines.MainScope()).associate { m -> m.label to m.children.orEmpty().map { (if (it.enabled) "" else "-") + it.label + (it.keys?.let { k -> " " + shortcutText(k, false) } ?: "") } } }
            waitForIdle()
            assertEquals(listOf("File", "Edit", "View", "Playback", "Help"), labels.keys.toList())
            assertEquals(listOf("New Project… Ctrl+N", "Settings… Ctrl+,", "", "-Import Media…", "Import Project…", "Link Media Folder…", "", "-Export… Ctrl+E", "-Close Project Ctrl+W") + if (DeskOs.mac) emptyList() else listOf("", "Quit Ctrl+Q"), labels["File"])
            assertEquals(listOf("-Play", "-Go to Start", "-Go to End", "", "-Previous Bookmark Alt+←", "-Next Bookmark Alt+→"), labels["Playback"])
            assertEquals(listOf("Zoom In Ctrl+=", "Zoom Out Ctrl+-", "-Actual Size Ctrl+0"), labels["View"])
            assertEquals(9, labels["Edit"]!!.size)
            assertEquals(listOf("AbsentMotion Help", "About AbsentMotion", "Crash Logs"), labels["Help"])
        }
    }

    @Test fun barOnEverySize() {
        data("sizes")
        for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val app = DeskAppState()
            DeskWindow.chrome = windows
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            shot("${w}x$h-home-windows")
            runOnIdle { DeskWindow.chrome = DeskWindow.Chrome() }
            shot("${w}x$h-home-fallback")
            runOnIdle { DeskWindow.chrome = windows }
            burger()
            onAllNodesWithText("File", substring = false).onFirst().performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(300)
            shot("${w}x$h-home-burger-file")
            keys("esc")
            onAllNodesWithText("New Project…", substring = false).assertCountEquals(0)
            open(app)
            shot("${w}x$h-editor-windows")
            runOnIdle { DeskWindow.chrome = DeskWindow.Chrome() }
            shot("${w}x$h-editor-fallback")
            runOnIdle { DeskWindow.chrome = windows }
            burger()
            onAllNodesWithText("Playback", substring = false).onFirst().performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(300)
            shot("${w}x$h-editor-burger-playback")
            keys("esc")
            val editor = app.editor!!
            runOnIdle {
                editor.groupLayers(editor.scene.layers.take(2).map { it.id }.toSet())
                editor.enterGroup(editor.selectedId!!)
                editor.selectedId = null
            }
            mainClock.advanceTimeBy(500)
            assertTrue(editor.inGroup)
            onAllNodesWithContentDescription("Project settings").onFirst().performClick()
            mainClock.advanceTimeBy(500)
            assertTrue(DeskGear.open)
            onAllNodesWithText("Re-timing", substring = false).onFirst().assertExists()
            shot("${w}x$h-editor-group-gear")
            runOnIdle { DeskGear.open = false }
            mainClock.advanceTimeBy(500)
            shot("${w}x$h-editor-group")
            runBlocking { app.closeEditor() }
            mainClock.advanceTimeBy(500)
        }
        DeskWindow.chrome = DeskWindow.Chrome()
    }

    @Test fun barOnEveryOs() {
        data("chromes")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            for ((name, chrome) in chromes) { runOnIdle { DeskWindow.chrome = chrome }; shot("os-$name-home") }
            open(app)
            for ((name, chrome) in chromes) { runOnIdle { DeskWindow.chrome = chrome }; shot("os-$name-editor") }
            runOnIdle { DeskWindow.chrome = windows; DeskScale.set(150) }
            mainClock.advanceTimeBy(500)
            shot("os-windows-editor-zoom150")
            runOnIdle { DeskScale.reset(); DeskWindow.chrome = chromes.getValue("linux") }
            runOnIdle { app.openSettings() }
            mainClock.advanceTimeBy(800)
            shot("os-linux-editor-modal")
            onAllNodesWithContentDescription("Menu").onFirst().performClick()
            mainClock.advanceTimeBy(300)
            onAllNodesWithText("Playback", substring = false).assertCountEquals(0)
            runOnIdle { app.settingsOpen = false }
            mainClock.advanceTimeBy(500)
            DeskWindow.chrome = DeskWindow.Chrome()
        }
    }

    @Test fun dragAreaAndControls() {
        data("hits")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            DeskWindow.chrome = windows
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            open(app)
            fun pressAt(node: SemanticsNodeInteraction): Boolean? {
                DeskWindow.hit = null
                node.performMouseInput { moveTo(center); press() }
                val hit = DeskWindow.hit
                node.performMouseInput { release() }
                mainClock.advanceTimeBy(300)
                return hit
            }
            fun pressAt(x: Float, y: Float): Boolean? {
                DeskWindow.hit = null
                onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(x, y)); press() }
                val hit = DeskWindow.hit
                onAllNodes(isRoot()).onFirst().performMouseInput { release() }
                mainClock.advanceTimeBy(300)
                return hit
            }
            assertEquals(false, pressAt(600f, 20f))
            assertEquals(false, pressAt(onAllNodesWithText("Styles", substring = false).onFirst()))
            assertEquals(null, pressAt(onAllNodesWithText("Export", substring = false).onFirst()))
            runOnIdle { app.editor!!.exportOpen = false }
            mainClock.advanceTimeBy(500)
            Thread.sleep(350)
            assertEquals(null, pressAt(onAllNodesWithContentDescription("Menu").onFirst()))
            onAllNodesWithText("Playback", substring = false).assertCountEquals(1)
            keys("esc")
            assertEquals(null, pressAt(onAllNodesWithContentDescription("Undo", substring = true).onFirst()))
            assertEquals(null, pressAt(onAllNodesWithContentDescription("Exit to project list").onFirst()))
            waitUntil(timeoutMillis = 15_000) { app.editor == null }
            runOnIdle { app.openSettings() }
            mainClock.advanceTimeBy(800)
            assertEquals(false, pressAt(onAllNodesWithContentDescription("Menu").onFirst()))
            onAllNodesWithText("Playback", substring = false).assertCountEquals(0)
            runOnIdle { app.settingsOpen = false }
            mainClock.advanceTimeBy(500)
            burger()
            onAllNodesWithText("Playback", substring = false).assertCountEquals(1)
            onAllNodesWithContentDescription("Menu").onFirst().performClick()
            mainClock.advanceTimeBy(300)
            onAllNodesWithText("Playback", substring = false).assertCountEquals(0)
            DeskWindow.chrome = DeskWindow.Chrome()
        }
    }

    @Test fun burgerKeysAndClicks() {
        data("keys")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            burger()
            onAllNodesWithText("Edit", substring = false).assertCountEquals(1)
            keys("down", "right", "down", "down")
            shot("keys-file-settings-highlighted")
            keys("enter")
            mainClock.advanceTimeBy(500)
            assertTrue(app.settingsOpen)
            onAllNodesWithText("New Project…", substring = false).assertCountEquals(0)
            runOnIdle { app.settingsOpen = false }
            mainClock.advanceTimeBy(500)
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.frame = 10 }
            burger()
            keys("down", "down", "down", "right")
            onAllNodesWithText("Zoom In", substring = false).assertCountEquals(1)
            keys("left")
            onAllNodesWithText("Zoom In", substring = false).assertCountEquals(0)
            keys("down", "enter", "down", "down", "down")
            shot("keys-playback-go-to-end")
            keys("enter")
            mainClock.advanceTimeBy(500)
            assertEquals(editor.scene.lastFrame, editor.frame)
            burger()
            onAllNodesWithText("View", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(200)
            val before = DeskScale.percent
            onAllNodesWithText("Zoom In", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(500)
            assertNotEquals(before, DeskScale.percent)
            runOnIdle { DeskScale.reset() }
            burger()
            onAllNodesWithText("Edit", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(200)
            onAllNodesWithText("Undo", substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            onAllNodesWithText("Undo", substring = false).assertCountEquals(1)
            keys("esc")
            onAllNodesWithText("Undo", substring = false).assertCountEquals(0)
            assertFalse(DeskGear.open)
        }
    }
}
