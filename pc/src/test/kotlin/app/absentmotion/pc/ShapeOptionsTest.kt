package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.addShape
import app.absentmotion.editor.requestPoints
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.editor.KeyTarget
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.outlineShape
import app.absentmotion.pc.engine.DesktopEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ShapeOptionsTest {
    private val repo = File("..").canonicalFile

    private fun data(name: String) = System.setProperty("am.data", File("build/shape-options-$name-data").also { it.deleteRecursively() }.absolutePath)

    private fun outline(state: app.absentmotion.editor.EditorState) = state.selected!!.node.child("path")!!["d"]

    private fun teeth(state: app.absentmotion.editor.EditorState) = state.selected!!.propText("teeth")

    @Test fun gearOptionsEditUndoSaveReopenConvertAndDraw() {
        data("flow")
        val app = DeskAppState()
        val id = app.store.create(Scene.create("Shapes", 1080, 1080, 30, 0))
        try {
            val state = runBlocking { app.openNow(id)!! }
            state.addShape(ShapeCatalog.all.single { it.outline == "gear" })
            assertEquals("gear", state.selected!!.outlineShape)
            assertEquals("the finished outline is written for older readers", "evenodd", state.selected!!.node.child("path")!!["fillRule"])
            val eight = outline(state)
            state.editSelected { it.withProp("teeth", "float", "16") }
            val sixteen = outline(state)
            assertNotEquals(eight, sixteen)
            state.undo()
            assertEquals(eight, outline(state))
            assertEquals("8.000000", teeth(state))
            state.redo()
            assertEquals(sixteen, outline(state))
            runBlocking { app.closeEditor() }
            val saved = app.store.load(id)!!.layers.single()
            assertEquals("gear", saved.outlineShape)
            assertEquals("16", saved.propText("teeth"))
            assertEquals(sixteen, saved.node.child("path")!!["d"])

            val again = runBlocking { app.openNow(id)!! }
            again.selectedId = again.scene.layers.single().id
            again.requestPoints(enter = false)
            assertNull("Convert keeps no template", again.selected!!.outlineShape)
            assertNull(again.selected!!.propText("teeth"))
            assertNotNull("Round Corners stays live", again.selected!!.propText("roundCorners"))
            again.undo()
            assertEquals("gear", again.selected!!.outlineShape)
            again.keyTarget = KeyTarget(PropRef.shape("teeth"), "16")
            again.toggleKeyframe()
            again.frame = 20
            again.editSelected { it.withProp("teeth", "float", "6") }
            again.requestPoints(enter = false)
            assertEquals("animated options are not dropped without asking", "gear", again.selected!!.outlineShape)
            assertNotNull(again.pointsAsk)
            again.pointsAsk!!()
            assertNull(again.selected!!.outlineShape)
            again.undo()
            assertEquals("gear", again.selected!!.outlineShape)
            runBlocking { app.closeEditor() }
        } finally {
            app.engine.close()
        }
        val scene = app.store.load(id)!!
        val older = Scene(scene.node.copy(children = scene.node.children.map { if (it.name == "shape") it.with("outlineShape", "newer-than-this-reader") else it }))
        DesktopEngine(repo.resolve("shared/assets"), repo).use { engine ->
            engine.setScene(scene, repo)
            val drawn = engine.render(0, 270, 270).getRGB(0, 0, 270, 270, null, 0, 270)
            engine.setScene(older, repo)
            val stored = engine.render(0, 270, 270).getRGB(0, 0, 270, 270, null, 0, 270)
            assertTrue("the desktop engine draws the gear", drawn.toSet().size > 1)
            assertTrue("an older reader draws the same gear", drawn.indices.count { drawn[it] != stored[it] } < drawn.size / 100)
        }
    }

    @Test fun shapePanelShowsRoundCornersFirstAndExtrasBehindAdvanced() {
        data("panel")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.create("Shapes", 1080, 1080, 30, 0))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            runOnIdle {
                editor.addShape(ShapeCatalog.all.single { it.outline == "gear" })
                editor.panel = EditorPanel.EditShape
            }
            mainClock.advanceTimeBy(600)
            for (label in listOf("Round Corners", "Size", "Teeth", "Tooth Depth", "Tooth Width", "Hole", "Advanced")) {
                assertTrue(label, onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
            }
            assertTrue(onAllNodesWithText("Tooth Base").fetchSemanticsNodes().isEmpty())
            onAllNodesWithText("Advanced").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(400)
            assertTrue(onAllNodesWithText("Tooth Base").fetchSemanticsNodes().isNotEmpty())
            runOnIdle {
                editor.selectedId = null
                editor.addShape(ShapeCatalog.all.single { it.id == ".roundrect" })
                editor.panel = EditorPanel.EditShape
            }
            mainClock.advanceTimeBy(600)
            assertEquals("one rounding control for a shape with its own radius", 1, onAllNodesWithText("Round Corners").fetchSemanticsNodes().size)
            assertTrue(onAllNodesWithText("Radius").fetchSemanticsNodes().isEmpty())
            assertFalse(onAllNodesWithText("Advanced").fetchSemanticsNodes().isNotEmpty())
            runBlocking { app.closeEditor() }
            app.engine.close()
        }
    }
}
