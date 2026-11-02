package app.absentmotion.pc

import app.absentmotion.desk.DeskOs
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class DeskEmojiTest {
    private val xml = """<scene width="540" height="270" fps="30" totalTime="1000" bgcolor="#ff000000"><text id="1" startTime="0" endTime="999" fillType="color" size="60.000000" font="googlefonts?name=Roboto&amp;weight=400" wrapWidth="500" align="center"><transform><location value="270.000000,135.000000,0.000000" /></transform><fillColor value="#ffffffff" /><content>😀🎉🇬🇧</content></text></scene>"""

    private fun colourful(engine: DesktopEngine): Int {
        engine.setScene(Scene.parse(xml), null)
        val image = engine.render(0, 540, 270)
        val dir = File("build/ui-shots/emoji").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "emoji-${System.nanoTime()}.png"))
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            if (maxOf(r, g, b) - minOf(r, g, b) > 60) count++
        }
        return count
    }

    @Test fun systemEmojiRendersInColour() {
        assumeNotNull(DeskOs.systemEmoji)
        val root = File("..").canonicalFile
        val system = DesktopEngine(root.resolve("shared/assets"), root, bundledEmoji = false).use { colourful(it) }
        val bundled = DesktopEngine(root.resolve("shared/assets"), root, bundledEmoji = true).use { colourful(it) }
        println("emoji colour pixels system=$system bundled=$bundled font=${DeskOs.systemEmoji}")
        assertTrue(system > 1500)
        assertTrue(bundled > 1500)
    }
}
