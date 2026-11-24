package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class DeskMediaFillTest {
    @Test fun theMediaFillRowSwitchesFillFitAndStretchWithUndo() {
        System.setProperty("am.data", File("build/media-fill-data").also { it.deleteRecursively() }.absolutePath)
        val repo = File("..").canonicalFile
        val image = repo.resolve("testdata/media/test-image.jpg").toURI().toString().replaceFirst("file:/", "file:///")
        val xml = """<scene title="Fill" width="1080" height="1920" exportWidth="1080" exportHeight="1920" bgcolor="#ff202020" totalTime="3000" fps="30">
  <shape id="1" label="Picture box" startTime="0" endTime="3000" fillType="media" fillImage="$image" mediaFillMode="fill" s=".rect">
    <transform><location value="540.000000,700.000000,0.000000" /></transform>
    <property name="size" type="vec2" value="300.000000,400.000000" />
  </shape>
</scene>"""
        runSkikoComposeUiTest(size = Size(1600f, 900f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse(xml))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            runOnIdle { editor.selectedId = editor.scene.layers.first().id }
            mainClock.advanceTimeBy(500)
            onAllNodesWithText("Fill & Color").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(800)
            onAllNodesWithText("Fit").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(800)
            runOnIdle { assertEquals("fit", editor.scene.layers.first().node["mediaFillMode"]) }
            onAllNodesWithText("Stretch").filter(hasClickAction()).onFirst().performClick()
            mainClock.advanceTimeBy(800)
            runOnIdle { assertEquals("stretch", editor.scene.layers.first().node["mediaFillMode"]) }
            runOnIdle { editor.undo() }
            mainClock.advanceTimeBy(300)
            runOnIdle { assertEquals("fit", editor.scene.layers.first().node["mediaFillMode"]) }
            runBlocking { app.closeEditor() }
        }
    }
}
