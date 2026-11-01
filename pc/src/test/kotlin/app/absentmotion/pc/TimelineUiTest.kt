package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.model.Scene
import app.absentmotion.model.PropRef
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.Timeline
import app.absentmotion.pc.ui.MediaPool
import app.absentmotion.pc.ui.Inspector
import app.absentmotion.pc.ui.Viewer
import app.absentmotion.pc.ui.panel
import app.absentmotion.pc.ui.preview
import app.absentmotion.pc.ui.timeline.rulerLabel
import app.absentmotion.pc.ui.timeline.rulerStep
import app.absentmotion.pc.ui.timeline.snapTime
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class TimelineUiTest {
    private fun project() = ProjectState().apply {
        open(Scene.parse("""<scene title="Timeline test" width="1920" height="1080" fps="30" totalTime="10000"><shape id="1" label="Back" startTime="1000" endTime="3000" s=".rect"/><shape id="2" label="Front" startTime="4000" endTime="6000" s=".rect"/></scene>"""), null)
    }

    @Test fun rulerSnapAndScale() {
        assertEquals(1000f / 30, rulerStep(1000, 9000f, 30), .01f)
        assertEquals("00:01:15", rulerLabel(1500f, 1000f / 30, 30))
        assertEquals("02:00", rulerLabel(120000f, 1000f, 30))
        assertEquals(2000 to 2000, snapTime(2040, listOf(2000), .1f, false, 30))
        assertNull(snapTime(2040, listOf(2000), .1f, true, 30).second)
    }

    @Test fun selectMoveTrimReorderLockAndUndo() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = project()
        var zoom by mutableFloatStateOf(1f)
        setContent {
            MaterialTheme(colors = darkColors()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize().background(preview)) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.width(240.dp).fillMaxHeight().background(panel)) { MediaPool(state) {} }
                        Box(Modifier.weight(1f).fillMaxHeight()) { Viewer(state, null, null, "Fit", {}, false, {}, {}) { _, _ -> } }
                        Box(Modifier.width(300.dp).fillMaxHeight().background(panel)) { Inspector(state, emptyList()) {} }
                    }
                    Box(Modifier.height(350.dp).fillMaxWidth()) { Timeline(state, zoom, { zoom = it }, "") }
                }
                }
            }
        }
        onNodeWithTag("tracks").performTouchInput { click(Offset(400f, 16f)) }
        runOnIdle { assertEquals(2L, state.selected) }
        onNodeWithTag("tracks").performTouchInput { swipe(Offset(400f, 16f), Offset(480f, 16f)) }
        runOnIdle { assertEquals(5000, state.scene.layer(2)!!.startTime); state.undo(); assertEquals(4000, state.scene.layer(2)!!.startTime); state.redo(); assertEquals(5000, state.scene.layer(2)!!.startTime) }
        onNodeWithTag("tracks").performTouchInput { swipe(Offset(560f, 16f), Offset(640f, 16f)) }
        runOnIdle { assertEquals(8000, state.scene.layer(2)!!.endTime); state.undo(); assertEquals(7000, state.scene.layer(2)!!.endTime) }
        onNodeWithTag("header-2").performTouchInput { swipe(Offset(20f, 16f), Offset(20f, 48f)) }
        runOnIdle { assertEquals(2L, state.scene.layers.first().id); state.undo(); assertEquals(2L, state.scene.layers.last().id) }
        onNodeWithTag("lock-2").performClick()
        runOnIdle { assertEquals("true", state.scene.layer(2)!!.node["locked"]); assertEquals(0L, state.selected) }
        onNodeWithTag("tracks").performTouchInput { click(Offset(440f, 16f)) }
        runOnIdle { assertEquals(0L, state.selected); state.undo(); assertNull(state.scene.layer(2)!!.node["locked"]) }
        onNodeWithTag("ruler").performTouchInput { swipe(Offset(80f, 10f), Offset(240f, 10f)) }
        runOnIdle { assertEquals(3000, state.time); state.selected = 2 }
        val shots = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("timeline.png"))
        runOnIdle { zoom = 3f }
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("timeline-zoomed.png"))
    }
    @Test fun rectangleDeleteAndSplit() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = project()
        setContent { Box(Modifier.fillMaxSize()) { Timeline(state, 1f, {}, "") } }
        onNodeWithTag("tracks").performTouchInput { swipe(Offset(20f, 2f), Offset(500f, 62f)) }
        runOnIdle { assertTrue(state.selected == 1L || state.selected == 2L) }
        onRoot().performKeyInput { pressKey(Key.Delete) }
        runOnIdle { assertTrue(state.scene.layers.isEmpty()); state.undo(); assertEquals(2, state.scene.layers.size) }
        onNodeWithTag("tracks").performTouchInput { click(Offset(150f, 48f)) }
        runOnIdle { state.time = 2000 }
        onRoot().performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.B); keyUp(Key.MetaLeft) }
        runOnIdle { assertEquals(3, state.scene.layers.size); state.undo(); assertEquals(2, state.scene.layers.size) }
    }

    @Test fun videoTrimRespectsSource() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState().apply {
            open(Scene.parse("""<scene fps="30" totalTime="10000"><media uri="video" duration="5000000"/><shape id="1" label="Video" startTime="1000" endTime="3000" fillType="media" fillVideo="video" inTime="1000" outTime="3000" s=".rect"/></scene>"""), null)
        }
        setContent { Box(Modifier.fillMaxSize()) { Timeline(state, 1f, {}, "") } }
        onNodeWithTag("tracks").performTouchInput { swipe(Offset(240f, 16f), Offset(480f, 16f)) }
        runOnIdle { assertEquals(5000, state.scene.layer(1)!!.endTime); state.undo(); assertEquals(3000, state.scene.layer(1)!!.endTime) }
        onNodeWithTag("tracks").performTouchInput { swipe(Offset(80f, 16f), Offset(160f, 16f)) }
        runOnIdle { assertEquals(2000, state.scene.layer(1)!!.startTime); assertEquals("2000", state.scene.layer(1)!!.node["inTime"]); state.undo(); assertEquals("1000", state.scene.layer(1)!!.node["inTime"]) }
    }

    @Test fun renameAndKeyframe() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = project()
        state.selected = 1
        state.time = 1500
        state.update { it.withKeyframeToggled(PropRef(PropRef.Kind.Transform, "location"), "0,0,0") }
        state.time = 0
        setContent { Box(Modifier.fillMaxSize()) { Timeline(state, 1f, {}, "") } }
        onNodeWithTag("tracks").performTouchInput { click(Offset(120f, 48f)) }
        runOnIdle { assertEquals(1500, state.time) }
        onNodeWithTag("header-1").performTouchInput { doubleClick() }
        onNodeWithTag("rename-1").performTextClearance()
        onNodeWithTag("rename-1").performTextInput("Renamed")
        onNodeWithTag("rename-1").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("Renamed", state.scene.layer(1)!!.label); state.undo(); assertEquals("Back", state.scene.layer(1)!!.label) }
    }

}
