package app.absentmotion.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.material.darkColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.model.PropRef
import app.absentmotion.model.valueAt
import app.absentmotion.pc.ui.panel
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.EffectsLibrary
import app.absentmotion.pc.ui.Inspector
import app.absentmotion.pc.ui.Timeline
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class WorkspaceUiTest {
    @Test fun selectEditAddEffectAndUndo() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState()
        state.open(Scene.parse(File("../testdata/scenes/shapes.xml").readText()), null)
        val assets = File("../app/src/main/assets")
        val definition = EffectDefinition.parse(JSONObject(assets.resolve("effects/brightnesscontrast.json").readText()))
        setContent {
            MaterialTheme(colors = darkColors()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.fillMaxSize().background(panel)) {
                    Box(Modifier.width(280.dp).height(700.dp)) { EffectsLibrary(state, listOf(definition), assets) }
                    Box(Modifier.width(350.dp).height(700.dp)) { Inspector(state, listOf(definition)) {} }
                    Box(Modifier.width(700.dp).height(700.dp)) { Timeline(state, 1f, {}, "") }
                }
                }
            }
        }
        onNodeWithText("Circle", substring = false).performScrollTo().performClick()
        waitForIdle()
        runOnIdle { assertEquals(1001L, state.selected) }
        onNodeWithTag("location-0").performTextClearance()
        onNodeWithTag("location-0").performTextInput("300")
        waitForIdle()
        runOnIdle { assertEquals(300f, state.layer!!.location[0], 0.01f) }
        onNodeWithText(definition.name, substring = false).performClick()
        waitForIdle()
        runOnIdle { assertEquals(definition.id, state.layer!!.effects.single()["id"]) }
        onNodeWithTag("effect-brightness").performTextClearance()
        onNodeWithTag("effect-brightness").performTextInput("0.5")
        waitForIdle()
        runOnIdle { assertEquals(0.5f, state.layer!!.element(PropRef.effect(0, "brightness"))!!.valueAt(0f)!!.toFloat(), 0.01f) }
        onNodeWithTag("key-brightness").performClick()
        waitForIdle()
        runOnIdle { assertTrue(state.scene.layerAt(1001, state.time)!!.onKeyframe(PropRef.effect(0, "brightness"))) }
        val shots = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("panels-test.png"))
        runOnIdle { state.undo(); state.undo(); state.undo(); assertTrue(state.layer!!.effects.isEmpty()); state.redo(); assertEquals(1, state.layer!!.effects.size) }
    }
}
