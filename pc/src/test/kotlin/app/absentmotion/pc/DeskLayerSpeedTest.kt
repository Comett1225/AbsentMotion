package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.setVideoSpeed
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class DeskLayerSpeedTest {
    init { DesktopNative.hashCode() }

    @OptIn(ExperimentalTestApi::class)
    @Test fun textNullAndGroupGetTheSpeedTool() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val shots = File("build/layer-speed").absoluteFile.also { it.mkdirs() }
        val app = DeskAppState()
        setContent { DeskApp(app) }
        val id = app.store.create(Scene.parse(
            """<scene width="400" height="400" fps="30" totalTime="4000">""" +
                """<text id="1" label="Title" startTime="0" endTime="4000"><content>Hello</content><transform><opacity><kf t="0" v="0"/><kf t="1" v="1"/></opacity></transform></text>""" +
                """<nullobj id="2" label="Null" startTime="0" endTime="4000"/>""" +
                """<embedScene id="3" label="Group" startTime="0" endTime="4000"><scene fps="30" totalTime="4000" retime="off"><text id="4" startTime="0" endTime="4000"><content>Inside</content></text></scene></embedScene>""" +
                """<shape id="5" label="Box" startTime="0" endTime="4000" fillType="color"/></scene>""",
        ))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        val editor = app.editor!!
        fun settle() { mainClock.advanceTimeBy(500); waitForIdle() }
        fun speedButtons() = onAllNodesWithContentDescription("Speed").filter(hasClickAction()).fetchSemanticsNodes().size
        for (layerId in listOf(1L, 2L, 3L)) {
            editor.selectedId = layerId
            editor.panel = EditorPanel.Tools
            settle()
            assertTrue("layer $layerId", speedButtons() > 0)
            onAllNodesWithContentDescription("Speed").filter(hasClickAction()).onFirst().performClick()
            settle()
            assertEquals(EditorPanel.Speed, editor.panel)
            assertTrue(onAllNodesWithText("1.00x").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText("Normal").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Freeze Frame").fetchSemanticsNodes().isEmpty())
            editor.setVideoSpeed(2f, editor.selected!!)
            settle()
            assertEquals(2000, editor.scene.layer(layerId)!!.endTime)
            assertTrue(onAllNodesWithText("2.00x").fetchSemanticsNodes().isNotEmpty())
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("speed-$layerId.png"))
        }
        editor.selectedId = 5L
        editor.panel = EditorPanel.Tools
        settle()
        assertEquals(0, speedButtons())
    }
}
