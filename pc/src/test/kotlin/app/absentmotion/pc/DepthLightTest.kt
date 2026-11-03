package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DepthLightTest {
    @Test
    fun lightOptionsEditAndUndo() {
        System.setProperty("am.data", File("build/depth-light-test").also { it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val project = Scene.create("Depth", 400, 400, 30, 0)
            runBlocking { app.openNow(app.store.create(project)) }
            waitUntil(timeoutMillis = 30_000) { app.editor != null }
            val editor = app.editor!!
            mainClock.advanceTimeBy(500)
            onNodeWithText("Objects").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Light").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Light options").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Point").performClick()
            runOnIdle {
                assertEquals("point", editor.selected!!.node["type"])
                assertTrue(editor.selected!!.propText("intensity") != null)
                assertTrue(editor.selected!!.propText("falloff") != null)
                editor.undo()
                assertEquals("directional", editor.selected!!.node["type"])
                editor.undo()
                assertFalse(editor.scene.layers.any { it.kind == "light" })
            }
        }
    }
}
