package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.easing
import app.absentmotion.model.PropRef
import app.absentmotion.model.handles
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class EasingGraphDragTest {
    @Test fun aQuickDragThatStartsOnAHandleMovesItRightAway() {
        System.setProperty("am.data", File("build/easing-drag-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        val id = app.store.create(Scene.parse("""
            <scene title="e" width="640" height="360" fps="30" totalTime="2000">
              <shape id="1" startTime="0" endTime="2000" s=".rect" fillType="color">
                <transform><location><kf t="0" v="100,100,0"/><kf t="1" v="500,200,0" e="cubicBezier 0.25 0.25 0.75 0.75"/></location></transform>
                <fillColor value="#ffffffff"/><property name="size" type="vec2" value="80,80"/>
              </shape>
            </scene>
        """.trimIndent()))
        val state = runBlocking { app.openNow(id)!! }
        try {
            state.selectedId = 1
            state.keyTarget = KeyTarget(PropRef.Location, "0,0,0")
            state.frame = 30
            val before = state.easing!!
            assertEquals("cubicBezier", before.type)
            runSkikoComposeUiTest(size = Size(1150f, 400f)) {
                setContent { EasingPanel(state, Modifier.size(1150.dp, 400.dp)) }
                waitForIdle()
                val graphLeft = 1150f * 168f / 1080f
                val handle = before.handles().first()
                val width = 1150f * 575f / 1080f
                val scale = width / 575f
                val graphH = 400f / scale
                val s = (graphH - 84f) / 1.5f
                val at = Offset(graphLeft + (42f + 491f * handle.x) * scale, (graphH / 2f - (handle.y - 0.5f) * s) * scale)
                onRoot().performTouchInput { down(at); moveTo(at + Offset(160f, -90f)); up() }
                waitForIdle()
            }
            val after = state.easing!!.handles().first()
            assertNotEquals(before, state.easing)
            assertEquals(true, after.x > before.handles().first().x + 0.2f && after.y > before.handles().first().y + 0.2f)
        } finally {
            runBlocking { app.closeEditor() }
            app.engine.close()
        }
    }
}
