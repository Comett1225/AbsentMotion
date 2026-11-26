package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.editor.FaceSource
import app.absentmotion.editor.FacePick
import app.absentmotion.editor.FaceState
import app.absentmotion.editor.faceState
import app.absentmotion.editor.followFace
import app.absentmotion.editor.framePts
import app.absentmotion.editor.withFaceSource
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackDeskTest {
    private val out = File("build/face-track").absoluteFile.also { it.mkdirs() }
    private val context = object : Context() {
        override fun getExternalFilesDir(type: String?): File = out
    }
    private val width = 384
    private val height = 216
    private val frames = 12
    private fun movingX(frame: Int) = 80f + frame * 10f

    private fun face(g: java.awt.Graphics2D, cx: Float, cy: Float, r: Float) {
        g.color = Color(60, 60, 140)
        g.fill(java.awt.geom.Rectangle2D.Float(cx - r * 1.3f, cy + r * 0.9f, r * 2.6f, height.toFloat()))
        g.color = Color(225, 180, 150)
        g.fill(Ellipse2D.Float(cx - r * 0.8f, cy - r, r * 1.6f, r * 2f))
        g.color = Color(60, 40, 30)
        g.fill(Arc2D.Float(cx - r * 0.85f, cy - r * 1.1f, r * 1.7f, r * 1.3f, 0f, 180f, Arc2D.CHORD))
        for (s in listOf(-1, 1)) {
            val ex = cx + s * r * 0.33f
            val ey = cy - r * 0.05f
            g.color = Color(250, 250, 250)
            g.fill(Ellipse2D.Float(ex - r * 0.17f, ey - r * 0.08f, r * 0.34f, r * 0.16f))
            g.color = Color(50, 35, 25)
            g.fill(Ellipse2D.Float(ex - r * 0.08f, ey - r * 0.08f, r * 0.16f, r * 0.16f))
            g.color = Color(60, 40, 30)
            g.stroke = BasicStroke(maxOf(2f, r * 0.07f))
            g.draw(java.awt.geom.Line2D.Float(ex - r * 0.2f, ey - r * 0.22f, ex + r * 0.2f, ey - r * 0.25f))
        }
        g.color = Color(200, 145, 120)
        g.fill(java.awt.Polygon(intArrayOf(cx.toInt(), (cx - r * 0.12f).toInt(), (cx + r * 0.12f).toInt()), intArrayOf(cy.toInt(), (cy + r * 0.3f).toInt(), (cy + r * 0.3f).toInt()), 3))
        g.color = Color(150, 60, 60)
        g.fill(Arc2D.Float(cx - r * 0.3f, cy + r * 0.35f, r * 0.6f, r * 0.3f, 180f, 180f, Arc2D.CHORD))
    }

    private fun video(): File {
        repeat(frames) { k ->
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(70, 110, 150)
            g.fillRect(0, 0, width, height)
            face(g, movingX(k), 100f, 34f)
            face(g, 310f, 100f, 30f)
            g.dispose()
            ImageIO.write(image, "png", out.resolve("face-$k.png"))
        }
        val file = out.resolve("faces.mp4")
        val process = ProcessBuilder(app.absentmotion.Judge.ffmpeg, "-nostdin", "-v", "error", "-y", "-framerate", "10", "-i", out.resolve("face-%d.png").path,
            "-vf", "gblur=sigma=1.5", "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p", file.path).start()
        assertEquals(0, process.waitFor())
        return file
    }

    private fun scene(uri: String) = Scene.parse("""<scene title="f" width="$width" height="$height" totalTime="1199" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="$width" height="$height" duration="1200000" fps="10"/><shape id="1" startTime="0" endTime="1199" fillType="media" fillVideo="$uri" inTime="0" outTime="1200" mediaFillMode="fill" s=".rect"><transform><location value="${width / 2},${height / 2},0"/></transform><property name="size" type="vec2" value="${width / 2},${height / 2}"/><effect id="absent.facetrack" hidden="false"><property name="zoom" type="float" value="1"/><property name="position" type="vec2" value="0.5,0.5"/><property name="smoothing" type="float" value="0"/></effect></shape></scene>""")

    @Test fun theVideoFollowsThePickedFace() = runBlocking {
        val file = video()
        val uri = Uri.fromFile(file).toString()
        val detector = FaceDetector(File("../shared/assets/${FaceDetector.MODEL}").readBytes())
        val (wanted, samples) = detector.use {
            FrameReader(context, uri).use { reader ->
                val first = reader.timestamps.first()
                var faces = emptyList<FaceSpot>()
                reader.read(listOf(first)) { _, yuv -> faces = detector.faces(yuv, 0, width, height) }
                assertEquals("faces seen in the first frame", 2, faces.size)
                val pick = faces.minBy { it.x }
                assertEquals(movingX(0), pick.x, 6f)
                val s = scene(uri)
                val wanted = s.framePts(s.layer(1)!!, (0 until frames).toList(), reader.timestamps)
                wanted to (pick to followFace(FacePick(first, pick), wanted, { batch, deliver -> reader.read(batch) { p, yuv -> deliver(p, detector.faces(yuv, 0, width, height)) } }))
            }
        }
        val (pick, found) = samples
        assertEquals(frames, found.size)
        found.forEachIndexed { k, (_, spot) -> assertEquals("frame $k", movingX(k), spot.x, 6f) }
        val tracked = scene(uri).withFaceSource(1, 0, FaceSource(uri, width, height, FacePick(wanted.first(), pick), wanted.first(), wanted.last(), found))
        assertEquals(FaceState.Ready, tracked.faceState(tracked.layer(1)!!, tracked.layer(1)!!.effects[0]))
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            fun skin(pixels: ByteArray, x: Int, y: Int): Boolean {
                val at = (y * width + x) * 4
                return abs((pixels[at].toInt() and 255) - 225) < 30 && abs((pixels[at + 1].toInt() and 255) - 180) < 30
            }
            for (frame in listOf(0, 4)) {
                val ms = frame * 100 + 50
                val plain = engine.exportSession(scene(uri).toXml(), app.absentmotion.export.Smoothing(), 10) { still(ms, width, height) }!!
                val moved = engine.exportSession(tracked.toXml(), app.absentmotion.export.Smoothing(), 10) { still(ms, width, height) }!!
                assertTrue("frame $frame: the face is off centre without the effect", !skin(plain, width / 2 + 17, height / 2))
                assertTrue("frame $frame: the face sits in the centre with the effect", skin(moved, width / 2 + 17, height / 2))
            }
        } finally { engine.close() }
    }
}
