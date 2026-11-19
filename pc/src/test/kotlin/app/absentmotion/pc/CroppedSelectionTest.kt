package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.openCrop
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.previewFrame
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CroppedSelectionTest {
    private fun crop(left: Float, top: Float, right: Float, bottom: Float, hidden: Boolean = false) =
        """<effect id="absent.crop"${if (hidden) " hidden=\"true\"" else ""}><property name="left" type="float" value="$left" /><property name="top" type="float" value="$top" /><property name="right" type="float" value="$right" /><property name="bottom" type="float" value="$bottom" /></effect>"""

    private fun scene(effect: String, transform: String = "", fill: String = """fillType="color"""") =
        """<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" $fill s=".rect"><transform><location value="200,200,0" />$transform</transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,50" />$effect</shape></scene>"""

    private fun <T> engine(xml: String, block: (Long) -> T): T {
        val handle = DesktopNative.create()
        try {
            DesktopNative.loadEffect(handle, EffectDefinition.parse(JSONObject(File("../shared/assets/effects/crop.json").readText())).runtimeXml())
            DesktopNative.setPicture(handle, "pic", File("../testdata/media/test-image.jpg").absolutePath, false)
            assertEquals(null, DesktopNative.setScene(handle, xml))
            return block(handle)
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    private fun drawn(handle: Long): FloatArray {
        val rgba = DesktopNative.still(handle, 0.0, 400, 400, true)!!
        var left = 400; var top = 400; var right = -1; var bottom = -1
        for (y in 0 until 400) for (x in 0 until 400) {
            if ((rgba[(y * 400 + x) * 4].toInt() and 255) > 128) { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1) }
        }
        return floatArrayOf(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun box(q: FloatArray) = floatArrayOf(
        (0..3).minOf { q[2 * it] }, (0..3).minOf { q[2 * it + 1] }, (0..3).maxOf { q[2 * it] }, (0..3).maxOf { q[2 * it + 1] },
    )

    @Test fun aCroppedImageIsFramedByWhatShows() {
        val cut = crop(0.25f, 0.1f, 0.25f, 0.3f)
        engine(scene(cut, fill = """fillType="media" fillImage="pic"""")) { h ->
            assertArrayEquals(floatArrayOf(150f, 160f, 250f, 220f), DesktopNative.layerBounds(h, 1, 0.0), 0.01f)
            assertArrayEquals(floatArrayOf(150f, 160f, 250f, 160f, 250f, 220f, 150f, 220f), DesktopNative.layerQuad(h, 1, 0.0), 0.01f)
            assertEquals(0L, DesktopNative.hitTest(h, 120f, 200f, 0.0))
            assertEquals(0L, DesktopNative.hitTest(h, 200f, 235f, 0.0))
            assertEquals(1L, DesktopNative.hitTest(h, 200f, 190f, 0.0))
        }
        engine(scene(cut)) { h -> assertArrayEquals(drawn(h), DesktopNative.layerBounds(h, 1, 0.0), 1f) }
        val keyed = """<effect id="absent.crop"><property name="left" type="float"><kf t="0" v="0" /><kf t="1" v="0.5" /></property></effect>"""
        engine(scene(keyed)) { h ->
            assertArrayEquals(floatArrayOf(100f, 150f, 300f, 250f), DesktopNative.layerBounds(h, 1, 0.0), 0.01f)
            assertArrayEquals(floatArrayOf(200f, 150f, 300f, 250f), DesktopNative.layerBounds(h, 1, 999.0), 0.5f)
        }
    }

    @Test fun aTurnedAndScaledCroppedLayerIsFramedByWhatShows() {
        val turn = """<rotation value="30" /><scale value="1.5,1.5" />"""
        engine(scene(crop(0.25f, 0.1f, 0.25f, 0.3f), turn)) { h ->
            val q = DesktopNative.layerQuad(h, 1, 0.0)!!
            val bounds = DesktopNative.layerBounds(h, 1, 0.0)!!
            assertArrayEquals(box(q), bounds, 0.01f)
            assertArrayEquals(drawn(h), bounds, 2f)
            assertEquals(150f, kotlin.math.hypot(q[2] - q[0], q[3] - q[1]), 0.01f)
            assertEquals(90f, kotlin.math.hypot(q[6] - q[0], q[7] - q[1]), 0.01f)
            val midX = (q[0] + q[6]) / 2f + (q[0] - q[2]) * 0.4f
            val midY = (q[1] + q[7]) / 2f + (q[1] - q[3]) * 0.4f
            assertEquals(0L, DesktopNative.hitTest(h, midX, midY, 0.0))
            assertEquals(1L, DesktopNative.hitTest(h, (q[0] + q[4]) / 2f, (q[1] + q[5]) / 2f, 0.0))
            engine(scene("", turn)) { whole -> assertEquals(1L, DesktopNative.hitTest(whole, midX, midY, 0.0)) }
        }
    }

    @Test fun aCroppedGroupOrAGroupWithACroppedChildIsFramedByWhatShows() {
        fun group(outer: String, inner: String) =
            """<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><embedScene id="10" startTime="0" endTime="999"><transform><location value="200,200,0" /></transform><scene width="400" height="400" totalTime="999" fps="30" retime="off" bgcolor="#00000000"><shape id="12" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,50" />$inner</shape></scene>$outer</embedScene></scene>"""
        engine(group("", "")) { h -> assertArrayEquals(floatArrayOf(100f, 150f, 300f, 250f), DesktopNative.layerBounds(h, 10, 0.0), 0.01f) }
        engine(group(crop(0.5f, 0f, 0f, 0f), "")) { h ->
            assertArrayEquals(floatArrayOf(200f, 150f, 300f, 250f), DesktopNative.layerBounds(h, 10, 0.0), 0.01f)
            assertArrayEquals(drawn(h), DesktopNative.layerBounds(h, 10, 0.0), 1f)
            assertEquals(0L, DesktopNative.hitTest(h, 150f, 200f, 0.0))
            assertEquals(10L, DesktopNative.hitTest(h, 250f, 200f, 0.0))
        }
        engine(group("", crop(0f, 0f, 0.5f, 0f))) { h -> assertArrayEquals(floatArrayOf(100f, 150f, 200f, 250f), DesktopNative.layerBounds(h, 10, 0.0), 0.01f) }
    }

    @Test fun anUncroppedLayerKeepsItsWholeFrame() {
        for (effect in listOf("", crop(0f, 0f, 0f, 0f), crop(0.25f, 0.1f, 0.25f, 0.3f, hidden = true))) engine(scene(effect)) { h ->
            assertArrayEquals(effect, floatArrayOf(100f, 150f, 300f, 250f), DesktopNative.layerBounds(h, 1, 0.0), 0.01f)
            assertArrayEquals(effect, floatArrayOf(100f, 150f, 300f, 150f, 300f, 250f, 100f, 250f), DesktopNative.layerQuad(h, 1, 0.0), 0.01f)
            assertEquals(effect, 1L, DesktopNative.hitTest(h, 120f, 200f, 0.0))
        }
    }

    private fun picture(effect: String) =
        """<scene title="Crop" width="1080" height="1080" totalTime="2999" fps="30" bgcolor="#ff202020"><shape id="7" label="Picture" startTime="0" endTime="2999" fillType="media" fillImage="${File("../testdata/media/test-image.jpg").canonicalFile.toURI()}" mediaFillMode="fill" s=".rect"><transform><location value="540,540,0" /></transform><property name="size" type="vec2" value="400,300" />$effect</shape></scene>"""

    private fun desk(name: String, xml: String, select: Boolean = true, block: ComposeUiTest.(EditorState, (String) -> FloatArray) -> Unit) {
        System.setProperty("am.data", File("build/crop-frame-$name").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/crop-frame").also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            runBlocking { app.openNow(app.store.create(Scene.parse(xml))) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            if (select) runOnIdle { editor.selectedId = 7; editor.panel = EditorPanel.Tools }
            block(editor) { shot ->
                mainClock.advanceTimeBy(800)
                waitForIdle()
                val quad = runBlocking { editor.engine.layerQuad(7, editor.timeMs) }!!
                javax.swing.SwingUtilities.invokeAndWait {}
                waitForIdle()
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$shot.png"))
                quad
            }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun theDesktopEditorFramesTheCroppedPartAndTheCropPanelTheWholePicture() = desk("panel", picture(crop(0.3f, 0.2f, 0.1f, 0.35f))) { editor, shot ->
        val selected = shot("panel-selected")
        runOnIdle { editor.openCrop() }
        val cropping = shot("crop-panel")
        val cropView = runOnIdle { editor.canvasPan.y }
        runOnIdle { editor.panel = EditorPanel.Tools }
        val done = shot("done")
        val cropped = floatArrayOf(380f, 360f, 860f, 360f, 860f, 630f, 380f, 630f)
        assertArrayEquals(cropped, selected, 0.01f)
        assertArrayEquals(floatArrayOf(140f, 240f, 940f, 240f, 940f, 840f, 140f, 840f), cropping, 0.01f)
        assertEquals(45f, cropView, 0.5f)
        assertArrayEquals(cropped, done, 0.01f)
    }

    @Test fun aCornerOfACroppedPictureWithItsPivotOutsideStaysUnderThePointer() = desk("drag", picture(crop(0f, 0f, 0.6f, 0f))) { editor, shot ->
        val q = shot("drag-before")
        val b = DeskPreviewBounds.bounds
        val (scale, dx, dy) = previewFrame(b.width, b.height, 0f, 1080f, 1080f, editor.canvasZoom, editor.canvasPan)
        fun screen(x: Float, y: Float) = Offset(b.left + dx + x * scale, b.top + dy + y * scale)
        val corner = screen(q[4], q[5])
        val centre = screen((q[0] + q[4]) / 2f, (q[1] + q[5]) / 2f)
        val target = centre + (corner - centre) * 1.5f
        onRoot().performMouseInput { moveTo(corner); press(); moveTo(corner + (target - corner) * 0.5f); moveTo(target); release() }
        val after = shot("drag-after")
        assertEquals(target.x, screen(after[4], after[5]).x, 1f)
        assertEquals(target.y, screen(after[4], after[5]).y, 1f)
        assertEquals((q[0] + q[4]) / 2f, (after[0] + after[4]) / 2f, 0.5f)
        assertEquals((q[1] + q[5]) / 2f, (after[1] + after[5]) / 2f, 0.5f)
    }

    @Test fun clickingACroppedPictureDrawsItsFrameAroundTheCroppedPart() = desk("click", picture(crop(0.3f, 0.2f, 0.1f, 0.35f)), select = false) { editor, shot ->
        shot("unselected")
        val plain = ImageIO.read(File("build/ui-shots/crop-frame/unselected.png"))
        val b = DeskPreviewBounds.bounds
        val (scale, dx, dy) = previewFrame(b.width, b.height, 0f, 1080f, 1080f, editor.canvasZoom, editor.canvasPan)
        fun screen(x: Float, y: Float) = Offset(b.left + dx + x * scale, b.top + dy + y * scale)
        fun marked(image: java.awt.image.BufferedImage, x: Float, y: Float): Boolean {
            val at = screen(x, y)
            return (-3..3).any { ox -> (-3..3).any { oy ->
                val a = image.getRGB(at.x.toInt() + ox, at.y.toInt() + oy)
                val p = plain.getRGB(at.x.toInt() + ox, at.y.toInt() + oy)
                (0..2).sumOf { kotlin.math.abs((a shr (8 * it) and 255) - (p shr (8 * it) and 255)) } > 90
            } }
        }
        onRoot().performMouseInput { click(screen(620f, 495f)) }
        waitUntil(timeoutMillis = 10_000) { editor.selectedId == 7L }
        shot("selected")
        val shown = ImageIO.read(File("build/ui-shots/crop-frame/selected.png"))
        val corners = listOf(380f to 360f, 860f to 360f, 860f to 630f, 380f to 630f)
        val handles = corners + corners.indices.map { i -> (corners[i].first + corners[(i + 1) % 4].first) / 2f to (corners[i].second + corners[(i + 1) % 4].second) / 2f }
        assertTrue("no frame handle at ${handles.filterNot { (x, y) -> marked(shown, x, y) }}", handles.all { (x, y) -> marked(shown, x, y) })
        for ((x, y) in listOf(140f to 240f, 940f to 240f, 940f to 840f, 140f to 840f)) assertFalse("a handle at the uncropped corner $x,$y", marked(shown, x, y))
    }
}
