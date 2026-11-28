package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.Scene
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.parseColor
import app.absentmotion.platform.readPreviewPixel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskKeyColorPickTest {
    private val folder = File("build/key-color-pick").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }

    private fun picture(name: String, rgb: Int): String {
        val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, 400, 400)
        g.dispose()
        return File(folder, name).also { ImageIO.write(image, "png", it) }.toURI().toString()
    }

    private fun green(argb: Int) = (argb shr 16 and 255) < 40 && (argb shr 8 and 255) > 215 && (argb and 255) < 40

    @Test fun theEyedropperPicksTheKeyedClipsOwnColourNotTheClipBehind() {
        System.setProperty("am.data", File(folder, "data").also { it.mkdirs() }.absolutePath)
        val back = picture("back.png", 0xe08020)
        val screen = picture("screen.png", 0x00ff00)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            for ((effect, param) in listOf("chromakey" to "key", "advancedchromakey" to "c1color")) {
                fun layer(id: Int, uri: String, inner: String = "") =
                    """<shape id="$id" startTime="0" endTime="3000" s=".rect" fillType="media" fillImage="$uri" mediaFillMode="fill"><transform><location value="200,200,0"/></transform><property name="size" type="vec2" value="200,200"/>$inner</shape>"""
                val id = app.store.create(Scene.parse(
                    """<scene width="400" height="400" fps="30" totalTime="3000" bgcolor="#ff000000">""" +
                        """<media uri="$back" type="image/png" width="400" height="400"/><media uri="$screen" type="image/png" width="400" height="400"/>""" +
                        layer(1, back) + layer(2, screen, """<effect id="absent.$effect"/>""") + "</scene>",
                ))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                fun settle() { mainClock.advanceTimeBy(700); waitForIdle() }
                fun freshPreview() {
                    settle()
                    val seen = app.engine.renders
                    app.engine.restart()
                    waitUntil(timeoutMillis = 10_000) { app.engine.renders > seen }
                }
                runOnIdle {
                    editor.selectedId = 2
                    editor.panel = EditorPanel.Effects
                    editor.effectIndex = 0
                }
                freshPreview()
                onAllNodesWithContentDescription("Palettes").onFirst().performClick()
                settle()
                onAllNodesWithText("Eyedropper").onFirst().performClick()
                assertNotNull(editor.eyedropper)
                repeat(3) { sample ->
                    freshPreview()
                    var picked: Int? = null
                    readPreviewPixel(editor.previewSurface, 0.5f, 0.5f) { picked = it }
                    runOnIdle { editor.eyedropper!!.invoke(picked!!) }
                    settle()
                    val chosen = parseColor(editor.scene.layer(2)!!.effects[0].effectProperty(param)!!["value"]!!)!!
                    assertTrue("$effect sample $sample picked ${Integer.toHexString(chosen)}, the colour behind the green clip", green(chosen))
                }
                runBlocking { app.closeEditor() }
            }
        }
    }
}
