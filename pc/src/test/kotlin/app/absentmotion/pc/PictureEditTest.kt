package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.resetValue
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.formatFloats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class PictureEditTest {
    @Test fun pictureStaysAfterEdits() {
        val data = File("build/picture-edit-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val image = File("../testdata/media/test-image.jpg").absoluteFile.toURI().toString()
        val xml = """<scene title="Pic" width="1080" height="1080" totalTime="2000" fps="30" bgcolor="#00000000"><media uri="$image" type="image/jpeg"/><shape id="7" label="Pic" startTime="0" endTime="2000" fillType="media" fillImage="$image" mediaFillMode="fill" s=".rect"><transform><location value="540,540,0"/></transform><property name="size" type="vec2" value="300,300"/></shape></scene>"""
        runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            runBlocking { app.openNow(app.store.create(Scene.parse(xml))) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            fun painted(): Boolean {
                mainClock.advanceTimeBy(300); Thread.sleep(800); mainClock.advanceTimeBy(300)
                return (0..8).any { i -> (0..8).any { j ->
                    val c = app.engine.pixelAt(0.42f + i * 0.02f, 0.42f + j * 0.02f) ?: 0
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    maxOf(r, g, b) - minOf(r, g, b) > 80
                } }
            }
            assertTrue("picture shown at start", painted())
            for (factor in listOf(1.5f, 3f)) {
                runOnIdle { editor.edit { sc -> sc.update(7L) { l -> l.resetValue(PropRef.Scale, formatFloats(floatArrayOf(factor, factor))) } } }
                assertTrue("picture shown after scaling to $factor", painted())
            }
        }
    }
}
