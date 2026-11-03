package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.editor.ValueLinkControl
import app.absentmotion.ui.theme.Am
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskLinkingTest {
    private val xml = """<scene title="Links" width="640" height="640" fps="30" totalTime="2000">
        <shape id="1" label="Source" startTime="0" endTime="2000"><transform><location><kf t="0" v="10,20,0"/><kf t="1" v="110,220,0"/></location></transform></shape>
        <shape id="2" label="Target" startTime="0" endTime="2000"><transform><rotation><kf t="0" v="7"/><kf t="1" v="17"/></rotation></transform></shape>
        </scene>"""

    @Test fun pickerAtPhoneAndDesktopSizes() {
        for (width in listOf(390, 1280)) runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            System.setProperty("am.data", File("build/linking-ui-$width").also { it.deleteRecursively() }.absolutePath)
            val app = DeskAppState()
            val state = runBlocking { app.openNow(app.store.create(Scene.parse(xml)))!! }
            runOnIdle { state.selectedId = 2 }
            val before = state.scene.layer(2)!!.keyframes(PropRef.Rotation)
            setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Am.Panel)) {
                        ValueLinkControl(state, KeyTarget(PropRef.Rotation, "0"), Modifier.width(84.dp).height(64.dp))
                    }
                }
            }
            onNodeWithContentDescription("Link").performClick()
            onNodeWithText("Source").performClick()
            onNodeWithText("Position X").performClick()
            onNodeWithText("Multiply").performTextReplacement("2")
            onNodeWithText("Add", substring = false).performTextReplacement("3")
            onNodeWithText("Delay (seconds)").performTextReplacement("0.5")
            mainClock.advanceTimeBy(300)
            val shot = onAllNodes(isRoot()).onLast().captureToImage().toAwtImage()
            ImageIO.write(shot, "png", File("build/linking-picker-$width.png"))
            onNodeWithText("Apply").performClick()
            runOnIdle {
                val link = state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0)!!
                assertEquals(2f, link.multiply)
                assertEquals(3f, link.add)
                assertEquals(.5f, link.delay)
                assertEquals(before, state.scene.layer(2)!!.keyframes(PropRef.Rotation))
                state.undo()
                assertNull(state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0))
                state.redo()
                assertNotNull(state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0))
            }
            onNodeWithContentDescription("Link").performClick()
            onNodeWithText("Unlink").performClick()
            runOnIdle {
                assertNull(state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0))
                assertEquals(before, state.scene.layer(2)!!.keyframes(PropRef.Rotation))
                state.undo()
                state.selectedId = 1
                state.deleteSelected()
                assertNull(state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0))
                state.undo()
                assertNotNull(state.scene.layer(1))
                assertNotNull(state.scene.layer(2)!!.element(PropRef.Rotation)!!.valueLink(0))
            }
            val numbers = runBlocking { app.engine.propertyNumbers(2, -1, "rotation", 1000) }!!
            assertEquals(73f, numbers[0], .001f)
            runBlocking { app.closeEditor() }
            app.engine.close()
        }
    }
}
