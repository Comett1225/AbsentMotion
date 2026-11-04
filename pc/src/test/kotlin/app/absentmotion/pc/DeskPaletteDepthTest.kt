package app.absentmotion.pc

import android.content.Desk
import android.widget.Toast
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FullColor
import app.absentmotion.library.PaletteLibrary
import app.absentmotion.model.Scene
import app.absentmotion.model.addEffect
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskPaletteDepthTest {
    private val shots = File("build/ui-shots/deskport").also { it.mkdirs() }
    private val saved = listOf("am.data", "am.pick", "am.exports").associateWith { System.getProperty(it) }

    @After fun restoreProperties() {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }

    private fun fresh(name: String) = File("build/deskport-$name-${System.nanoTime()}").also { it.mkdirs() }.canonicalFile

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.open(app: DeskAppState, xml: String): Pair<EditorState, String> {
        val id = app.store.create(Scene.parse(xml))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
        return app.editor!! to id
    }

    private fun ComposeUiTest.settle(ms: Long = 500) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    private fun ComposeUiTest.top(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.beside(label: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val row = onAllNodesWithText(label).onFirst().fetchSemanticsNode().boundsInRoot
        val found = onAllNodes(matcher).fetchSemanticsNodes().filter { it.boundsInRoot.center.y in row.top..row.bottom }
        return onAllNodes(matcher).filter(SemanticsMatcher("same row as $label") { node -> found.any { it.id == node.id } }).onFirst()
    }

    private fun ComposeUiTest.rightOf(label: String): SemanticsNodeInteraction {
        val text = onAllNodesWithText(label).onFirst().fetchSemanticsNode().boundsInRoot
        val found = onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.center.y in text.top..text.bottom && it.boundsInRoot.left >= text.right - 1f }
            .minBy { it.boundsInRoot.left }
        return onAllNodes(hasClickAction()).filter(SemanticsMatcher("right of $label") { it.id == found.id }).onFirst()
    }

    @Test fun paletteSheetImportsAGplFileAndItsColourFillsTheLayer() {
        System.setProperty("am.data", fresh("palette-data").path)
        val exports = fresh("palette-exports")
        System.setProperty("am.exports", exports.path)
        val gpl = fresh("palette-file").resolve("Sunset.gpl").apply { writeText("GIMP Palette\nName: Sunset\n#\n255  94  58\tOrange\n 20 120 200\tBlue\n") }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val (editor, _) = open(app, File("../testdata/scenes/shapes.xml").readText())
            val library = PaletteLibrary.at(Desk.context.filesDir)
            val circle = editor.scene.layers.first { it.label == "Circle" }.id
            runOnIdle { editor.selectedId = circle; editor.panel = EditorPanel.ColorFill }
            settle()
            onAllNodesWithText("Default").onFirst().assertExists()
            shot("1-fill-color-palette-chip")

            onAllNodesWithContentDescription("Palettes").onFirst().performClick()
            settle()
            onAllNodesWithText("From file").filter(hasClickAction()).onFirst().assertExists()
            shot("2-palette-sheet")

            System.setProperty("am.pick", gpl.path)
            onAllNodesWithText("From file").filter(hasClickAction()).onFirst().performClick()
            waitUntil(timeoutMillis = 10_000) { library.user.any { it.name == "Sunset" } }
            settle()
            runOnIdle {
                assertEquals("Sunset", library.chosen.name)
                assertEquals(listOf(0xFF5E3A, 0x1478C8), library.chosen.colors)
            }
            onAllNodesWithText("In use").onFirst().assertExists()
            shot("3-palette-imported")

            onAllNodesWithText("Export").filter(hasClickAction()).onLast().performClick()
            settle()
            onAllNodesWithText("GIMP palette (.gpl)").filter(hasClickAction()).onFirst().performClick()
            val written = exports.resolve("Documents/AbsentMotion/Sunset.gpl")
            waitUntil(timeoutMillis = 10_000) { written.isFile }
            assertTrue(written.readText().contains("20 120 200"))
            waitUntil(timeoutMillis = 5_000) { Toast.current.value?.text?.contains("Documents/AbsentMotion") == true }

            runOnIdle { DeskBack.back() }
            settle()
            runOnIdle { DeskBack.back() }
            settle()
            assertEquals(0, onAllNodesWithText("From file").fetchSemanticsNodes().size)
            onAllNodesWithText("Sunset").onFirst().assertExists()
            shot("4-fill-color-sunset-swatches")

            onAllNodesWithContentDescription("#1478C8 (100%)").onFirst().performClick()
            settle()
            runOnIdle { assertEquals(0xFF1478C8.toInt(), editor.scene.layer(circle)!!.fillColor) }

            runOnIdle { editor.fullColor = FullColor(0xff336699.toInt()) { _, _ -> } }
            settle()
            assertEquals(2, onAllNodesWithText("Sunset").fetchSemanticsNodes().size)
            shot("5-full-colour-picker-palette")
            runOnIdle { editor.fullColor = null }
            settle()

            val effect = editor.engine.effects.value.find("absent.chromakey")!!
            runOnIdle { editor.editSelected { it.addEffect(effect.instance()) }; editor.panel = EditorPanel.Effects }
            settle()
            runOnIdle { editor.effectIndex = 0 }
            settle()
            assertEquals(0, onAllNodesWithText("Sunset").fetchSemanticsNodes().size)
            rightOf("Key Color").performClick()
            settle()
            onAllNodesWithText("Sunset").onFirst().assertExists()
            shot("6-effect-colour-palette")
            onAllNodesWithText("Sunset").onFirst().performClick()
            settle()
            onAllNodesWithText("From file").filter(hasClickAction()).onFirst().assertExists()
            runOnIdle { DeskBack.back() }
            settle()
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun droppedPaletteFilesGoToThePaletteLibrary() {
        System.setProperty("am.data", fresh("drop-data").path)
        val folder = fresh("drop-files")
        val gpl = folder.resolve("Ocean.gpl").apply { writeText("GIMP Palette\n#\n0 64 128\n0 128 255\n") }
        val picture = File("../testdata/media/test-image.jpg").canonicalFile
        val app = DeskAppState()
        assertEquals(listOf(picture), app.takePalettes(listOf(gpl, picture)))
        val library = PaletteLibrary.at(Desk.context.filesDir)
        assertEquals("Ocean", library.chosen.name)
        assertEquals(listOf(0x004080, 0x0080FF), library.chosen.colors)
        val broken = folder.resolve("Broken.ase").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf(broken), app.takePalettes(listOf(broken)))
    }

    @Test fun sortByPieceAndMixPiecesWriteTheProjectXml() {
        System.setProperty("am.data", fresh("depth-data").path)
        val obj = fresh("depth-model").resolve("tri.obj").apply { writeText("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n") }
        val xml = """<scene title="Depth" width="400" height="400" totalTime="2999" fps="30" depthOrder="true">
            <camera id="3001" label="Camera" startTime="0" endTime="2999" type="perspective"><transform><location value="200,200,-346" /></transform><fov value="60" /></camera>
            <shape id="3002" label="Pieces" startTime="0" endTime="2999" fillType="color" mediaFillMode="fill" s=".roundrect"><transform><location value="200,200,0" /></transform><fillColor value="#ff3e63dd" /><property name="size" type="vec2" value="100,100" /><effect id="absent.scatter3d" hidden="false" /></shape>
            <model id="3003" label="Model" startTime="0" endTime="2999" src="${obj.toURI()}"><transform><location value="200,200,0" /></transform><property name="size" type="float" value="120" /></model>
        </scene>"""
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val (editor, id) = open(app, xml)
            runOnIdle { editor.selectedId = 3001; editor.panel = EditorPanel.Camera }
            settle()
            val perspective = top(onAllNodesWithText("Perspective").onFirst())
            assertTrue(top(onAllNodesWithText("By piece").onFirst()) > top(onAllNodesWithText("Zoom").onFirst()))
            onAllNodesWithText("By piece").filter(hasClickAction()).onFirst().performClick()
            settle()
            runOnIdle { assertEquals("piece", editor.scene.node["depthSort"]) }
            shot("7-camera-view-sort-by-piece")
            runOnIdle { editor.edit { it.withDepthOrder(false) } }
            settle()
            assertEquals(0, onAllNodesWithText("By piece").fetchSemanticsNodes().size)
            assertEquals(perspective, top(onAllNodesWithText("Perspective").onFirst()))
            runOnIdle { editor.edit { it.withDepthOrder(true) } }
            settle()

            runOnIdle { editor.selectedId = 3002; editor.panel = EditorPanel.Effects }
            settle()
            runOnIdle { editor.effectIndex = 0 }
            settle()
            val mix = onAllNodesWithText("Mix pieces with other layers").onFirst()
            beside("Mix pieces with other layers", isToggleable()).performScrollTo()
            settle()
            assertTrue(top(mix) > onAllNodesWithText("Shape").fetchSemanticsNodes().maxBy { it.boundsInRoot.left }.boundsInRoot.bottom)
            beside("Mix pieces with other layers", isToggleable()).assertIsOn()
            shot("8-effect-card-mix-pieces")
            beside("Mix pieces with other layers", isToggleable()).performClick()
            settle()
            runOnIdle { assertEquals("false", editor.scene.layer(3002)!!.node["mixPieces"]) }

            runOnIdle { editor.selectedId = 3003; editor.panel = EditorPanel.Model }
            settle()
            onAllNodesWithText("Mix pieces with other layers").onFirst().performScrollTo()
            settle()
            shot("9-model-options-mix-pieces")
            beside("Mix pieces with other layers", isToggleable()).performClick()
            settle()
            runOnIdle { assertEquals("false", editor.scene.layer(3003)!!.node["mixPieces"]) }

            runBlocking { app.closeEditor() }
            val stored = runBlocking { app.load(id) }!!
            assertEquals("piece", stored.node["depthSort"])
            assertEquals("false", stored.layer(3002)!!.node["mixPieces"])
            assertEquals("false", stored.layer(3003)!!.node["mixPieces"])
            assertNull(stored.layer(3001)!!.node["mixPieces"])
            assertFalse(stored.toXml().contains("depthSort=\"layer\""))
        }
    }
}
