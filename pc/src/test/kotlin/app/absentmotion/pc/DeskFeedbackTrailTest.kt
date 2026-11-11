package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.abs

class DeskFeedbackTrailTest {
    private val repo = File("..").canonicalFile
    private val out = File("build/feedback-trail").canonicalFile
    private val video = repo.resolve("testdata/media/test-video.mp4").toURI().toString().replaceFirst("file:/", "file:///")

    private fun scene(trails: Int) = Scene.parse("""<scene title="trail" width="640" height="360" fps="30" totalTime="1999" bgcolor="#ff000000">
<media uri="$video" filename="test-video.mp4" title="test-video.mp4" type="video/mp4" size="119175" orientation="0" duration="5000000" fps="30.00" infoUpdated="1760000000000" width="1280" height="720" />
<shape id="1" startTime="0" endTime="1999" fillType="media" fillVideo="$video" inTime="1000" outTime="5000" mediaFillMode="fill" s=".rect">
<transform><location value="320,180,0"/></transform><property name="size" type="vec2" value="320,180"/>
<effect id="absent.videofeedback"><property name="copies" type="float" value="6"/><property name="zoom" type="float" value="0.8"/>
<property name="fade" type="float" value="1"/><property name="trails" type="float" value="$trails"/></effect></shape></scene>""")

    private fun gap(a: ByteArray, b: ByteArray): Double = a.indices.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)).toLong() }.toDouble() / a.size

    private fun save(pixels: ByteArray, name: String) {
        val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until 640 * 360) image.setRGB(i % 640, i / 640, (pixels[i * 4].toInt() and 255 shl 16) or (pixels[i * 4 + 1].toInt() and 255 shl 8) or (pixels[i * 4 + 2].toInt() and 255))
        ImageIO.write(image, "png", out.resolve(name))
    }

    @Test fun copiesOfAVideoLayerShowEarlierFrames() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.data", out.resolve("data").path)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            val last = scene(1).frameStart(30)
            suspend fun inOrder(trails: Int): ByteArray = engine.exportSession(scene(trails).toXml()) {
                var pixels: ByteArray? = null
                for (frame in 0..30) pixels = checkNotNull(still(scene(trails).frameStart(frame), 640, 360))
                pixels!!
            }
            val started = System.nanoTime()
            val trailed = inOrder(1)
            val trailMs = (System.nanoTime() - started) / 1_000_000
            val plainStarted = System.nanoTime()
            val same = inOrder(0)
            val plainMs = (System.nanoTime() - plainStarted) / 1_000_000
            val jumped = engine.exportSession(scene(1).toXml()) { checkNotNull(still(last, 640, 360)) }
            save(trailed, "trails-on.png")
            save(same, "trails-off.png")
            save(jumped, "trails-on-jumped.png")
            out.resolve("report.txt").writeText("31 frames: trails on $trailMs ms, off $plainMs ms; on vs off ${gap(trailed, same)}, in order vs jumped ${gap(trailed, jumped)}\n")
            assertTrue("copies show the current frame", gap(trailed, same) > 1.0)
            assertTrue("a jump decodes the same history", gap(trailed, jumped) < 0.5)
        } finally {
            engine.close()
        }
    }
}
