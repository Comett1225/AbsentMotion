package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskKeys
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.editor.DeskTimelineControls
import app.absentmotion.ui.editor.DeskRuler
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.Timeline
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskTimelineTest {
    private val commandKey = if (System.getProperty("os.name").lowercase().contains("mac")) Key.MetaLeft else Key.CtrlLeft

    @Before @After fun resetHeader() { DeskTimelineControls.headerDp = 180f }

    @Test fun mouseEditingAndScreenshots() {
        for (width in listOf(1280, 2560)) {
            System.setProperty("am.data", File("build/timeline-data-$width").absolutePath)
            val app = DeskAppState()
            val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
            val state = runBlocking { app.openNow(id)!! }
            val topId = state.scene.layers.last().id
            runSkikoComposeUiTest(size = Size(width.toFloat(), 700f)) {
                setContent {
                    CompositionLocalProvider(LocalWide provides true) {
                        Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                    }
                }
                waitForIdle()
                val shots = File("build/ui-shots/timeline").also { it.mkdirs() }
                val first = onRoot().captureToImage().toAwtImage()
                ImageIO.write(first, "png", shots.resolve("timeline-$width.png"))
                val lit = (184..230).flatMap { x -> (2..18).map { y -> first.getRGB(x, y) } }.count { (it shr 8 and 0xFF) > 110 }
                assertTrue("ruler label pixels $lit", lit > 8)
                runOnIdle { state.timelineZoom = 100f }
                onRoot().performMouseInput { moveTo(Offset(230f, 57f)); press(); moveTo(Offset(280f, 57f)); release() }
                runOnIdle { assertTrue(state.scene.layer(topId)!!.startTime > 0); assertTrue(state.canUndo); state.undo() }
                mainClock.autoAdvance = false
                onRoot().performMouseInput { moveTo(Offset(230f, 57f)); press(); moveTo(Offset(280f, 57f)) }
                runOnIdle { assertTrue(state.scene.layer(topId)!!.startTime > 0); assertTrue(DeskBack.back()); assertEquals(0, state.scene.layer(topId)!!.startTime) }
                onRoot().performMouseInput { release() }
                mainClock.autoAdvance = true
                onRoot().performMouseInput { moveTo(Offset(480f, 57f)); press(); moveTo(Offset(430f, 57f)); release() }
                runOnIdle { assertTrue(state.scene.layer(topId)!!.endTime < 2999); state.undo(); state.clearForTest() }
                onRoot().performMouseInput { moveTo(Offset(280f, 8f)); press(); release() }
                runOnIdle { assertEquals(30, state.frame) }
                onRoot().performMouseInput { moveTo(Offset(230f, 57f)); press(); moveTo(Offset(325f, 57f)); release() }
                runOnIdle { assertEquals(30, state.scene.firstShownFrame(state.scene.layer(topId)!!.startTime)); state.undo(); state.clearForTest() }
                onRoot().performMultiModalInput {
                    key { keyDown(Key.AltLeft) }
                    mouse { moveTo(Offset(230f, 57f)); press(); moveTo(Offset(325f, 57f)); release() }
                    key { keyUp(Key.AltLeft) }
                }
                runOnIdle { assertEquals(29, state.scene.firstShownFrame(state.scene.layer(topId)!!.startTime)); state.undo(); state.clearForTest() }
                onRoot().performMouseInput { moveTo(Offset(280f, 57f)); press(); moveTo(Offset(280f, 92f)); release() }
                runOnIdle { assertTrue(state.scene.layers.last().id != topId); state.undo(); state.clearForTest() }
                onRoot().performMultiModalInput {
                    key { keyDown(commandKey) }
                    mouse { moveTo(Offset(250f, 57f)); press(); release(); moveTo(Offset(250f, 87f)); press(); release() }
                    key { keyUp(commandKey) }
                }
                runOnIdle { assertEquals(2, MultiSelect.ids.size); state.clearForTest() }
                onRoot().performMouseInput { moveTo(Offset(500f, 50f)); press(); moveTo(Offset(250f, 110f)); release() }
                runOnIdle { assertTrue("rubber selection=${MultiSelect.ids} zoom=${state.timelineZoom} frame=${state.frame}", MultiSelect.ids.size >= 2); state.clearForTest() }
                onRoot().performMouseInput { moveTo(Offset(250f, 57f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
                runOnIdle { assertTrue(DeskBack.back()) }
                assertTrue(onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
                onRoot().performMouseInput { moveTo(Offset(250f, 57f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
                onNodeWithText("Delete").performClick()
                runOnIdle { assertTrue(state.scene.layer(topId) == null); state.undo(); assertTrue(state.scene.layer(topId) != null) }
            }
            MultiSelect.clear()
            app.engine.close()
        }
    }

    @Test fun rulerStepsAndLabels() {
        val fit = DeskRuler.steps(30, 0.6f)
        assertEquals(150, fit.major)
        assertEquals(15, fit.minor)
        val frames = DeskRuler.steps(30, 40f)
        assertEquals(2, frames.major)
        assertEquals(1, frames.minor)
        val mid = DeskRuler.steps(30, 4f)
        assertEquals(30, mid.major)
        assertEquals(3, mid.minor)
        assertEquals("0:05", DeskRuler.label(150, 30, 150))
        assertEquals("2:10", DeskRuler.label(30 * 130, 30, 150))
        assertEquals("1:01:40", DeskRuler.label(30 * 3700, 30, 150))
        assertEquals("00:00:07", DeskRuler.label(7, 30, 2))
    }

    @Test fun rulerScrubHeaderResizeAndReorder() {
        System.setProperty("am.data", File("build/timeline-ruler-data").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val topId = state.scene.layers.last().id
        runSkikoComposeUiTest(size = Size(1280f, 400f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            runOnIdle { state.timelineZoom = 100f }
            onRoot().performMouseInput { moveTo(Offset(250f, 8f)); press(); moveTo(Offset(400f, 8f)); release() }
            runOnIdle { assertEquals(66, state.frame) }
            onRoot().performMouseInput { moveTo(Offset(100f, 57f)); press(); moveTo(Offset(100f, 92f)); release() }
            runOnIdle { assertTrue(state.scene.layers.last().id != topId); state.undo(); state.clearForTest() }
            runOnIdle { assertTrue(DeskDrop.frameAt(Offset(220f, 100f)) != null) }
            onRoot().performMouseInput { moveTo(Offset(178f, 100f)); press(); moveTo(Offset(300f, 100f)); release() }
            runOnIdle { assertEquals(null, DeskDrop.frameAt(Offset(220f, 100f))); assertTrue(DeskDrop.frameAt(Offset(320f, 100f)) != null) }
            onRoot().performMouseInput { moveTo(Offset(30f, 20f)); press(); release() }
            runOnIdle { state.timelineZoom = 1000f; state.frame = 60; state.playing = true }
            waitForIdle()
            runOnIdle { assertTrue(DeskDrop.frameAt(Offset(400f, 100f))!! >= 60); state.playing = false }
        }
        MultiSelect.clear()
        app.engine.close()
    }

    @Test fun dragAndMenuScreenshots() {
        System.setProperty("am.data", File("build/timeline-drag-data").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        runSkikoComposeUiTest(size = Size(1280f, 420f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            val shots = File("build/ui-shots/timeline").also { it.mkdirs() }
            fun shot(name: String) = ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
            runOnIdle { state.timelineZoom = 100f }
            onRoot().performMouseInput { moveTo(Offset(300f, 87f)) }
            waitForIdle()
            shot("timeline-hover")
            mainClock.autoAdvance = false
            onRoot().performMouseInput { moveTo(Offset(300f, 87f)); press(); moveTo(Offset(380f, 72f)); moveTo(Offset(400f, 80f)) }
            mainClock.advanceTimeBy(200)
            shot("timeline-dragging")
            onRoot().performMouseInput { release() }
            mainClock.autoAdvance = true
            runOnIdle { if (state.canUndo) state.undo(); state.clearForTest() }
            onRoot().performMouseInput { moveTo(Offset(250f, 57f)); press(); release() }
            waitForIdle()
            shot("timeline-selected")
            runOnIdle { state.clearForTest() }
            onRoot().performMouseInput { moveTo(Offset(500f, 50f)); press(); moveTo(Offset(300f, 150f)) }
            waitForIdle()
            shot("timeline-rubber")
            onRoot().performMouseInput { release() }
            onRoot().performMouseInput { moveTo(Offset(300f, 87f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
            waitForIdle()
            shot("timeline-menu")
            runOnIdle { DeskBack.back() }
            runOnIdle { state.clearForTest() }
        }
        MultiSelect.clear()
        app.engine.close()
    }

    @Test fun keyboardNudgeAndSelectAll() {
        System.setProperty("am.data", File("build/timeline-keys-data").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val layer = state.scene.layers.last()
        state.selectedId = layer.id
        runSkikoComposeUiTest(size = Size(800f, 300f)) {
            setContent {
                val focus = remember { FocusRequester() }
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { DeskKeys.handle(app, it) }.focusable()) { Timeline(state, Modifier.fillMaxSize()) }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            onRoot().performKeyInput { keyDown(Key.AltLeft); keyDown(Key.DirectionRight); keyUp(Key.DirectionRight); keyUp(Key.AltLeft) }
            runOnIdle { assertEquals(1, state.scene.firstShownFrame(state.scene.layer(layer.id)!!.startTime)); state.undo(); assertEquals(0, state.scene.firstShownFrame(state.scene.layer(layer.id)!!.startTime)) }
            onRoot().performKeyInput { keyDown(Key.ShiftLeft); keyDown(Key.AltLeft); keyDown(Key.DirectionRight); keyUp(Key.DirectionRight); keyUp(Key.AltLeft); keyUp(Key.ShiftLeft) }
            runOnIdle { assertEquals(10, state.scene.firstShownFrame(state.scene.layer(layer.id)!!.startTime)); state.undo() }
            val command = if (System.getProperty("os.name").lowercase().contains("mac")) Key.MetaLeft else Key.CtrlLeft
            onRoot().performKeyInput { keyDown(command); keyDown(Key.A); keyUp(Key.A); keyUp(command) }
            runOnIdle { assertEquals(state.scene.layers.size, MultiSelect.ids.size); MultiSelect.clear() }
            val fitZoom = state.timelineZoom
            onRoot().performKeyInput { keyDown(Key.Equals); keyUp(Key.Equals) }
            runOnIdle { assertTrue(state.timelineZoom > fitZoom) }
            onRoot().performKeyInput { keyDown(Key.ShiftLeft); keyDown(Key.Z); keyUp(Key.Z); keyUp(Key.ShiftLeft) }
            runOnIdle { assertEquals(fitZoom, state.timelineZoom, 0.01f) }
        }
        MultiSelect.clear()
        app.engine.close()
    }

    @Test fun wheelAndDropMapping() {
        System.setProperty("am.data", File("build/timeline-wheel-data").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        runSkikoComposeUiTest(size = Size(1280f, 300f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            runOnIdle { state.timelineZoom = 1000f }
            val before = DeskDrop.frameAt(Offset(400f, 100f))!!
            assertEquals(null, DeskDrop.frameAt(Offset(100f, 100f)))
            onRoot().performMouseInput { moveTo(Offset(400f, 100f)); scroll(5f) }
            assertTrue(onAllNodesWithContentDescription("Flipped").fetchSemanticsNodes().isEmpty())
            onRoot().performMouseInput { scroll(-5f) }
            onRoot().performMultiModalInput {
                mouse { moveTo(Offset(400f, 100f)) }
                key { keyDown(Key.ShiftLeft) }
                mouse { scroll(4f) }
                key { keyUp(Key.ShiftLeft) }
            }
            runOnIdle { assertTrue(DeskDrop.frameAt(Offset(400f, 100f))!! > before) }
            val zoom = state.timelineZoom
            onRoot().performMultiModalInput {
                key { keyDown(Key.MetaLeft) }
                mouse { scroll(1f) }
                key { keyUp(Key.MetaLeft) }
            }
            runOnIdle { assertTrue(state.timelineZoom != zoom) }
        }
        assertEquals(null, DeskDrop.frameAt(Offset(400f, 100f)))
        app.engine.close()
    }

    @Test fun keyframeMovesImmediately() {
        System.setProperty("am.data", File("build/timeline-keyframe-data").absolutePath)
        val app = DeskAppState()
        val xml = File("../testdata/scenes/styles.xml").readText().replace("t=\"1.000000\"", "t=\"0.500000\"")
        val source = Scene.parse(xml)
        val scene = Scene(source.node.copy(children = source.node.children.filter { it["id"] == "7006" }))
        val id = app.store.create(scene)
        val state = runBlocking { app.openNow(id)!! }
        val before = state.scene.layer(7006L)!!.keyframeTimes().maxOrNull()!!
        runSkikoComposeUiTest(size = Size(1280f, 300f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            runOnIdle { state.timelineZoom = 100f }
            onRoot().performMouseInput { moveTo(Offset(330f, 57f)); press(); moveTo(Offset(370f, 57f)); release() }
            runOnIdle { assertTrue(state.scene.layer(7006L)!!.keyframeTimes().maxOrNull()!! > before); state.undo(); assertEquals(before, state.scene.layer(7006L)!!.keyframeTimes().maxOrNull()) }
        }
        app.engine.close()
    }

    @Test fun sizingScreenshots() {
        System.setProperty("am.data", File("build/timeline-sizing-data").absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        val state = runBlocking { app.openNow(id)!! }
        val sizes = listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)
        for (density in listOf(1f, 2f)) for ((width, height) in sizes) {
            runSkikoComposeUiTest(size = Size(width * density, height * density * 0.34f)) {
                setContent {
                    CompositionLocalProvider(LocalWide provides true, LocalDensity provides Density(density)) {
                        Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                    }
                }
                waitForIdle()
                val shots = File("build/ui-shots/timeline").also { it.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("timeline-${width}x${height}-${density.toInt()}x.png"))
            }
        }
        app.engine.close()
    }

    @Test fun videoStripAppears() {
        System.setProperty("am.data", File("build/timeline-media-data").absolutePath)
        val app = DeskAppState()
        val repo = File("..").canonicalFile
        val xml = File("../testdata/scenes/media.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        val id = app.store.create(Scene.parse(xml))
        val state = runBlocking { app.openNow(id)!! }
        runSkikoComposeUiTest(size = Size(1280f, 700f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            Thread.sleep(12000)
            waitForIdle()
            val image = onRoot().captureToImage().toAwtImage()
            val shots = File("build/ui-shots/timeline").also { it.mkdirs() }
            ImageIO.write(image, "png", shots.resolve("timeline-video.png"))
            val colors = (240 until 800 step 3).map { image.getRGB(it, 117) }.toSet()
            assertTrue("video strip contains decoded pixels", colors.size > 20)
        }
        app.engine.close()
    }
}

private fun app.absentmotion.editor.EditorState.clearForTest() {
    selectedId = null
    MultiSelect.clear()
}
