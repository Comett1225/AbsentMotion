package app.absentmotion.media

import app.absentmotion.TestData
import app.absentmotion.editor.Place
import app.absentmotion.editor.Sim
import app.absentmotion.editor.TrackApply
import app.absentmotion.editor.motionOf
import app.absentmotion.editor.trackFrames
import app.absentmotion.editor.trackNull
import app.absentmotion.editor.videoToScene
import app.absentmotion.editor.withTrack
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class MotionTrackerJniTest {
    private val library = File(TestData.project, "build/tracker-host/libtracker_host.so")

    @Before fun load() {
        assumeTrue("Build the host tracker with tools/tracker_check.sh", library.isFile)
        System.load(library.absolutePath)
    }

    private fun texture(x: Double, y: Double): Double =
        128 + 38 * sin(0.21 * x + 0.13 * y) + 33 * cos(0.17 * y - 0.11 * x) + 24 * sin(0.083 * x - 0.191 * y + 1.3) + 18 * cos(0.047 * x + 0.061 * y)

    private val center = Pt(640f, 360f)
    private val turn = Math.toRadians(0.8)
    private val shift = Pt(1.5f, -0.75f)

    private fun moved(p: Pt, k: Int): Pt {
        val a = turn * k
        val x = p.x - center.x
        val y = p.y - center.y
        return Pt((center.x + x * cos(a) - y * sin(a) + shift.x * k).toFloat(), (center.y + x * sin(a) + y * cos(a) + shift.y * k).toFloat())
    }

    private fun frame(k: Int): LumaFrame {
        val a = -turn * k
        val pixels = ByteArray(640 * 360) { i ->
            val mx = (i % 640 + 0.5) * 2 - 0.5 - shift.x * k - center.x
            val my = (i / 640 + 0.5) * 2 - 0.5 - shift.y * k - center.y
            texture(center.x + mx * cos(a) - my * sin(a), center.y + mx * sin(a) + my * cos(a)).toInt().coerceIn(0, 255).toByte()
        }
        return LumaFrame(640, 360, pixels, factor = 2)
    }

    @Test fun aMovingPatternIsTrackedThroughJniIntoNullKeyframes() = runBlocking {
        val frames = (0 until 24).toList()
        val pts = frames.map { it * 33_333L }
        val lumas = frames.associate { pts[it] to frame(it) }
        val start = listOf(Pt(420f, 300f), Pt(860f, 420f))
        val outcome = trackFrames(frames, pts, pts.toLongArray(), start, 1280, 720,
            read = { batch, deliver -> batch.forEach { deliver(it, lumas.getValue(it)) } }, tracker = { MotionTracker(it) })
        assertNull(outcome.lostAt)
        for (step in outcome.steps) start.zip(step.points).forEach { (s, found) ->
            val truth = moved(s, step.frame)
            assertEquals("x at ${step.frame}", truth.x, found.x, 0.3f)
            assertEquals("y at ${step.frame}", truth.y, found.y, 0.3f)
        }
        val video = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "2000", "fillType" to "media", "fillVideo" to "v.mp4",
            "mediaFillMode" to "fill", "s" to ".rect", "outTime" to "4000"))).withTransform("location", 540f, 960f, 0f).withProp("size", "vec2", 480f, 270f)
        val scene = Scene(Scene.create("t", 1080, 1920, 30, 0).node.copy(children = listOf(video.node)))
        val layer = scene.layer(1)!!
        val motion = scene.motionOf(layer, outcome.steps, 1280, 720, Sim())
        val anchor = scene.videoToScene(layer, 0, 1280, 720)!!.map(start[0])
        val out = scene.add(trackNull(5, layer, anchor, "Track 1"))
            .withTrack(TrackApply(5, Place(anchor.x, anchor.y, 0f, 0f, 1f, 1f, 0f), rotateScale = true), motion)
        for (k in listOf(0, 9, 23)) {
            val expected = scene.videoToScene(layer, k, 1280, 720)!!.map(moved(start[0], k))
            val at = out.layerAt(5, scene.frameStart(k))!!
            assertEquals(expected.x, at.location[0], 0.8f)
            assertEquals(expected.y, at.location[1], 0.8f)
            assertEquals(0.8f * k, at.transform("rotation", 0f)[0], 0.2f)
            assertEquals(1f, at.scale[0], 0.003f)
        }
    }
}
