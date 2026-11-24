package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.Scene
import app.absentmotion.model.addEffect
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class DeskEffectControlsTest {
    @Test fun slidersPillsSectionsAndTypedValuesWorkLikeAlight() {
        System.setProperty("am.data", File("build/effect-controls-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse("""<scene width="1080" height="1920" fps="30" totalTime="3000" bgcolor="#ff202020"><shape id="7" startTime="0" endTime="3000" fillType="color" s=".rect"><transform><location value="540,960,0"/></transform><property name="size" type="vec2" value="400,400"/><fillColor value="#ffff0000"/></shape></scene>"""))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            fun settle() { mainClock.advanceTimeBy(700); waitForIdle() }
            fun value(index: Int, name: String) = editor.scene.layer(7)!!.effects[index].effectProperty(name)!!["value"]!!
            runOnIdle {
                editor.selectedId = 7
                listOf("motionblur", "tiles", "texttransform").forEach { n -> editor.editSelected { it.addEffect(editor.engine.effects.value.find("absent.$n")!!.instance()) } }
                editor.panel = EditorPanel.Effects
                editor.effectIndex = 0
            }
            settle()

            val label = onAllNodesWithText("Tune").onFirst().fetchSemanticsNode().boundsInRoot
            val box = onAllNodesWithText("1.00").filter(hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot
            val y = label.center.y
            onRoot().performTouchInput { click(Offset(box.left - 30f, y)) }
            settle()
            assertEquals(1f, value(0, "tune").toFloat(), 0f)
            val inset = 12f * density.density
            val track = box.left - label.right - 2 * inset
            val thumb = label.right + inset + track / 4f
            onRoot().performTouchInput { down(Offset(thumb, y)); moveTo(Offset(thumb + 30f, y)); moveTo(Offset(thumb + track / 4f, y)); up() }
            settle()
            assertEquals(2f, value(0, "tune").toFloat(), 0.05f)

            onAllNodesWithText("2.00").filter(hasClickAction()).onFirst().performClick()
            settle()
            assertEquals(2f, editor.keypad!!.fields.single().value, 0.01f)
            runOnIdle { editor.keypad = null; editor.effectIndex = 1 }
            settle()

            onAllNodesWithText("Inside Tiles").filter(hasClickAction()).onFirst().performClick()
            settle()
            assertEquals(2f, value(1, "sizing").toFloat(), 0f)
            assertTrue(onAllNodesWithText("Inside Tiles").filter(isSelected()).fetchSemanticsNodes().isNotEmpty())
            runOnIdle { editor.effectIndex = 2 }
            settle()

            assertEquals(0, onAllNodesWithText("Start").fetchSemanticsNodes().size)
            onAllNodesWithText("Range").filter(hasClickAction()).onFirst().performClick()
            settle()
            assertEquals(1, onAllNodesWithText("Start").fetchSemanticsNodes().size)
            onAllNodesWithText("Range").filter(hasClickAction()).onFirst().performClick()
            settle()
            assertEquals(0, onAllNodesWithText("Start").fetchSemanticsNodes().size)
            runBlocking { app.closeEditor() }
        }
    }
}
