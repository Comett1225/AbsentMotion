package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.addEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskEffectCardMotionTest {
    @Test fun anEffectCardMovesUpAsItOpensAndBackAsItCloses() {
        System.setProperty("am.data", File("build/cardmotion-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/cardmotion").also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse("""<scene width="1080" height="1920" fps="30" totalTime="3000" bgcolor="#ff202020"><shape id="7" startTime="0" endTime="3000" fillType="color" s=".rect"><transform><location value="540,960,0"/></transform><property name="size" type="vec2" value="400,400"/><fillColor value="#ffff0000"/></shape></scene>"""))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            val names = listOf("absent.chromakey", "absent.tile", "absent.timequant")
            runOnIdle {
                editor.selectedId = 7
                names.forEach { n -> editor.editSelected { it.addEffect(editor.engine.effects.value.find(n)!!.instance()) } }
                editor.panel = EditorPanel.Effects
                editor.effectIndex = -1
            }
            mainClock.advanceTimeBy(800)
            waitForIdle()
            val label = editor.engine.effects.value.find(names.last())!!.name
            fun top() = onAllNodesWithText(label).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
            val start = top()
            fun frame(name: String) = ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
            frame("0-list")
            mainClock.autoAdvance = false
            onAllNodesWithText(label).onFirst().performClick()
            mainClock.advanceTimeBy(120)
            val middle = top()
            frame("1-opening")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val end = top()
            frame("2-open")
            assertTrue("start $start middle $middle end $end", end < middle && middle < start)
            runOnIdle { editor.effectIndex = -1 }
            mainClock.advanceTimeBy(120)
            val back = top()
            frame("3-closing")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            frame("4-list")
            assertTrue("closing $back between $end and $start", back > end && back < top() + 1f)
            mainClock.autoAdvance = true
            runBlocking { app.closeEditor() }
        }
    }
}
