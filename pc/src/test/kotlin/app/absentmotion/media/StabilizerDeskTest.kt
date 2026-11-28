package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.editor.StabState
import app.absentmotion.editor.analyzeShake
import app.absentmotion.editor.stabState
import app.absentmotion.editor.withStabPathsFollowing
import app.absentmotion.editor.withStabSource
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.withEffect
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizerDeskTest {
    private val out = File("build/stabilizer").absoluteFile.also { it.mkdirs() }
    private val context = object : Context() {
        override fun getExternalFilesDir(type: String?): File = out
    }
    private val width = 384
    private val height = 216
    private val frames = 40
    private val fps = 20
    private val random = Random(5)
    private val shake = List(frames) { floatArrayOf(random.nextFloat() * 16 - 8, random.nextFloat() * 12 - 6, random.nextFloat() * 3 - 1.5f) }

    private fun texture(): BufferedImage {
        val image = BufferedImage(2 * width, 2 * height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = Random(11)
        g.color = Color(90, 120, 150)
        g.fillRect(0, 0, image.width, image.height)
        repeat(260) {
            g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            val x = r.nextInt(image.width)
            val y = r.nextInt(image.height)
            val s = 6 + r.nextInt(30)
            if (it % 2 == 0) g.fillRect(x, y, s, s * (1 + r.nextInt(2))) else g.fillOval(x, y, s, s)
        }
        g.dispose()
        return image
    }

    private fun video(): File {
        val big = texture()
        repeat(frames) { k ->
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val (dx, dy, degrees) = shake[k].toList()
            val m = AffineTransform()
            m.translate(width / 2.0 + dx, height / 2.0 + dy)
            m.rotate(Math.toRadians(degrees.toDouble()))
            m.translate(-big.width / 2.0, -big.height / 2.0)
            g.drawImage(big, m, null)
            g.dispose()
            ImageIO.write(image, "png", out.resolve("shake-$k.png"))
        }
        val file = out.resolve("shaky.mp4")
        val process = ProcessBuilder(app.absentmotion.Judge.ffmpeg, "-nostdin", "-v", "error", "-y", "-framerate", "$fps", "-i", out.resolve("shake-%d.png").path,
            "-c:v", "libx264", "-crf", "10", "-pix_fmt", "yuv420p", file.path).start()
        assertEquals(0, process.waitFor())
        return file
    }

    private fun scene(uri: String, level: Int?) = Scene.parse("""<scene title="s" width="$width" height="$height" totalTime="${frames * 1000 / fps - 1}" fps="$fps" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="$width" height="$height" duration="${frames * 1_000_000L / fps}" fps="$fps"/><shape id="1" startTime="0" endTime="${frames * 1000 / fps - 1}" fillType="media" fillVideo="$uri" inTime="0" outTime="${frames * 1000 / fps}" mediaFillMode="fill" s=".rect"><transform><location value="${width / 2},${height / 2},0"/></transform><property name="size" type="vec2" value="${width / 2},${height / 2}"/>${level?.let { """<effect id="absent.stabilizer" hidden="false"><property name="stabilization" type="float" value="$it"/></effect>""" } ?: ""}</shape></scene>""")

    private fun jitter(stills: List<ByteArray>): Double {
        var sum = 0.0
        var n = 0
        for (k in 1 until stills.size) for (y in height / 4 until 3 * height / 4 step 2) for (x in width / 4 until 3 * width / 4 step 2) {
            val at = (y * width + x) * 4
            for (c in 0..2) sum += abs((stills[k][at + c].toInt() and 255) - (stills[k - 1][at + c].toInt() and 255))
            n += 3
        }
        return sum / n
    }

    @Test fun theShakeIsMeasuredAndTheStabilizedVideoHoldsStill() = runBlocking<Unit> {
        val uri = Uri.fromFile(video()).toString()
        val plain = scene(uri, 3)
        assertEquals(StabState.None, plain.stabState(plain.layer(1)!!, plain.layer(1)!!.effects[0]))
        val source = checkNotNull(plain.analyzeShake(context, 1))
        assertEquals(frames, source.samples.size)
        val a0 = Math.toRadians(shake[0][2].toDouble()).toFloat()
        for (k in 1 until frames) {
            val turn = Math.toRadians(shake[k][2].toDouble()).toFloat() - a0
            val tx = shake[k][0] - (cos(turn) * shake[0][0] - sin(turn) * shake[0][1])
            val ty = shake[k][1] - (sin(turn) * shake[0][0] + cos(turn) * shake[0][1])
            val (x, y, a) = source.samples[k].second.toList()
            assertEquals("frame $k x", tx, x * width, 1f)
            assertEquals("frame $k y", ty, y * width, 1f)
            assertEquals("frame $k angle", Math.toDegrees(turn.toDouble()), Math.toDegrees(a.toDouble()), 0.25)
        }
        val steady = Scene.parse(plain.withStabSource(1, 0, source).toXml())
        assertEquals("the measured shake is kept with the project", StabState.Ready, steady.stabState(steady.layer(1)!!, steady.layer(1)!!.effects[0]))
        val off = steady.update(1) { l -> l.withEffect(0) { e -> e.withChild("property", e.effectProperty("stabilization")!!.with("value", "0")) } }.withStabPathsFollowing()
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            suspend fun stills(s: Scene) = engine.exportSession(s.toXml(), app.absentmotion.export.Smoothing(), fps) {
                (0 until frames).map { still(it * 1000 / fps + 10, width, height)!! }
            }!!
            val shaky = stills(scene(uri, null))
            val held = stills(steady)
            assertTrue("Off draws the clip as it is", stills(off).zip(shaky).all { (a, b) -> a.contentEquals(b) })
            val before = jitter(shaky)
            val after = jitter(held)
            assertTrue("the stabilized clip moves much less: $after vs $before", after < before * 0.1)
        } finally { engine.close() }
    }
}
