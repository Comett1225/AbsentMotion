package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class DeskWarmGlowTest {
    private val out = File("build/warm-glow").canonicalFile
    private val side = 400

    private fun picture(): String {
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(240, 230, 200)
        g.fillRect(0, 0, side, side)
        g.color = Color(20, 20, 30)
        g.fillRect(170, 170, 60, 60)
        g.fillRect(40, 180, 30, 40)
        g.dispose()
        val file = File(out, "picture.png")
        ImageIO.write(image, "png", file)
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun scene(uri: String, effect: String) = Scene.parse("""<scene title="w" width="$side" height="$side" fps="30" totalTime="999" bgcolor="#ff000000">
<media uri="$uri" filename="p.png" title="p.png" type="image/png" width="$side" height="$side"/>
<shape id="1" startTime="0" endTime="999" fillType="media" fillImage="$uri" mediaFillMode="fill" s=".rect"><transform><location value="${side / 2},${side / 2},0"/></transform>
<property name="size" type="vec2" value="${side / 2},${side / 2}"/>$effect</shape></scene>""")

    private fun glow(vararg props: Pair<String, String>) = """<effect id="absent.warmglow">""" +
        props.joinToString("") { (name, value) -> """<property name="$name" type="${if (',' in value) "vec2" else "float"}" value="$value"/>""" } + "</effect>"

    private fun ByteArray.level(x: Int, y: Int): Int = (0..2).sumOf { this[(y * side + x) * 4 + it].toInt() and 255 } / 3

    @Test fun shadowsSpreadIntoTheBrightPartsOnlyInsideTheMask() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.data", out.resolve("data").path)
        val uri = picture()
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            assertEquals("ok", engine.effects.first { it.definitions.isNotEmpty() }.find("absent.warmglow")?.status)
            fun render(effect: String): ByteArray = runBlocking {
                val s = scene(uri, effect)
                checkNotNull(engine.exportSession(s.toXml()) { still(s.frameStart(5), side, side) })
            }
            val plain = render("")
            val glow = render(glow("blur" to "50"))
            assertTrue("the picture fills the frame", plain.level(5, 5) > 200 && plain.level(200, 200) < 40)
            for (y in 0 until side step 3) for (x in 0 until side step 3) {
                assertTrue("Darken never brightens ($x, $y): ${glow.level(x, y)} > ${plain.level(x, y)}", glow.level(x, y) <= plain.level(x, y) + 2)
            }
            assertTrue("a bright point beside the dark square turns darker: ${glow.level(160, 200)}", glow.level(160, 200) < plain.level(160, 200) - 40)
            assertTrue("the dark square stays dark", glow.level(200, 200) < 40)
            assertTrue("a far bright point keeps its look: ${glow.level(340, 50)}", abs(glow.level(340, 50) - plain.level(340, 50)) <= 2)
            val weak = render(glow("blur" to "5"))
            assertTrue("a low level keeps the shadow close: ${weak.level(160, 200)} vs ${glow.level(160, 200)}", weak.level(160, 200) > glow.level(160, 200) + 30)
            val left = render(glow("blur" to "50", "mask" to "1", "maskCenter" to "0.25,0.5", "maskWidth" to "0.5", "maskHeight" to "1", "feather" to "0"))
            assertTrue("inside the mask the shadow spreads: ${left.level(75, 200)}", left.level(75, 200) < plain.level(75, 200) - 40)
            for (y in 0 until side step 4) for (x in 210 until side step 4) {
                assertTrue("outside the mask nothing changes ($x, $y)", abs(left.level(x, y) - plain.level(x, y)) <= 1)
            }
            for (shape in listOf("2", "3")) {
                val round = render(glow("blur" to "50", "mask" to shape, "maskCenter" to "0.5,0.5", "maskWidth" to "0.4", "maskHeight" to "0.4", "feather" to "0"))
                assertTrue("mask $shape: the middle is changed", round.level(160, 200) < plain.level(160, 200) - 40)
                assertTrue("mask $shape: a corner is untouched", abs(round.level(60, 60) - plain.level(60, 60)) <= 1)
            }
        } finally {
            engine.close()
        }
    }
}
