package app.absentmotion.pc

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.Viewer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ViewerUiTest {
    private fun state() = ProjectState().apply {
        open(Scene.parse("""<scene width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color"><transform><location value="50,50,0"/><scale value="1,1"/></transform><property name="size" type="vec2" value="60,60"/></shape></scene>"""), null)
        selected = 1
    }
    private val quad = floatArrayOf(20f, 20f, 80f, 20f, 80f, 80f, 20f, 80f)

    @Test fun moveScaleRotateAndUndo() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val state = state()
        setContent { Box(Modifier.size(800.dp, 500.dp)) { Viewer(state, null, quad, "Fit", {}, false, {}, {}) { _, _ -> } } }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(400f, 250f), Offset(450f, 275f)) }
        runOnIdle { assertEquals(60f, state.layer!!.location[0], .01f); assertEquals(55f, state.layer!!.location[1], .01f); state.undo(); assertEquals(50f, state.layer!!.location[0], .01f) }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(550f, 400f), Offset(625f, 475f)) }
        runOnIdle { assertEquals(1.5f, state.layer!!.scale[0], .01f); assertEquals(1.5f, state.layer!!.scale[1], .01f); state.undo() }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(400f, 68f), Offset(582f, 250f)) }
        runOnIdle { assertEquals(90f, state.layer!!.rotation, 1f); state.undo(); assertEquals(0f, state.layer!!.rotation, .01f) }
    }

    @Test fun moveUpdatesKeyframeAtPlayhead() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val state = state()
        val ref = PropRef(PropRef.Kind.Transform, "location")
        state.update { it.withKeyframeToggled(ref, "50,50,0") }
        state.time = 500
        setContent { Box(Modifier.size(800.dp, 500.dp)) { Viewer(state, null, quad, "Fit", {}, false, {}, {}) { _, _ -> } } }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(400f, 250f), Offset(450f, 250f)) }
        runOnIdle {
            val edited = state.scene.layerAt(1, 500)!!
            assertTrue(edited.onKeyframe(ref))
            assertEquals(60f, edited.location[0], .01f)
            assertEquals(50f, state.scene.layerAt(1, 0)!!.location[0], .01f)
            state.undo()
            assertEquals(50f, state.scene.layerAt(1, 500)!!.location[0], .01f)
        }
    }

    @Test fun pinchZoomDoesNotEditLayer() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val state = state()
        var mode by mutableStateOf("Fit")
        setContent { Box(Modifier.size(800.dp, 500.dp)) { Viewer(state, null, quad, mode, { mode = it }, false, {}, {}) { _, _ -> } } }
        onNodeWithTag("viewer").performTouchInput {
            down(0, Offset(350f, 250f)); down(1, Offset(450f, 250f))
            updatePointerTo(0, Offset(300f, 250f)); updatePointerTo(1, Offset(500f, 250f)); move()
            up(1); up(0)
        }
        runOnIdle { assertEquals(800f, mode.removeSuffix("%").toFloat(), .01f); assertFalse(state.canUndo) }
    }

    @Test fun spaceDragPansWithoutEditing() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val state = state()
        var panned = false
        setContent { Box(Modifier.size(800.dp, 500.dp)) { Viewer(state, null, quad, "Fit", {}, true, { panned = true }, {}) { _, _ -> } } }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(400f, 250f), Offset(450f, 275f)) }
        runOnIdle { assertTrue(panned); assertEquals(50f, state.layer!!.location[0], .01f); assertFalse(state.canUndo) }
    }

    @Test fun gradientHandlesAndSelection() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val state = state()
        state.update { it.withFill("gradient").withGradient(it.gradient) }
        var hit = Offset.Unspecified
        setContent { Box(Modifier.size(800.dp, 500.dp)) { Viewer(state, null, quad, "Fit", {}, false, {}, {}) { x, y -> hit = Offset(x, y) } } }
        onNodeWithTag("viewer").performTouchInput { swipe(Offset(250f, 100f), Offset(310f, 130f)) }
        runOnIdle { assertEquals(.2f, state.layer!!.gradient.x0, .01f); assertEquals(.1f, state.layer!!.gradient.y0, .01f); state.undo(); assertEquals(0f, state.layer!!.gradient.x0, .01f) }
        onNodeWithTag("viewer").performTouchInput { click(Offset(175f, 25f)) }
        runOnIdle { assertEquals(5f, hit.x, .01f); assertEquals(5f, hit.y, .01f) }
    }
}
