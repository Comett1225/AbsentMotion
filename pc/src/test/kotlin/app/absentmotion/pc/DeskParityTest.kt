package app.absentmotion.pc

import app.absentmotion.editor.place
import app.absentmotion.editor.EditorPanel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskOs
import app.absentmotion.editor.ElementNav
import app.absentmotion.editor.canPaste
import app.absentmotion.editor.extractAudio
import app.absentmotion.editor.markBeats
import app.absentmotion.editor.savePreset
import app.absentmotion.editor.screenshotFrame
import app.absentmotion.library.PresetStore
import app.absentmotion.ui.editor.importSvgFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.model.shownName
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.addPicked
import app.absentmotion.ui.editor.rightClick
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DeskParityTest {
    private val repo = File("..").canonicalFile
    private val commandKey = if (DeskOs.mac) Key.MetaLeft else Key.CtrlLeft
    private val commandMask = if (DeskOs.mac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    private val created = mutableListOf<Pair<DeskAppState, String>>()

    private fun DeskAppState.make(scene: Scene): String = store.create(scene).also { created += this to it }

    @After fun removeWhatTheTestsCreated() {
        for ((app, id) in created) runCatching { app.store.delete(id) }
        created.clear()
    }

    private fun data(name: String) = System.setProperty("am.data", File("build/parity-$name-data").also { it.deleteRecursively() }.absolutePath)

    private fun absolute(scene: String): Scene = Scene.parse(
        repo.resolve("testdata/scenes/$scene.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/"),
    )

    private fun ComposeUiTest.open(app: DeskAppState, scene: String) {
        val id = app.make(Scene.parse(repo.resolve("testdata/scenes/$scene.xml").readText()))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
    }

    private fun press(app: DeskAppState, vk: Int, mods: Int) {
        val key = androidx.compose.ui.input.key.Key(vk)
        DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0, isMetaPressed = mods and InputEvent.META_DOWN_MASK != 0))
        DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0, isMetaPressed = mods and InputEvent.META_DOWN_MASK != 0))
    }

    @Test fun multiSelectHasGroupParentDeleteAlignAndDistribute() {
        data("multi")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            val count = editor.scene.layers.size
            runOnIdle { MultiSelect.ids = editor.scene.layers.take(3).map { it.id }.toSet(); MultiSelect.active = true }
            mainClock.advanceTimeBy(500)
            val labels = listOf("Group", "Group & Mask", "Group & Exclude", "Link to parent", "Delete selected layers", "Align left", "Align center", "Align right", "Align top", "Align middle", "Align bottom", "Distribute vertically", "Distribute horizontally")
            for (label in labels) assertTrue(label, onAllNodesWithText(label).filter(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            val before = editor.scene.node
            onAllNodesWithText("Align left").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            runOnIdle { assertTrue("align moved the layers", editor.scene.node !== before) }
            onAllNodesWithText("Group").filter(hasClickAction()).onFirst().performClick()
            waitForIdle()
            runOnIdle { assertEquals(count - 2, editor.scene.layers.size); assertFalse(MultiSelect.active) }
            runOnIdle { editor.undo(); MultiSelect.ids = editor.scene.layers.take(2).map { it.id }.toSet(); MultiSelect.active = true }
            mainClock.advanceTimeBy(300)
            onAllNodesWithText("Delete selected layers").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            waitForIdle()
            runOnIdle { assertEquals(count - 2, editor.scene.layers.size) }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun projectAndLayerCanBeRenamedInTheInspector() {
        data("rename")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            onAllNodesWithContentDescription("Project settings").onFirst().performClick()
            mainClock.advanceTimeBy(500)
            val name = onAllNodes(hasSetTextAction() and hasText("Styles")).onFirst()
            name.performClick()
            name.performTextReplacement("Renamed project")
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            runOnIdle { assertEquals("Renamed project", editor.scene.title) }
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
            mainClock.advanceTimeBy(500)
            runOnIdle { editor.selectedId = editor.scene.layers.last().id }
            mainClock.advanceTimeBy(500)
            val layer = onAllNodes(hasSetTextAction() and hasText(editor.selected!!.label)).onFirst()
            layer.performClick()
            layer.performTextReplacement("Hero layer")
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            runOnIdle { assertEquals("Hero layer", editor.selected!!.label) }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun copyPasteMenuAndParentMenuAreReachable() {
        data("menus")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            onNodeWithContentDescription("Copy and paste").performClick()
            mainClock.advanceTimeBy(300)
            for (label in listOf("Select All Layers", "Save Preset", "Paste Style")) assertTrue(label, onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
            onAllNodesWithText("Select All Layers").onFirst().performClick()
            mainClock.advanceTimeBy(300)
            runOnIdle { assertTrue(MultiSelect.active); MultiSelect.clear(); editor.selectedId = editor.scene.layers.last().id }
            mainClock.advanceTimeBy(500)
            val chosen = editor.selected!!
            val other = editor.scene.layers[editor.scene.layers.lastIndex - 1]
            onNodeWithContentDescription("Link to parent").performClick()
            mainClock.advanceTimeBy(300)
            onAllNodesWithText(other.shownName()).onLast().performScrollTo().performClick()
            mainClock.advanceTimeBy(300)
            runOnIdle { assertEquals(other.id.toString(), editor.scene.layer(chosen.id)!!.node["parent"]) }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun canvasZoomsAndPansWithTheMouse() {
        data("canvas")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            val centre = DeskPreviewBounds.bounds.center
            onRoot().performMultiModalInput {
                mouse { moveTo(centre) }
                key { keyDown(commandKey) }
                mouse { scroll(-2f) }
                key { keyUp(commandKey) }
            }
            runOnIdle { assertTrue("zoomed in", editor.canvasZoom > 1f) }
            onRoot().performMouseInput { moveTo(centre); scroll(3f) }
            runOnIdle { assertNotEquals(Offset.Zero, editor.canvasPan) }
            runOnIdle { editor.resetCanvasView(); editor.selectedId = null }
            mainClock.advanceTimeBy(300)
            onRoot().performMouseInput { moveTo(centre); press(MouseButton.Primary); moveTo(centre + Offset(60f, 30f), 200); release(MouseButton.Primary) }
            runOnIdle { assertNotEquals("dragging empty canvas pans", Offset.Zero, editor.canvasPan) }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun effectsMenuOpensAtItsButton() {
        data("effects-menu")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            runOnIdle { editor.selectedId = editor.scene.layers.last().id; editor.panel = EditorPanel.Effects }
            mainClock.advanceTimeBy(500)
            val more = onAllNodesWithContentDescription("More").filter(hasClickAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.left }
            val button = more.boundsInRoot
            onRoot().performMouseInput { click(button.center) }
            mainClock.advanceTimeBy(500)
            val menu = onAllNodesWithText("Copy Effects").onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue("menu at ${menu.left}, button at ${button.left}", menu.left > button.left - 60f && menu.left < button.right)
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun draggingThePreviewMovesEverySelectedLayer() {
        data("multi-move")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            val chosen = editor.scene.layers.filter { it.kind != "camera" && it.kind != "audio" }.take(2).map { it.id }
            val before = chosen.associateWith { editor.scene.place(editor.scene.layer(it)!!, 0) }
            runOnIdle { editor.selectedId = null; MultiSelect.ids = chosen.toSet(); MultiSelect.active = true }
            mainClock.advanceTimeBy(800)
            val centre = DeskPreviewBounds.bounds.center
            onRoot().performMouseInput { moveTo(centre); press(MouseButton.Primary); moveTo(centre + Offset(60f, 30f), 200); release(MouseButton.Primary) }
            mainClock.advanceTimeBy(300)
            runOnIdle {
                val moved = chosen.map { editor.scene.place(editor.scene.layer(it)!!, 0).x - before.getValue(it).x }
                assertTrue("moved $moved", moved.all { it > 1f })
                assertEquals(moved[0], moved[1], 0.5f)
                assertEquals(Offset.Zero, editor.canvasPan)
                MultiSelect.clear()
            }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun pinchingSeveralLayersFollowsTheFingersAndStaysOnRelease() {
        data("multi-pinch")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app, "styles")
            val editor = app.editor!!
            val chosen = editor.scene.layers.filter { it.kind != "camera" && it.kind != "audio" }.take(2).map { it.id }
            fun places() = chosen.map { editor.scene.place(editor.scene.layer(it)!!, 0).let { p -> Offset(p.x, p.y) } }
            val before = runOnIdle { places() }
            runOnIdle { editor.selectedId = null; MultiSelect.ids = chosen.toSet(); MultiSelect.active = true }
            mainClock.advanceTimeBy(800)
            val centre = DeskPreviewBounds.bounds.center
            fun pinch(drift: Float): Pair<List<Offset>, List<Offset>> {
                onRoot().performTouchInput {
                    down(0, centre + Offset(-50f, 0f))
                    down(1, centre + Offset(50f, 0f))
                    for (i in 1..12) {
                        val t = i / 12f
                        updatePointerTo(0, centre + Offset(-50f - 100f * t, drift * t))
                        updatePointerTo(1, centre + Offset(50f + 100f * t, drift * t))
                        move()
                    }
                }
                mainClock.advanceTimeBy(100)
                val held = runOnIdle { places() }
                onRoot().performTouchInput { up(0); up(1) }
                mainClock.advanceTimeBy(300)
                return held to runOnIdle { places() }
            }
            val (heldStill, leftStill) = pinch(0f)
            val spread = heldStill[1] - heldStill[0]
            val start = before[1] - before[0]
            assertEquals(start.x * 3f, spread.x, 1f)
            assertEquals(start.y * 3f, spread.y, 1f)
            for (k in chosen.indices) assertTrue("layer $k after release: ${heldStill[k]} then ${leftStill[k]}", (heldStill[k] - leftStill[k]).getDistance() < 0.01f)
            runOnIdle { editor.undo() }
            mainClock.advanceTimeBy(100)
            assertEquals(before, runOnIdle { places() })
            val (heldDown, leftDown) = pinch(60f)
            for (k in chosen.indices) {
                val shift = heldDown[k] - heldStill[k]
                assertEquals("layer $k sideways", 0f, shift.x, 0.5f)
                assertTrue("layer $k follows the fingers down: $shift", shift.y > 1f)
                assertTrue("layer $k after release: ${heldDown[k]} then ${leftDown[k]}", (heldDown[k] - leftDown[k]).getDistance() < 0.01f)
            }
            runOnIdle { MultiSelect.clear() }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun cutKeyRemovesTheLayerAndKeepsItForPaste() {
        data("cut")
        val app = DeskAppState()
        val id = app.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val count = state.scene.layers.size
        state.selectedId = state.scene.layers.last().id
        press(app, java.awt.event.KeyEvent.VK_X, commandMask)
        assertEquals(count - 1, state.scene.layers.size)
        assertTrue(state.canPaste)
        press(app, java.awt.event.KeyEvent.VK_V, commandMask)
        assertEquals(count, state.scene.layers.size)
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun collectMediaCopiesFilesIntoTheProject() {
        data("collect")
        val app = DeskAppState()
        val id = app.make(absolute("media"))
        val before = app.store.load(id)!!
        assertTrue(before.media.any { !app.store.inMediaFolder(it.uri) })
        runBlocking { app.collectMedia(id) }
        val after = app.store.load(id)!!
        assertTrue(after.media.joinToString { it.uri }, after.media.all { app.store.inMediaFolder(it.uri) })
        app.engine.close()
    }

    @Test fun weeklyBackupAndSweepsRunOnDesktop() {
        data("maintain")
        System.setProperty("am.exports", File("build/parity-maintain-exports").also { it.deleteRecursively() }.absolutePath)
        val app = DeskAppState()
        app.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText()))
        app.settings.weeklyBackup = true
        app.settings.lastAutoBackup = 0L
        try {
            runBlocking { app.maintainNow() }
            assertFalse("backup failed", app.settings.autoBackupFailed)
            assertTrue(app.settings.lastAutoBackup > 0L)
        } finally {
            app.settings.weeklyBackup = false
            app.settings.lastAutoBackup = 0L
            app.settings.autoBackupFailed = false
            app.engine.close()
        }
    }

    @Test fun unsavedEditsAreOfferedAfterACrashAndRestored() {
        data("recovery")
        val first = DeskAppState()
        val id = first.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText()))
        val edited = first.store.load(id)!!.withTitle("Recovered title")
        first.recovery.write(id, edited.toXml())
        first.recovery.file(id).setLastModified(System.currentTimeMillis() + 3_600_000)
        val app = DeskAppState()
        runBlocking { app.startup() }
        val offer = app.recovered
        assertNotNull(offer)
        assertEquals(id, offer!!.id)
        runBlocking { app.answerRecovery(offer, true) }
        assertEquals("Recovered title", app.store.load(id)!!.title)
        assertEquals(id, app.editor?.projectId)
        assertEquals(null, app.recovered)
        runBlocking { app.closeEditor() }
        app.engine.close()
        first.engine.close()
    }

    @Test fun openOriginalOfALinkedAssetWorksOnDesktop() {
        data("element")
        val app = DeskAppState()
        val one = app.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText()))
        val two = app.make(Scene.parse(repo.resolve("testdata/scenes/shapes.xml").readText()))
        runBlocking { app.startup(); app.openNow(one) }
        assertNotNull(ElementNav.open)
        ElementNav.open!!.invoke(two)
        runBlocking { withTimeout(20_000) { while (app.editor?.projectId != two) delay(50) } }
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun rightClickRunsTheActionAndLeftClickStillWorks() {
        runSkikoComposeUiTest(size = Size(300f, 200f)) {
            var right = 0
            var left = 0
            var control = 0
            setContent {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.size(100.dp).testTag("both").rightClick { right++ }.clickable { left++ })
                    Box(Modifier.size(100.dp).testTag("control").clickable { control++ })
                }
            }
            onNodeWithTag("control").performClick()
            mainClock.advanceTimeBy(200)
            runOnIdle { assertEquals(1, control) }
            onNodeWithTag("both").performClick()
            mainClock.advanceTimeBy(200)
            runOnIdle { assertEquals(0, right); assertEquals(1, left) }
            onNodeWithTag("both").performMouseInput { rightClick() }
            mainClock.advanceTimeBy(200)
            runOnIdle { assertEquals(1, right); assertEquals(1, left) }
            onNodeWithTag("both").performClick()
            mainClock.advanceTimeBy(200)
            runOnIdle { assertEquals(1, right); assertEquals(2, left) }
        }
    }

    private fun animatedGif(file: File) {
        file.parentFile.mkdirs()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file).use { out ->
            writer.output = out
            writer.prepareWriteSequence(null)
            for (i in 0 until 4) {
                val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
                for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, if ((x / 8 + y / 8 + i) % 2 == 0) 0xffff3300.toInt() else 0)
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val format = meta.nativeMetadataFormatName
                val root = meta.getAsTree(format) as IIOMetadataNode
                val control = (root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).also { it.setAttribute("delayTime", "10") }
                check(control.getAttribute("delayTime") == "10")
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(image, null, meta), null)
            }
            writer.endWriteSequence()
        }
    }

    @Test fun animatedGifImportNeverBreaksTheEditor() {
        data("gif")
        val file = File("build/parity-gif/loop.gif").absoluteFile
        animatedGif(file)
        val app = DeskAppState()
        val id = app.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val before = state.scene.layers.size
        val outcome = runCatching { runBlocking { state.addPicked(android.content.Desk.context, android.net.Uri.fromFile(file)) } }
        assertTrue("gif import threw " + outcome.exceptionOrNull(), outcome.isSuccess)
        assertTrue(state.scene.layers.size > before)
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun layerMenuActionsRunOnDesktop() {
        data("actions")
        System.setProperty("am.exports", File("build/parity-actions-exports").also { it.deleteRecursively() }.absolutePath)
        val context = android.content.Desk.context
        val app = DeskAppState()
        val state = runBlocking { app.openNow(app.make(absolute("media")))!! }
        val results = LinkedHashMap<String, String>()
        fun attempt(name: String, block: () -> Unit) { results[name] = runCatching(block).fold({ "ok" }, { it.toString() }) }
        val video = state.scene.layers.first { it.mediaFill?.video == true }
        val sound = state.scene.layers.first { it.kind == "audio" }
        attempt("extract audio") { state.selectedId = video.id; val n = state.scene.layers.size; state.extractAudio(); check(state.scene.layers.size == n + 1) }
        attempt("screenshot") { state.selectedId = video.id; check(runBlocking { state.screenshotFrame(context, CoroutineScope(Dispatchers.Default)) }) }
        attempt("mark beats") { state.selectedId = sound.id; runBlocking { state.markBeats(context) } }
        attempt("svg") {
            val svg = File("build/parity-actions-svg/box.svg").also { it.parentFile.mkdirs(); it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" fill=\"#ff0000\"/></svg>") }
            val n = state.scene.layers.size
            runBlocking { state.importSvgFile(context, android.net.Uri.fromFile(svg)) }
            check(state.scene.layers.size > n)
        }
        runBlocking { app.closeEditor() }
        val styles = runBlocking { app.openNow(app.make(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText())))!! }
        attempt("save preset") {
            styles.selectedId = styles.scene.layers.first().id
            val saved = runBlocking { styles.savePreset(PresetStore(context)) }
            check(saved != null)
            PresetStore(context).delete(saved.id)
        }
        assertEquals(results.toString(), emptyList<String>(), results.filterValues { it != "ok" }.map { it.key + ": " + it.value })
        runBlocking { app.closeEditor() }
        app.engine.close()
    }

    @Test fun screenshotsOfTheNewDesktopControls() {
        data("shots")
        val dir = File("build/ui-shots/parity").also { it.mkdirs() }
        for ((width, height) in listOf(1280 to 800, 1024 to 700)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                fun shot(name: String) {
                    waitForIdle()
                    javax.imageio.ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("${width}x$height-$name.png"))
                }
                val app = DeskAppState()
                setContent { DeskApp(app) }
                open(app, "styles")
                val editor = app.editor!!
                shot("project")
                runOnIdle { editor.selectedId = editor.scene.layers.last().id }
                mainClock.advanceTimeBy(500)
                shot("layer")
                onNodeWithContentDescription("Copy and paste").performClick()
                mainClock.advanceTimeBy(400)
                shot("copy-paste")
                onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(4f, 4f)); click() }
                mainClock.advanceTimeBy(400)
                onNodeWithContentDescription("Link to parent").performClick()
                mainClock.advanceTimeBy(400)
                shot("parent")
                onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(4f, 4f)); click() }
                mainClock.advanceTimeBy(400)
                runOnIdle { MultiSelect.ids = editor.scene.layers.take(3).map { it.id }.toSet(); MultiSelect.active = true }
                mainClock.advanceTimeBy(500)
                shot("multi")
                runOnIdle { MultiSelect.clear() }
                runBlocking { app.closeEditor() }
            }
        }
    }
}
