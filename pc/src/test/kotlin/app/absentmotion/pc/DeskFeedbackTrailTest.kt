package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.desk.DeskMedia
import app.absentmotion.engine.EngineDebug
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.pow

class DeskFeedbackTrailTest {
    private val repo = File("..").canonicalFile
    private val out = File("build/feedback-trail").canonicalFile
    private val sharp = File("build/feedback-sharp").canonicalFile
    private val video = repo.resolve("testdata/media/test-video.mp4").toURI().toString().replaceFirst("file:/", "file:///")

    private fun scene(trails: Int) = Scene.parse("""<scene title="trail" width="640" height="360" fps="30" totalTime="1999" bgcolor="#ff000000">
<media uri="$video" filename="test-video.mp4" title="test-video.mp4" type="video/mp4" size="119175" orientation="0" duration="5000000" fps="30.00" infoUpdated="1760000000000" width="1280" height="720" />
<shape id="1" startTime="0" endTime="1999" fillType="media" fillVideo="$video" inTime="1000" outTime="5000" mediaFillMode="fill" s=".rect">
<transform><location value="320,180,0"/></transform><property name="size" type="vec2" value="320,180"/>
<effect id="absent.videofeedback"><property name="copies" type="float" value="6"/><property name="zoom" type="float" value="0.8"/>
<property name="fade" type="float" value="1"/><property name="trails" type="float" value="$trails"/></effect></shape></scene>""")

    private fun gap(a: ByteArray, b: ByteArray): Double = a.indices.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)).toLong() }.toDouble() / a.size

    private fun save(pixels: ByteArray, name: File, width: Int = 640, height: Int = 360, crop: IntArray = intArrayOf(0, 0, width, height)) {
        val image = BufferedImage(crop[2], crop[3], BufferedImage.TYPE_INT_RGB)
        for (y in 0 until crop[3]) for (x in 0 until crop[2]) {
            val i = (y + crop[1]) * width + x + crop[0]
            image.setRGB(x, y, (pixels[i * 4].toInt() and 255 shl 16) or (pixels[i * 4 + 1].toInt() and 255 shl 8) or (pixels[i * 4 + 2].toInt() and 255))
        }
        ImageIO.write(image, "png", name)
    }

    private fun ring(copy: Int, x: Int, y: Int): Boolean {
        fun inside(scale: Double, margin: Int) = abs(x - W / 2) < W / 2 * scale + margin && abs(y - H / 2) < H / 2 * scale + margin
        return inside(ZOOM.pow(copy), -4) && !inside(ZOOM.pow(copy + 1), 4)
    }

    private fun off(a: ByteArray, b: ByteArray, copy: Int): Double {
        var n = 0
        var sum = 0L
        for (y in 0 until H) for (x in 0 until W) if (ring(copy, x, y)) for (c in 0..2) {
            n++
            sum += abs((a[(y * W + x) * 4 + c].toInt() and 255) - (b[(y * W + x) * 4 + c].toInt() and 255))
        }
        return sum.toDouble() / n
    }

    private fun sharpness(pixels: ByteArray, copy: Int): Double {
        fun luma(x: Int, y: Int) = (y * W + x).let { (pixels[it * 4].toInt() and 255) * 0.3 + (pixels[it * 4 + 1].toInt() and 255) * 0.6 + (pixels[it * 4 + 2].toInt() and 255) * 0.1 }
        var n = 0
        var sum = 0.0
        var squares = 0.0
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            if (!ring(copy, x, y)) continue
            val edge = 4 * luma(x, y) - luma(x - 1, y) - luma(x + 1, y) - luma(x, y - 1) - luma(x, y + 1)
            n++
            sum += edge
            squares += edge * edge
        }
        return squares / n - (sum / n) * (sum / n)
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
            save(trailed, out.resolve("trails-on.png"))
            save(same, out.resolve("trails-off.png"))
            save(jumped, out.resolve("trails-on-jumped.png"))
            out.resolve("report.txt").writeText("31 frames: trails on $trailMs ms, off $plainMs ms; on vs off ${gap(trailed, same)}, in order vs jumped ${gap(trailed, jumped)}\n")
            assertTrue("copies show the current frame", gap(trailed, same) > 1.0)
            assertTrue("a jump decodes the same history", gap(trailed, jumped) < 0.5)
        } finally {
            engine.close()
        }
    }

    @Test fun nearCopiesStaySharpOnAPhoneBudget() = runBlocking {
        val clip = sharp.resolve("sharp-1080p.mp4")
        sharp.deleteRecursively()
        sharp.mkdirs()
        runCatching {
            ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=${W}x$H:r=30", "-vf", "loop=loop=35:size=1:start=0", "-frames:v", "36", "-c:v", "libx264", "-crf", "10",
                "-pix_fmt", "yuv420p", "-y", clip.path).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        }
        assumeTrue("no encoder to make the test clip", clip.length() > 0)
        val uri = clip.toURI().toString().replaceFirst("file:/", "file:///")
        fun big(trails: Int) = Scene.parse("""<scene title="sharp" width="$W" height="$H" fps="30" totalTime="1199" bgcolor="#ff000000">
<media uri="$uri" filename="sharp-1080p.mp4" title="sharp-1080p.mp4" type="video/mp4" size="${clip.length()}" orientation="0" duration="1200000" fps="30.00" infoUpdated="1760000000000" width="$W" height="$H" />
<shape id="1" startTime="0" endTime="1199" fillType="media" fillVideo="$uri" inTime="0" outTime="1200" mediaFillMode="fill" s=".rect">
<transform><location value="${W / 2},${H / 2},0"/></transform><property name="size" type="vec2" value="${W / 2},${H / 2}"/>
<effect id="absent.videofeedback"><property name="copies" type="float" value="24"/><property name="zoom" type="float" value="$ZOOM"/>
<property name="fade" type="float" value="1"/><property name="trails" type="float" value="$trails"/></effect></shape></scene>""")
        System.setProperty("am.data", sharp.resolve("data-sharp").path)
        EngineDebug.trailMb = 24
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            val last = big(1).frameStart(30)
            suspend fun inOrder(trails: Int): ByteArray = engine.exportSession(big(trails).toXml()) {
                var pixels: ByteArray? = null
                for (frame in 0..30) pixels = checkNotNull(still(big(trails).frameStart(frame), W, H))
                pixels!!
            }
            val started = System.nanoTime()
            val trailed = inOrder(1)
            val trailMs = (System.nanoTime() - started) / 1_000_000
            val plain = inOrder(0)
            val jumped = engine.exportSession(big(1).toXml()) { checkNotNull(still(last, W, H)) }
            val crop = intArrayOf(0, 0, W / 2, H / 2)
            save(trailed, sharp.resolve("near-copies-trails.png"), W, H, crop)
            save(plain, sharp.resolve("near-copies-plain.png"), W, H, crop)
            save(jumped, sharp.resolve("near-copies-jumped.png"), W, H, crop)
            val kept = (1..3).map { sharpness(trailed, it) / sharpness(plain, it) }
            val keptJumped = (1..3).map { sharpness(jumped, it) / sharpness(plain, it) }
            sharp.resolve("sharpness.txt").writeText("1080p, 24 copies, 24 MB a layer: sharpness of copies 1 to 3 against the plain frame, in order $kept, jumped $keptJumped; mean difference from the plain frame ${(1..3).map { off(trailed, plain, it) }}, whole frame ${gap(trailed, plain)}; 31 frames in $trailMs ms\n")
            assertTrue("the first copy is sharp: $kept", kept[0] > 0.4)
            assertTrue("the first copy is sharp after a jump: $keptJumped", keptJumped[0] > 0.4)
        } finally {
            EngineDebug.trailMb = 0
            engine.close()
        }
    }

    private companion object {
        const val W = 1920
        const val H = 1080
        const val ZOOM = 0.85
    }
}
