package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.editor.DeskTimelineControls
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskViewTest {
    private fun ComposeUiTest.open(app: DeskAppState) {
        setContent { DeskApp(app) }
        runBlocking { app.openNow(app.store.create(Scene.parse(File("../testdata/scenes/shapes.xml").readText()))) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(800)
    }

    @Test fun timelineZoomsOutToTenMinutesAndSlowsDown() {
        System.setProperty("am.data", File("build/view-test-zoom").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            open(app)
            val editor = app.editor!!
            runOnIdle { DeskTimelineControls.fit!!() }
            mainClock.advanceTimeBy(200)
            val fit = editor.timelineZoom
            val steps = ArrayList<Float>()
            repeat(60) {
                val before = editor.timelineZoom
                runOnIdle { DeskTimelineControls.zoom!!(0.8f) }
                mainClock.advanceTimeBy(50)
                steps += editor.timelineZoom / before
            }
            val floor = editor.timelineZoom
            val visibleSeconds = (1280f - DeskTimelineControls.headerDp) / floor
            assertTrue("zoomed out to $floor dp/s, fit was $fit", floor < fit / 4)
            assertTrue("visible span $visibleSeconds s", visibleSeconds in 500f..640f)
            assertEquals(1f, steps.last(), 1e-4f)
            val moving = steps.filter { it < 0.9999f }
            assertTrue("steps $moving", moving.first() < moving.last())
        }
    }

    @Test fun middleDragMovesThePreview() {
        System.setProperty("am.data", File("build/view-test-pan").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            open(app)
            val editor = app.editor!!
            val bounds = DeskPreviewBounds.bounds
            val start = Offset((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
            val panBefore = editor.canvasPan
            onRoot().performMouseInput {
                moveTo(start); press(MouseButton.Tertiary)
                moveTo(start + Offset(40f, 20f)); moveTo(start + Offset(80f, 40f))
                release(MouseButton.Tertiary)
            }
            mainClock.advanceTimeBy(300)
            runOnIdle {
                val moved = editor.canvasPan - panBefore
                assertTrue("pan moved $moved", moved.x > 0f && moved.y > 0f)
                assertEquals(null, editor.selectedId)
                assertEquals(2f, moved.x / moved.y, 0.05f)
            }
        }
    }

    @Test fun middleDragOverASelectedLayerOnlyMovesThePreview() {
        System.setProperty("am.data", File("build/view-test-pan-selected").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            open(app)
            val editor = app.editor!!
            fun saved() = editor.scene.toXml().replace(Regex("modifiedTime=\"\\d+\""), "")
            runOnIdle { editor.selectedId = editor.scene.layers.first { it.label == "Circle" }.id }
            mainClock.advanceTimeBy(300)
            val bounds = DeskPreviewBounds.bounds
            val start = Offset((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
            val panBefore = editor.canvasPan
            val sceneBefore = saved()
            onRoot().performMouseInput {
                moveTo(start); press(MouseButton.Tertiary)
                moveTo(start + Offset(40f, 20f)); moveTo(start + Offset(80f, 40f))
                release(MouseButton.Tertiary)
            }
            mainClock.advanceTimeBy(300)
            runOnIdle {
                val moved = editor.canvasPan - panBefore
                assertTrue("pan moved $moved", moved.x > 0f && moved.y > 0f)
                assertEquals(sceneBefore, saved())
            }
        }
    }
}
