package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextDrawingProgressTest {
    private fun inked(effect: String): Int {
        val scene = Scene.parse(
            """<scene width="200" height="100" fps="30" totalTime="1000" bgcolor="#ff000000">
            <text id="1" startTime="0" endTime="1000" fillType="color" size="40" wrapWidth="200" align="center"><transform><location value="100,50,0"/></transform>
            <fillColor value="#ffffffff"/>$effect<content>arungi</content></text></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            val image = engine.render(0, 200, 100)
            return (0 until 200).sumOf { x -> (0 until 100).count { y -> image.getRGB(x, y) and 0xff > 128 } }
        }
    }

    private fun progress(end: Float, style: Int?) = """<effect id="absent.drawingprogress"><property name="end" type="float" value="$end"/>""" +
        (style?.let { """<property name="style" type="float" value="$it"/>""" } ?: "") + "</effect>"

    @Test fun drawingProgressDrawsTextOutlinesFromStartToEnd() {
        val full = inked("")
        assertTrue(full > 300)
        assertEquals(0, inked(progress(0f, 1)))
        val little = inked(progress(0.15f, 1))
        val half = inked(progress(0.5f, 1))
        assertTrue("$little < $half < $full", little in 1 until half && half < full)
        assertTrue(inked(progress(1f, 1)) in full * 9 / 10..full * 11 / 10)
    }

    @Test fun savedProjectsWithoutTheStyleKeepWholeText() {
        assertEquals(inked(""), inked(progress(0.2f, null)))
    }
}
