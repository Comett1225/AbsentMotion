package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class DeskPageCurlTest {
    private val out = File("build/page-curl").canonicalFile

    private fun picture(name: String, round: Boolean): String {
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(220, 60, 60)
        if (round) g.fillOval(30, 30, 240, 240) else g.fillRect(0, 0, 300, 300)
        g.dispose()
        val file = File(out, "$name.png")
        ImageIO.write(image, "png", file)
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun scene(uri: String, follow: Boolean?) = Scene.parse("""<scene title="c" width="400" height="400" fps="30" totalTime="999" bgcolor="#ff202838">
<media uri="$uri" filename="p.png" title="p.png" type="image/png" width="300" height="300"/>
<shape id="1" startTime="0" endTime="999" fillType="media" fillImage="$uri" mediaFillMode="fit" s=".rect"><transform><location value="200,200,0"/></transform>
<property name="size" type="vec2" value="300,300"/><effect id="absent.pagecurl"><property name="progress" type="float" value="0.25"/>
${follow?.let { """<property name="follow" type="bool" value="$it"/>""" } ?: ""}</effect></shape></scene>""")

    private fun gap(a: ByteArray, b: ByteArray): Double = a.indices.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)).toLong() }.toDouble() / a.size

    @Test fun followShapeCurlsFromTheVisibleEdgeAndChangesNothingForAFullLayer() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.data", out.resolve("data").path)
        val round = picture("round", true)
        val square = picture("square", false)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            fun render(uri: String, follow: Boolean?): ByteArray = runBlocking {
                val s = scene(uri, follow)
                checkNotNull(engine.exportSession(s.toXml()) { still(s.frameStart(5), 400, 400) })
            }
            val roundOff = render(round, false)
            assertTrue("an older project draws like Follow Shape off", gap(render(round, null), roundOff) < 0.01)
            assertTrue("Follow Shape changes the curl of a round picture: ${gap(render(round, true), roundOff)}", gap(render(round, true), roundOff) > 1.0)
            assertTrue("Follow Shape changes nothing when the picture fills the layer: ${gap(render(square, true), render(square, false))}",
                gap(render(square, true), render(square, false)) < 0.5)
        } finally {
            engine.close()
        }
    }
}
