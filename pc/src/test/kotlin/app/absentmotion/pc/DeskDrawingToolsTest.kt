package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskOs
import app.absentmotion.editor.FreehandMode
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.Pressure
import app.absentmotion.editor.pointContours
import app.absentmotion.editor.startFreehand
import app.absentmotion.editor.startVectorDrawing
import app.absentmotion.model.Contour
import app.absentmotion.model.DrawTool
import app.absentmotion.model.Handle
import app.absentmotion.model.Knot
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.Stroke
import app.absentmotion.model.StrokePoint
import app.absentmotion.model.XmlNode
import app.absentmotion.model.caughtUp
import app.absentmotion.model.smoothTo
import app.absentmotion.model.speedPressure
import app.absentmotion.model.strokes
import app.absentmotion.model.touchedBy
import app.absentmotion.model.withHandlesPulled
import app.absentmotion.model.withStroke
import app.absentmotion.model.withoutStrokes
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.DrawingKeys
import app.absentmotion.ui.editor.angleSnapped
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DeskDrawingToolsTest {
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/drawing").also { it.mkdirs() }
    private val created = mutableListOf<Pair<DeskAppState, String>>()

    @After fun removeWhatTheTestsCreated() {
        for ((app, id) in created) runCatching { app.store.delete(id) }
        created.clear()
    }

    private fun line(vararg xy: Float) = xy.toList().chunked(2).map { StrokePoint(it[0], it[1], 1f) }

    @Test fun smoothingZeroKeepsTheRawLineAndMoreSmoothingCalmsJitter() {
        val raw = (0..40).map { StrokePoint(it * 5f, if (it % 2 == 0) 4f else -4f, 0.5f) }
        fun List<StrokePoint>.smoothed(amount: Float, finish: Boolean) = ArrayList<StrokePoint>().also { it.smoothTo(this, amount) }.let { if (finish) it.caughtUp(last()) else it }
        assertEquals(raw, raw.smoothed(0f, true))
        val calm = raw.smoothed(0.6f, false)
        assertEquals(raw.size, calm.size)
        assertTrue(calm.drop(5).all { abs(it.y) < 2f })
        val done = raw.smoothed(0.6f, true)
        assertEquals(raw.last().x, done.last().x, 0.01f)
        assertEquals(raw.last().y, done.last().y, 0.01f)
        fun jitter(p: List<StrokePoint>) = p.zipWithNext { u, v -> abs(v.y - u.y) }.drop(5).max()
        assertTrue(jitter(raw.smoothed(1f, false)) < jitter(calm))
        assertTrue(jitter(calm) < jitter(raw) / 3)
    }

    @Test fun speedPressureThinsFastStrokesAndStaysFullWhenSlow() {
        var slow = 1f
        repeat(20) { slow = speedPressure(slow, 0.2f, 16) }
        var fast = 1f
        repeat(20) { fast = speedPressure(fast, 60f, 16) }
        assertEquals(1f, slow, 0.01f)
        assertEquals(0.2f, fast, 0.02f)
    }

    @Test fun hardnessIsSavedOnlyWhenSoftAndOldStrokesReadAsHard() {
        val hard = Stroke(line(0f, 0f, 10f, 10f), 0xFFFF0000.toInt(), DrawTool.Brush, 8f)
        assertNull(hard.toNode()["hardness"])
        val soft = hard.copy(hardness = 0.6f)
        assertEquals("0.60", soft.toNode()["hardness"])
        assertEquals(0.6f, Stroke.parse(soft.toNode()).hardness, 1e-4f)
        assertEquals(hard, Stroke.parse(hard.toNode()))
        val old = XmlNode("stroke", mapOf("color" to "#ffff0000", "width" to "8.0", "type" to "brush", "points" to "0.00,0.00,1.00;10.00,10.00,1.00"))
        assertEquals(1f, Stroke.parse(old).hardness)
        assertEquals(0f, Stroke.parse(old.with("hardness", "-3")).hardness)
    }

    @Test fun wholeStrokeEraserFindsOnlyTheStrokesItTouches() {
        val strokes = listOf(
            Stroke(line(0f, 0f, 100f, 0f), -1, DrawTool.Pen, 4f),
            Stroke(line(0f, 50f, 100f, 50f), -1, DrawTool.Brush, 10f),
            Stroke(line(200f, 0f, 300f, 0f, 300f, 100f, 200f, 100f), -1, DrawTool.Fill, 4f),
            Stroke(line(0f, -20f, 0f, 200f), -1, DrawTool.Eraser, 30f),
            Stroke(line(400f, 400f), -1, DrawTool.Pen, 20f),
        )
        assertEquals(setOf(0), strokes.touchedBy(line(50f, -20f, 50f, 20f), 3f))
        assertEquals(setOf(0, 1), strokes.touchedBy(line(50f, -20f, 50f, 60f), 3f))
        assertEquals(setOf(2), strokes.touchedBy(line(250f, 40f, 260f, 60f), 2f))
        assertEquals(setOf(4), strokes.touchedBy(line(415f, 400f), 6f))
        assertEquals(emptySet<Int>(), strokes.touchedBy(line(150f, 20f, 150f, 30f), 5f))
        assertEquals(setOf(0, 1), strokes.touchedBy(line(-500f, 25f, 50f, 25f, 50f, 25f), 30f))
        val layer = strokes.fold(Layer(XmlNode("drawing", mapOf("id" to "7"), listOf(XmlNode("transform"))))) { l, s -> l.withStroke(s) }
        val left = layer.withoutStrokes(setOf(0, 2))
        assertEquals(listOf(strokes[1], strokes[3], strokes[4]), left.strokes)
        assertEquals("transform", left.node.children.first().name)
    }

    @Test fun penHelpersPullHandlesAndSnapToFortyFiveDegrees() {
        val c = Contour(listOf(Knot(Pt(0f, 0f)), Knot(Pt(100f, 0f))), closed = false)
        val pulled = c.withHandlesPulled(1, Pt(130f, 40f)).knots[1]
        assertEquals(Pt(130f, 40f), pulled.outHandle)
        assertEquals(Pt(70f, -40f), pulled.inHandle)
        val snapped = angleSnapped(Pt(0f, 0f), Pt(100f, 90f))
        assertEquals(snapped.x, snapped.y, 0.01f)
        assertEquals(Pt(100f, 90f).length, snapped.length, 0.01f)
        assertEquals(0f, angleSnapped(Pt(0f, 0f), Pt(100f, 8f)).y, 0.01f)
    }

    private fun drawingScene(hardness: String?) = Scene(XmlNode("scene", mapOf("width" to "400", "height" to "400", "fps" to "30", "totalTime" to "2999", "bgcolor" to "#ff000000"), listOf(
        XmlNode("drawing", mapOf("id" to "1", "label" to "Drawing", "startTime" to "0", "endTime" to "2999", "fillType" to "none"), listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "200,200,0")))),
            XmlNode("stroke", linkedMapOf("color" to "#ffff0000", "width" to "40.0", "type" to "pen", "points" to "-150.00,0.00,1.00;150.00,0.00,1.00") + (hardness?.let { mapOf("hardness" to it) } ?: emptyMap())),
        )),
    )))

    @Test fun softStrokesFeatherTheirEdgeAndOldDrawingsLookTheSame() {
        DesktopEngine(repo.resolve("shared/assets"), repo).use { engine ->
            fun render(hardness: String?): IntArray { engine.setScene(drawingScene(hardness), repo); return engine.render(500, 400, 400).let { it.getRGB(0, 0, 400, 400, null, 0, 400) } }
            val old = render(null)
            val explicit = render("1.00")
            val soft = render("0.20")
            assertTrue("a hard stroke with the attribute draws like one without it", old.contentEquals(explicit))
            fun red(img: IntArray, y: Int) = img[y * 400 + 200] shr 16 and 255
            assertTrue(red(old, 200) > 240)
            assertTrue(red(old, 226) < 10)
            assertTrue("soft edge reaches past the hard edge: ${red(soft, 226)}", red(soft, 226) > 20)
            assertTrue("soft edge is lighter at the old edge: ${red(soft, 219)}", red(soft, 219) in 40..230)
            assertTrue(red(soft, 200) > 200)
        }
    }

    private fun ComposeUiTest.open(app: DeskAppState) {
        val id = app.store.create(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText())).also { created += app to it }
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
    }

    private fun key(app: DeskAppState, key: Key, shift: Boolean = false, alt: Boolean = false, command: Boolean = false, release: Boolean = true): Boolean {
        val meta = command && DeskOs.mac
        val ctrl = command && !DeskOs.mac
        val handled = DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift))
        if (release) DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift))
        return handled
    }

    private fun FreehandMode.reset() {
        pick(DrawTool.Eraser); resize(10f); settings.hardness = 1f; settings.smoothing = 0f; wholeStrokes = false
        pick(DrawTool.Brush); pressure = Pressure.Stylus
        pick(DrawTool.Pen); resize(10f); settings.hardness = 1f; settings.smoothing = 0f; pressure = Pressure.Off
        recolour(0xFFE53935.toInt())
    }

    @Test fun freehandKeysChangeTheBrushAndZoomTheCanvas() {
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startFreehand() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as FreehandMode
            runOnIdle { mode.reset() }
            runOnIdle {
                assertTrue(key(app, Key.RightBracket))
                assertEquals(12f, mode.width)
                key(app, Key.LeftBracket); key(app, Key.LeftBracket)
                assertEquals(8f, mode.width)
                assertTrue(key(app, Key.LeftBracket, shift = true))
                assertEquals(0.9f, mode.settings.hardness, 1e-4f)
                key(app, Key.Five)
                assertEquals(0x80, mode.color ushr 24)
                key(app, Key.Zero)
                assertEquals(0xFF, mode.color ushr 24)
                key(app, Key.B)
                assertEquals(DrawTool.Brush, mode.tool)
                key(app, Key.E)
                assertEquals(DrawTool.Eraser, mode.tool)
                assertEquals("the eraser keeps its own size", 10f, mode.width)
                key(app, Key.RightBracket)
                key(app, Key.P)
                assertEquals(8f, mode.width)
                key(app, Key.F)
                assertEquals(DrawTool.Fill, mode.tool)
                key(app, Key.P)
                val zoom = editor.canvasZoom
                assertTrue(key(app, Key.Equals, alt = true))
                assertTrue(editor.canvasZoom > zoom)
                key(app, Key.Zero, alt = true)
                assertEquals(1f, editor.canvasZoom)
                assertFalse("command keys stay the app's", key(app, Key.Equals, command = true) && editor.canvasZoom != 1f)
                key(app, Key.Spacebar, release = false)
                assertTrue(DrawingKeys.space)
            }
            val c = previewOf(1280f, 800f).center
            val pan = editor.canvasPan
            onRoot().performMouseInput { moveTo(c); press(); moveTo(c + Offset(80f, 40f), 100); release() }
            mainClock.advanceTimeBy(200)
            runOnIdle {
                assertTrue("Space and drag pans", editor.canvasPan != pan)
                assertTrue("Space and drag draws nothing", editor.scene.layer(mode.layerId)!!.strokes.isEmpty())
                DeskKeys.handle(app, KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
                assertFalse(DrawingKeys.space)
                mode.recolour(0x80123456.toInt())
            }
            onRoot().performMultiModalInput {
                key { keyDown(Key.AltLeft) }
                mouse { moveTo(c + Offset(0f, 30f)); press(); release() }
                key { keyUp(Key.AltLeft) }
            }
            mainClock.advanceTimeBy(200)
            runOnIdle {
                assertTrue("Alt-click picks a colour from the canvas", mode.color != 0x80123456.toInt())
                assertEquals("and keeps the opacity", 0x80, mode.color ushr 24)
                assertTrue("Alt-click draws nothing", editor.scene.layer(mode.layerId)!!.strokes.isEmpty())
            }
            runBlocking { app.closeEditor() }
        }
    }

    private fun previewOf(width: Float, height: Float) = Rect(24f, 24f + 42f, width - 24f, height - 24f - 200f)

    @Test fun freehandDrawsStraightLinesAndErasesWholeStrokes() {
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startFreehand() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as FreehandMode
            runOnIdle { mode.reset() }
            val box = previewOf(1280f, 800f)
            val c = box.center
            fun strokes() = editor.scene.layer(mode.layerId)!!.strokes
            fun zigzag(y: Float) = onRoot().performMouseInput {
                moveTo(c + Offset(-150f, y)); press()
                for (i in 1..20) moveTo(c + Offset(-150f + i * 10f, y + if (i % 2 == 0) 6f else -6f), 16)
                release()
            }
            fun spread(s: Stroke) = s.points.drop(4).dropLast(7).let { p -> p.maxOf { it.y } - p.minOf { it.y } }
            zigzag(-60f)
            mainClock.advanceTimeBy(300)
            runOnIdle { mode.settings.smoothing = 0.5f; mode.settings.hardness = 0.4f }
            zigzag(0f)
            mainClock.advanceTimeBy(300)
            val first = runOnIdle { strokes() }
            assertEquals(2, first.size)
            assertEquals(1f, first[0].hardness)
            assertEquals(0.4f, first[1].hardness, 1e-4f)
            assertTrue("smoothing calms the zigzag: ${spread(first[1])} vs ${spread(first[0])}", spread(first[1]) < spread(first[0]) * 0.5f)
            onRoot().performMultiModalInput {
                key { keyDown(Key.ShiftLeft) }
                mouse { moveTo(c + Offset(100f, 80f)); press(); release() }
                key { keyUp(Key.ShiftLeft) }
            }
            mainClock.advanceTimeBy(300)
            val second = runOnIdle { strokes() }
            assertEquals(3, second.size)
            assertEquals(2, second[2].points.size)
            assertEquals(first[1].points.last().x, second[2].points.first().x, 0.01f)
            assertEquals(first[1].points.last().y, second[2].points.first().y, 0.01f)
            runOnIdle { mode.pick(DrawTool.Eraser); mode.wholeStrokes = true }
            onRoot().performMouseInput { moveTo(c + Offset(-100f, -100f)); press(); for (i in 1..7) moveTo(c + Offset(-100f, -100f + i * 10f), 16); release() }
            mainClock.advanceTimeBy(300)
            val left = runOnIdle { strokes() }
            assertEquals("the stroke it crossed is gone and no eraser stroke is left", second.drop(1), left)
            runOnIdle { editor.undo() }
            assertEquals(3, runOnIdle { strokes() }.size)
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun holdingStillStraightensAndFingerTapsUndoAndRedo() {
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startFreehand() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as FreehandMode
            runOnIdle { mode.reset() }
            val c = previewOf(1280f, 800f).center
            fun strokes() = editor.scene.layer(mode.layerId)!!.strokes
            onRoot().performTouchInput { down(c + Offset(-120f, 0f)); for (i in 1..12) { advanceEventTime(16); moveTo(c + Offset(-120f + i * 15f, if (i % 2 == 0) 25f else -25f)) } }
            mainClock.advanceTimeBy(900)
            onRoot().performTouchInput { up() }
            mainClock.advanceTimeBy(300)
            val held = runOnIdle { strokes() }
            assertEquals(1, held.size)
            assertEquals("holding at the end straightens the stroke", 2, held[0].points.size)
            onRoot().performTouchInput { down(0, c + Offset(-100f, 50f)); down(1, c + Offset(100f, 50f)); advanceEventTime(60); up(0); up(1) }
            mainClock.advanceTimeBy(300)
            assertEquals("two-finger tap undoes", 0, runOnIdle { strokes() }.size)
            onRoot().performTouchInput { down(0, c + Offset(-100f, 50f)); down(1, c + Offset(0f, 50f)); down(2, c + Offset(100f, 50f)); advanceEventTime(60); up(0); up(1); up(2) }
            mainClock.advanceTimeBy(300)
            assertEquals("three-finger tap redoes", 1, runOnIdle { strokes() }.size)
            runOnIdle { assertEquals(1f, editor.canvasZoom) }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun penToolClosesOnTheFirstPointAndEditsPointsWithTheMouse() {
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startVectorDrawing() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as PointMode
            val c = DeskPreviewBounds.bounds.center
            val a = c + Offset(-80f, -60f)
            val b = c + Offset(80f, -60f)
            val d = c + Offset(0f, 70f)
            fun contour() = editor.pointContours(mode)[0]
            for (p in listOf(a, b, d)) { onRoot().performMouseInput { click(p) }; mainClock.advanceTimeBy(500) }
            assertEquals(3, runOnIdle { contour().knots.size })
            onRoot().performMouseInput { moveTo(a + Offset(3f, 2f)) }
            mainClock.advanceTimeBy(100)
            shot("pen-close-hover")
            onRoot().performMouseInput { moveTo(c + Offset(60f, 60f)) }
            mainClock.advanceTimeBy(100)
            shot("pen-dashed-line")
            onRoot().performMouseInput { click(a) }
            mainClock.advanceTimeBy(500)
            runOnIdle {
                assertTrue(contour().closed)
                assertEquals(3, contour().knots.size)
                assertEquals(0, mode.point)
                assertEquals(Handle.Main, mode.handle)
            }
            onRoot().performMouseInput { click(b); advanceEventTime(120); click(b) }
            mainClock.advanceTimeBy(500)
            runOnIdle { assertFalse("double-click makes the point curved", contour().knots[1].sharp) }
            onRoot().performMultiModalInput {
                key { keyDown(Key.AltLeft) }
                mouse { moveTo(d); press(); moveTo(d + Offset(40f, 0f), 100); moveTo(d + Offset(60f, 0f), 100); release() }
                key { keyUp(Key.AltLeft) }
            }
            mainClock.advanceTimeBy(300)
            runOnIdle {
                val k = contour().knots[2]
                assertNotNull(k.outHandle)
                assertEquals(k.p.x * 2 - k.outHandle!!.x, k.inHandle!!.x, 0.5f)
                assertEquals(2, mode.point)
                val frame = editor.playheadFrame
                assertTrue(key(app, Key.Delete))
                assertEquals(2, contour().knots.size)
                key(app, Key.DirectionRight)
                assertTrue("arrows still step frames", editor.playheadFrame > frame)
            }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun penToolShiftKeepsFortyFiveDegreesAndEnterFinishes() {
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startVectorDrawing() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as PointMode
            val c = DeskPreviewBounds.bounds.center
            onRoot().performMouseInput { click(c) }
            mainClock.advanceTimeBy(400)
            onRoot().performMultiModalInput {
                key { keyDown(Key.ShiftLeft) }
                mouse { moveTo(c + Offset(120f, 100f)) }
                mouse { press(); release() }
                key { keyUp(Key.ShiftLeft) }
            }
            mainClock.advanceTimeBy(400)
            runOnIdle {
                val k = editor.pointContours(mode)[0].knots
                assertEquals(2, k.size)
                val v = k[1].p - k[0].p
                assertEquals(abs(v.x), abs(v.y), 0.5f)
                assertEquals(Handle.Add, mode.handle)
                assertTrue(key(app, Key.Enter))
                assertEquals(Handle.Main, mode.handle)
                assertEquals(1, mode.point)
            }
            runBlocking { app.closeEditor() }
        }
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    @Test fun screenshotsOfTheDrawingTools() {
        for ((w, h) in listOf(1280 to 800, 430 to 900)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            open(app)
            val editor = app.editor!!
            runOnIdle { editor.startFreehand() }
            mainClock.advanceTimeBy(500)
            val mode = editor.drawing as FreehandMode
            runOnIdle { mode.reset(); mode.pick(DrawTool.Brush); mode.settings.hardness = 0.6f; mode.settings.smoothing = 0.6f; mode.opacity(0.85f) }
            val box = previewOf(w.toFloat(), h.toFloat())
            onRoot().performMouseInput { moveTo(box.center - Offset(box.width / 3, 0f)); press(); for (i in 1..30) moveTo(box.center - Offset(box.width / 3 - i * box.width / 45, (i % 5) * 4f), 16); release() }
            mainClock.advanceTimeBy(300)
            onRoot().performMouseInput { moveTo(box.center + Offset(0f, box.height / 4)) }
            if (w < 700) {
                shot("${w}x$h-freehand-colours")
                onAllNodesWithContentDescription("Brush settings").onFirst().performClick()
                mainClock.advanceTimeBy(400)
            }
            shot("${w}x$h-freehand-settings")
            runOnIdle { mode.pick(DrawTool.Eraser); mode.settingsOpen = true; mode.wholeStrokes = true }
            mainClock.advanceTimeBy(400)
            shot("${w}x$h-eraser-settings")
            runOnIdle { mode.reset() }
            runBlocking { app.closeEditor() }
        }
    }
}
