package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.compat.readFla
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.withPickedFrame
import app.absentmotion.model.FrameRef
import app.absentmotion.model.HoldEasing
import app.absentmotion.model.Scene
import app.absentmotion.model.keyframes
import app.absentmotion.model.shownFrame
import app.absentmotion.pc.engine.DesktopEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class FlaRigTest {
    private fun square(color: String) = """<DOMShape><fills><FillStyle index="1"><SolidColor color="$color"/></FillStyle></fills>
        <edges><Edge fillStyle1="1" edges="!0 0|2000 0|2000 2000|0 2000|0 0"/></edges></DOMShape>"""

    private val mouth = """<DOMSymbolItem xmlns="http://ns.adobe.com/xfl/2008/" name="Mouth" symbolType="graphic"><timeline><DOMTimeline name="Mouth"><layers>
        <DOMLayer name="a"><frames>${listOf("#FF0000", "#00FF00", "#0000FF").withIndex().joinToString("") { (i, c) -> """<DOMFrame index="$i"><elements>${square(c)}</elements></DOMFrame>""" }}</frames></DOMLayer>
        </layers></DOMTimeline></timeline></DOMSymbolItem>"""

    private fun pick(index: Int, first: Int) = """<DOMFrame index="$index" duration="4"><elements>
        <DOMSymbolInstance libraryItemName="Mouth" symbolType="graphic" loop="single frame" firstFrame="$first"><matrix><Matrix tx="150" ty="100"/></matrix></DOMSymbolInstance></elements></DOMFrame>"""

    private val document = """<DOMDocument xmlns="http://ns.adobe.com/xfl/2008/" width="400" height="300" frameRate="24" backgroundColor="#FFFFFF"><timelines><DOMTimeline name="Scene 1"><layers>
        <DOMLayer name="Face"><frames>${pick(0, 0)}${pick(4, 2)}${pick(8, 1)}</frames></DOMLayer></layers></DOMTimeline></timelines></DOMDocument>"""

    private fun middle(frame: Int) = (2 * frame + 1) * 500 / 24

    @Test fun anImportedRigShowsTheFrameEachKeyframePicksAndTakesANewPick() {
        val scene = Scene(readFla(mapOf("DOMDocument.xml" to document, "LIBRARY/Mouth.xml" to mouth)).root)
        val rig = scene.layers.single()
        assertEquals(listOf(0, 0, 2, 2, 1), listOf(0, 3, 4, 7, 11).map { rig.shownFrame(middle(it).toDouble(), 24) })
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            fun colour(frame: Int) = engine.render(middle(frame), 400, 300).getRGB(200, 150) and 0xFFFFFF
            engine.setScene(scene, null)
            assertEquals(listOf(0xFF0000, 0xFF0000, 0x0000FF, 0x0000FF, 0x00FF00), listOf(0, 3, 4, 7, 11).map(::colour))
            val picked = scene.update(rig.id, middle(5)) { it.withPickedFrame(0) }
            engine.setScene(picked, null)
            assertEquals(listOf(0xFF0000, 0x0000FF, 0xFF0000, 0xFF0000, 0x00FF00), listOf(0, 4, 5, 7, 11).map(::colour))
        }
    }

    @Test fun theFrameTileStepsKeysAndTurnsOffTheRigFrame() {
        System.setProperty("am.data", File("build/fla-rig-data").also { it.deleteRecursively() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene(readFla(mapOf("DOMDocument.xml" to document, "LIBRARY/Mouth.xml" to mouth)).root))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            fun keys() = editor.selected!!.keyframes(FrameRef)
            fun step(name: String) = onNode(hasContentDescription(name) and hasClickAction() and hasAnySibling(hasText("/", substring = true))).performClick()
            runOnIdle { editor.selectedId = editor.scene.layers.single().id; editor.frame = 0 }
            mainClock.advanceTimeBy(600)
            onAllNodesWithText("Frame").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(600)
            assertEquals(EditorPanel.Frame, editor.panel)
            onNodeWithText("1 / 3").assertExists()
            step("Next frame")
            mainClock.advanceTimeBy(300)
            onNodeWithText("2 / 3").assertExists()
            assertEquals(listOf("1.000000", "2.000000", "1.000000"), keys().map { it.v })
            runOnIdle { editor.frame = 5 }
            mainClock.advanceTimeBy(300)
            onNodeWithText("3 / 3").assertExists()
            step("Previous frame")
            mainClock.advanceTimeBy(300)
            onNodeWithText("2 / 3").assertExists()
            assertEquals(4, keys().size)
            assertEquals(HoldEasing, keys()[2].e)
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File("build/ui-shots").also { it.mkdirs() }.resolve("fla-rig-frame.png"))
            onAllNodes(hasContentDescription("Remove keyframe") and hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            assertEquals(3, keys().size)
            onNodeWithText("3 / 3").assertExists()
            onAllNodesWithText("Off").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(300)
            assertNull(editor.selected!!.node.child("frame"))
            runOnIdle { editor.undo() }
            assertEquals(3, keys().size)
            runBlocking { app.closeEditor() }
            app.engine.close()
        }
    }
}
