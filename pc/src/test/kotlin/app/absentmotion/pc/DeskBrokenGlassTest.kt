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

class DeskBrokenGlassTest {
    private val out = File("build/broken-glass").canonicalFile
    private val w = 480
    private val h = 320

    private fun picture(): String {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val stripe = if ((x / 24) % 2 == 0) 0 else 30
            image.setRGB(x, y, Color(40 + 140 * x / w, 60 + stripe, 60 + 120 * y / h).rgb)
        }
        val file = File(out, "picture.png")
        ImageIO.write(image, "png", file)
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun scene(uri: String, effect: String) = Scene.parse("""<scene title="g" width="$w" height="$h" fps="30" totalTime="999" bgcolor="#ff000000">
<media uri="$uri" filename="p.png" title="p.png" type="image/png" width="$w" height="$h"/>
<shape id="1" startTime="0" endTime="999" fillType="media" fillImage="$uri" mediaFillMode="fill" s=".rect"><transform><location value="${w / 2},${h / 2},0"/></transform>
<property name="size" type="vec2" value="${w / 2},${h / 2}"/>$effect</shape></scene>""")

    private fun broken(debris: Int?) = """<effect id="absent.brokenglass">${debris?.let { """<property name="debris" type="float" value="$it"/>""" } ?: ""}</effect>"""

    private fun ByteArray.channel(i: Int, c: Int) = this[i * 4 + c].toInt() and 255

    private fun ByteArray.cracks(): Int = (0 until w * h).count { i -> (0..2).all { channel(i, it) > 205 } }

    private fun ByteArray.mean(c: Int): Double = (0 until w * h).sumOf { channel(it, c) }.toDouble() / (w * h)

    @Test fun thePictureBreaksIntoShiftedShardsWithCrackLines() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.data", out.resolve("data").path)
        val uri = picture()
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            assertEquals("ok", engine.effects.first { it.definitions.isNotEmpty() }.find("absent.brokenglass")?.status)
            fun render(effect: String): ByteArray = runBlocking {
                val s = scene(uri, effect)
                checkNotNull(engine.exportSession(s.toXml()) { still(s.frameStart(5), w, h) })
            }
            val plain = render("")
            val shattered = render(broken(null))
            assertEquals("the default is Debris 15", shattered.toList(), render(broken(15)).toList())
            assertEquals("the picture has no white of its own", 0, plain.cracks())
            val lines = shattered.cracks()
            assertTrue("crack lines are drawn: $lines", lines > w * h / 100 && lines < w * h / 4)
            val moved = (0 until w * h).count { i -> (0..2).any { abs(shattered.channel(i, it) - plain.channel(i, it)) > 12 } }
            assertTrue("shards are shifted: $moved", moved > w * h / 5)
            for (c in 0..2) assertTrue("the shards keep the picture's colors (channel $c)", abs(shattered.mean(c) - plain.mean(c)) < 18)
            val few = render(broken(1))
            val many = render(broken(30))
            assertTrue("more Debris breaks it into more pieces: ${few.cracks()} < $lines < ${many.cracks()}", few.cracks() < lines && lines < many.cracks())
            assertTrue("the frame stays filled", (0 until w * h).none { i -> (0..2).all { shattered.channel(i, it) < 8 } })
        } finally {
            engine.close()
        }
    }
}
