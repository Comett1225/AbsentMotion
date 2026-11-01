package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectPreset
import app.absentmotion.model.*
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class InspectorUiTest {
    private val assets = File("../app/src/main/assets")
    private val definition = EffectDefinition.parse(JSONObject(assets.resolve("effects/brightnesscontrast.json").readText())).copy(presets = listOf(EffectPreset("Bright", mapOf("brightness" to "0.75"), emptyMap())))
    private fun state(kind: String) = ProjectState().apply {
        open(Scene.parse("""<scene title="Inspector" width="640" height="360" fps="30" totalTime="2000"><$kind id="1" label="Example" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="320,180,0"/><scale value="1,1"/></transform><property name="size" type="vec2" value="200,150"/><fillColor value="#ffcc7755"/><content>Example text</content></$kind></scene>"""), null)
        selected = 1
    }

    @Test fun transformKeysAndEffectEditAreUndoable() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = state("shape")
        state.update { it.addEffect(definition.instance()) }
        DesktopEngine(assets, File("..")).use { engine ->
            engine.setScene(state.scene)
            var image by mutableStateOf(engine.render(0, 640, 360).toComposeImageBitmap())
            setContent {
                MaterialTheme(colors = darkColors(primary = accent)) { CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(Modifier.fillMaxSize().background(preview)) {
                        SectionTitle("ABSENTMOTION     Inspector project")
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.width(240.dp).fillMaxHeight().background(panel)) { EffectsLibrary(state, listOf(definition), assets) }
                            Box(Modifier.weight(1f)) { Viewer(state, image, null, "Fit", {}, false, {}, {}) { _, _ -> } }
                            Box(Modifier.width(340.dp).fillMaxHeight().background(panel)) { Inspector(state, listOf(definition)) {} }
                        }
                        Box(Modifier.height(190.dp)) { Timeline(state, 1f, {}, "") }
                    }
                } }
            }
            onNodeWithTag("key-location").performClick()
            runOnIdle { state.time = 1000 }
            onNodeWithTag("location-0").performTextClearance()
            onNodeWithTag("location-0").performTextInput("400")
            runOnIdle {
                assertEquals(400f, state.scene.layerAt(1, 1000)!!.location[0], .01f)
                assertEquals(320f, state.scene.layerAt(1, 0)!!.location[0], .01f)
                assertTrue(state.scene.layerAt(1, 1000)!!.onKeyframe(PropRef(PropRef.Kind.Transform, "location")))
                state.undo()
                assertEquals(320f, state.scene.layerAt(1, 1000)!!.location[0], .01f)
                state.redo()
            }
            onNodeWithText("Presets ▾").performScrollTo().performClick()
            onNodeWithText("Bright").performClick()
            runOnIdle { assertEquals(.75f, state.scene.layerAt(1, 1000)!!.element(PropRef.effect(0, "brightness"))!!.valueAt(.5f)!!.toFloat(), .01f) }
            onNodeWithTag("effect-brightness").performScrollTo().performTextClearance()
            onNodeWithTag("effect-brightness").performTextInput("0.5")
            onNodeWithTag("key-brightness").performScrollTo().performClick()
            runOnIdle { assertTrue(state.scene.layerAt(1, 1000)!!.onKeyframe(PropRef.effect(0, "brightness"))) }
            onNodeWithText("▾  Layer").performScrollTo().performClick()
            onNodeWithText("▾  Transform").performScrollTo().performClick()
            onNodeWithText("▾  Fill & Color").performScrollTo().performClick()
            runOnIdle { engine.setScene(state.scene); image = engine.render(state.time, 640, 360).toComposeImageBitmap() }
            val shots = File("build/ui-shots").also { it.mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("inspector-effects.png"))
        }
    }

    @Test fun textAndFillControls() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = state("text")
        val engine = DesktopEngine(assets, File(".."))
        engine.setScene(state.scene)
        var image by mutableStateOf(engine.render(0, 640, 360).toComposeImageBitmap())
        setContent {
            MaterialTheme(colors = darkColors(primary = accent)) { CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize().background(preview)) {
                    SectionTitle("ABSENTMOTION     Inspector project")
                    Row(Modifier.weight(1f)) {
                        Box(Modifier.width(240.dp).fillMaxHeight().background(panel)) { EffectsLibrary(state, listOf(definition), assets) }
                        Box(Modifier.weight(1f)) { Viewer(state, image, null, "Fit", {}, false, {}, {}) { _, _ -> } }
                        Box(Modifier.width(360.dp).fillMaxHeight().background(panel)) { Inspector(state, emptyList()) {} }
                    }
                    Box(Modifier.height(190.dp)) { Timeline(state, 1f, {}, "") }
                }
            } }
        }
        onNodeWithTag("text-content").performScrollTo().performTextClearance()
        onNodeWithTag("text-content").performTextInput("First\nSecond")
        runOnIdle { assertEquals("First\nSecond", state.layer!!.content); state.undo(); assertEquals("", state.layer!!.content); state.redo() }
        onNodeWithText("▾  Layer").performScrollTo().performClick()
        onNodeWithText("▾  Transform").performScrollTo().performClick()
        onNodeWithText("▾  Fill & Color").performScrollTo().performClick()
        runOnIdle { engine.setScene(state.scene); image = engine.render(0, 640, 360).toComposeImageBitmap() }
        val shots = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("inspector-text.png"))
        engine.close()
    }
}
