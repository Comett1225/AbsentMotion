package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.editor.groupLayers
import app.absentmotion.editor.renameLayer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskPreviewBounds
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskGroupTest {
    private fun ComposeUiTest.settle(app: DeskAppState) {
        repeat(4) { mainClock.advanceTimeBy(500); Thread.sleep(300) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(500)
    }

    private fun ComposeUiTest.open(app: DeskAppState, xml: String) {
        setContent { DeskApp(app) }
        runBlocking { app.openNow(app.store.create(Scene.parse(xml))) }
        settle(app)
    }

    @Test fun canvasEdgeHasNoLightLine() {
        System.setProperty("am.data", File("build/group-test-edge").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        fun scene(w: Int, h: Int) = """<scene title="Edge" width="$w" height="$h" exportWidth="$w" exportHeight="$h" bgcolor="#00000000" totalTime="1000" fps="30">
            <shape id="1" label="Black" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="${w / 2f},${h / 2f},0"/></transform>
            <property name="size" type="vec2" value="$w,$h"/><fillColor value="#ff000000"/></shape></scene>"""
        for ((w, h, canvas) in listOf(Triple(1280, 800, 1083 to 1077), Triple(1237, 761, 1083 to 1077), Triple(1237, 761, 63 to 47))) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val app = DeskAppState()
            open(app, scene(canvas.first, canvas.second))
            for (pixels in listOf(false, true)) {
                runOnIdle { app.editor!!.pixelMode = pixels }
                settle(app)
                waitForIdle()
                val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage() as BufferedImage
                val bounds = DeskPreviewBounds.bounds
                val y = (bounds.top + bounds.bottom).toInt() / 2
                val x = (bounds.left + bounds.right).toInt() / 2
                val light = (bounds.left.toInt() until bounds.right.toInt()).map { image.getRGB(it, y) }
                    .plus((bounds.top.toInt() until bounds.bottom.toInt()).map { image.getRGB(x, it) })
                    .map { maxOf(it shr 16 and 255, it shr 8 and 255, it and 255) }.filter { it > 40 }
                assertTrue("light pixels at the canvas edge, window ${w}x$h, canvas $canvas, pixel preview $pixels: $light", light.isEmpty())
            }
        }
    }

    @Test fun groupNavigation() {
        System.setProperty("am.data", File("build/group-test-nav").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val media = File("../testdata").canonicalFile.toURI().toString()
        val xml = File("../testdata/scenes/media.xml").readText().replace("\"testdata/", "\"$media").replace("title=\"Media\"", "title=\"Trip\"")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            open(app, xml)
            val editor = app.editor!!
            runOnIdle {
                editor.groupLayers(editor.scene.layers.filter { it.kind != "audio" }.map { it.id }.toSet())
                editor.renameLayer(editor.selectedId!!, "Outer")
                editor.selectedId = null
            }
            mainClock.advanceTimeBy(300)
            onAllNodesWithText("Sound", substring = false).assertCountEquals(1)
            onRoot().performMouseInput { doubleClick(Offset(700f, 596f)) }
            mainClock.advanceTimeBy(400)
            runOnIdle { assertTrue(editor.inGroup) }
            onAllNodesWithText("Sound", substring = false).assertCountEquals(0)
            onAllNodesWithText("Trip", substring = false).filter(hasClickAction()).assertCountEquals(1)

            runOnIdle {
                editor.groupLayers(editor.scene.layers.take(2).map { it.id }.toSet())
                editor.renameLayer(editor.selectedId!!, "Inner")
                editor.enterGroup(editor.selectedId!!)
            }
            mainClock.advanceTimeBy(300)
            runOnIdle { assertEquals(2, editor.groupPath.size) }
            onAllNodesWithText("Outer", substring = false).filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            runOnIdle { assertEquals(1, editor.groupPath.size); assertTrue(editor.selectedId != null) }

            runOnIdle { DeskBack.back() }
            runOnIdle { assertTrue(editor.inGroup); assertEquals(null, editor.selectedId) }
            runOnIdle { DeskBack.back() }
            runOnIdle { assertFalse(editor.inGroup) }

            onRoot().performMouseInput { click(Offset(80f, 596f)); advanceEventTime(120); click(Offset(700f, 596f)) }
            mainClock.advanceTimeBy(400)
            runOnIdle { assertFalse(editor.inGroup) }
            onRoot().performMouseInput { doubleClick(Offset(80f, 596f)) }
            mainClock.advanceTimeBy(400)
            runOnIdle { assertTrue(editor.inGroup) }
            onAllNodesWithText("Trip", substring = false).filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            runOnIdle { assertFalse(editor.inGroup) }
        }
    }
}
