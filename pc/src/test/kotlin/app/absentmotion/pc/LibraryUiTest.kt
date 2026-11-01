package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.pc.media.importMedia
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.*
import app.absentmotion.pc.ui.library.LibrarySettings
import java.io.File
import java.util.prefs.AbstractPreferences
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LibraryUiTest {
    private val assets = File("../app/src/main/assets")
    private fun screenshot(image: java.awt.image.BufferedImage, name: String) {
        val folder = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(image, "png", folder.resolve(name))
    }

    @Test fun searchPresetFavoriteAddAndUndo() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val prefs = MemoryPreferences()
        val settings = LibrarySettings(prefs)
        val def = EffectDefinition.parse(JSONObject(assets.resolve("effects/ascii.json").readText()))
        val state = ProjectState()
        state.open(Scene.parse(File("../testdata/scenes/shapes.xml").readText()), null)
        state.selected = 1001
        setContent {
            MaterialTheme(colors = darkColors()) { CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.fillMaxSize().background(preview)) {
                    Box(Modifier.width(280.dp).fillMaxHeight().background(panel)) { EffectsLibrary(state, listOf(def), assets, settings) }
                    Box(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(300.dp).fillMaxHeight().background(panel)) { Inspector(state, listOf(def)) {} }
                }
            } }
        }
        onNodeWithText("Search").assertExists()
        onAllNodes(hasSetTextAction())[0].performTextInput("Binary Rain")
        onNodeWithText("ASCII", substring = false).performClick()
        onNodeWithText("Binary Rain", substring = false).assertExists()
        screenshot(onRoot().captureToImage().toAwtImage(), "effects-library.png")
        runOnIdle { settings.toggle(def.id, def.presets[1].slug); assertTrue(LibrarySettings(prefs).contains(def.id, def.presets[1].slug)) }
        onNodeWithText("Binary Rain", substring = false).performClick()
        runOnIdle { assertEquals(def.id, state.layer!!.effects.single()["id"]); state.undo(); assertTrue(state.layer!!.effects.isEmpty()) }
    }

    @Test fun mediaAndElementsAddUndo() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState()
        val file = File("build/library-test/project.xml")
        file.parentFile.mkdirs()
        state.open(Scene.parse(File("../testdata/scenes/shapes.xml").readText()), file)
        val media = importMedia(listOf(File("../testdata/media/test-image.jpg")), file).single()
        state.edit { it.withMedia(media) }
        setContent {
            MaterialTheme(colors = darkColors()) { CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.fillMaxSize().background(preview)) {
                    Box(Modifier.width(280.dp).fillMaxHeight().background(panel)) { MediaPool(state) {} }
                    Box(Modifier.width(280.dp).fillMaxHeight().background(panel)) { Elements(state, assets) }
                    Box(Modifier.weight(1f))
                }
            } }
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("library-image").fetchSemanticsNodes().isNotEmpty() }
        screenshot(onRoot().captureToImage().toAwtImage(), "media-elements.png")
        onNodeWithText("test-image", substring = false).performTouchInput { doubleClick() }
        runOnIdle { assertEquals(media.uri, state.layer!!.node["fillImage"]); state.undo(); assertNull(state.scene.layer(state.selected)) }
        onNodeWithText("Solid colour", substring = false).performClick()
        runOnIdle { assertEquals(".rect", state.layer!!.node["s"]); state.undo(); assertNull(state.scene.layer(state.selected)) }
        onNodeWithText("Circle", substring = false).performTouchInput { swipe(Offset(20f, 8f), Offset(420f, 8f)) }
        runOnIdle { assertEquals(".circle", state.layer!!.node["s"]); state.undo() }
        onNodeWithText("Text", substring = false).performClick()
        runOnIdle { assertEquals("text", state.layer!!.kind); assertTrue(File(java.net.URI(media.uri)).parentFile.canonicalFile == file.parentFile.resolve("project_media").canonicalFile) }
    }
}

private class MemoryPreferences : AbstractPreferences(null, "") {
    private val values = mutableMapOf<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi() = values.keys.toTypedArray()
    override fun childrenNamesSpi() = emptyArray<String>()
    override fun childSpi(name: String) = MemoryPreferences()
    override fun syncSpi() {}
    override fun flushSpi() {}
}
