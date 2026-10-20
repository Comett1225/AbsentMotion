package app.absentmotion

import app.absentmotion.model.CameraFov
import app.absentmotion.model.CameraMove
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cameraFov
import app.absentmotion.model.cameraFovOf
import app.absentmotion.model.cameraFrame
import app.absentmotion.model.cameraGesture
import app.absentmotion.model.cameraMoveSpan
import app.absentmotion.model.cameraZoomOf
import app.absentmotion.model.defaultCameraId
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.withCameraFrameScaled
import app.absentmotion.model.withCameraMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class CameraRigTest {
    private val distance = 623.5383f

    private fun camera(id: Long, start: Int = 0, end: Int = 4000, hidden: Boolean = false) = XmlNode(
        "camera",
        linkedMapOf("id" to "$id", "label" to "Camera $id", "startTime" to "$start", "endTime" to "$end").apply { if (hidden) put("hidden", "true") },
        listOf(
            XmlNode("transform", children = listOf(XmlNode("location", linkedMapOf("value" to formatFloats(floatArrayOf(360f, 360f, -distance)))))),
            XmlNode("fov", linkedMapOf("value" to formatFloats(floatArrayOf(60f)))),
        ),
    )

    private fun shape(id: Long) = XmlNode("shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "4000", "s" to ".circle"))

    private fun scene(vararg layers: XmlNode) =
        Scene(XmlNode("scene", linkedMapOf("width" to "720", "height" to "720", "fps" to "30", "totalTime" to "4000"), layers.toList()))

    private fun Scene.cam(id: Long): Layer = layer(id)!!

    private fun Layer.keys(ref: PropRef) = element(ref)?.keyframes.orEmpty()

    private fun values(text: String) = text.split(',').map { it.toFloat() }

    @Test
    fun zoomAndAngleAreTheSameSetting() {
        assertEquals(623.54f, cameraZoomOf(60f, 720), 0.01f)
        assertEquals(60f, cameraFovOf(cameraZoomOf(60f, 720), 720), 0.001f)
        assertEquals(720f, Layer(camera(1)).cameraFrame(720, 720).first, 0.1f)
    }

    @Test
    fun pinchScalesTheFrameAndKeepsThePlace() {
        val wider = Layer(camera(1)).cameraGesture(0f, 0f, 2f)
        assertEquals(1440f, wider.cameraFrame(720, 720).first, 0.5f)
        assertEquals(-distance, wider.location[2], 0.001f)
        assertEquals(360f, wider.location[0], 0.001f)
        val moved = Layer(camera(1)).cameraGesture(10f, -20f, 1f)
        assertEquals(370f, moved.location[0], 0.001f)
        assertEquals(340f, moved.location[1], 0.001f)
        assertEquals(60f, moved.cameraFov, 0.001f)
        assertEquals(179.9f, Layer(camera(1)).withCameraFrameScaled(1e6f).cameraFov, 0.001f)
    }

    @Test
    fun theLowestVisibleCameraIsTheDefault() {
        assertEquals(2L, scene(shape(1), camera(2), camera(3)).defaultCameraId)
        assertEquals(3L, scene(camera(2, hidden = true), camera(3)).defaultCameraId)
        assertNull(scene(shape(1)).defaultCameraId)
    }

    @Test
    fun pushInWritesTwoEasedKeysForwardOnly() {
        val s = scene(shape(1), camera(2)).withCameraMove(2, CameraMove.PushIn, 1000, 2000)
        val keys = s.cam(2).keys(PropRef.Location)
        assertEquals(2, keys.size)
        assertEquals(0.25f, keys[0].t, 1e-4f)
        assertEquals(0.75f, keys[1].t, 1e-4f)
        assertEquals(-distance, values(keys[0].v)[2], 0.01f)
        assertEquals(-distance * 0.65f, values(keys[1].v)[2], 0.01f)
        assertNotNull(keys[1].e)
        assertTrue(s.cam(2).keys(PropRef.Rotation).isEmpty())
        assertTrue(s.cam(2).keys(CameraFov).isEmpty())
    }

    @Test
    fun dollyZoomKeepsTheFrameAtTheSubjectTheSameSize() {
        val c = scene(camera(2)).withCameraMove(2, CameraMove.DollyZoom, 0, 4000).cam(2)
        val z = c.keys(PropRef.Location).map { values(it.v)[2] }
        val fov = c.keys(CameraFov).map { it.v.toFloat() }
        assertEquals(9, z.size)
        assertEquals(z.size, fov.size)
        val start = -z[0] * tan(Math.toRadians(fov[0] / 2.0)).toFloat()
        for (i in z.indices) assertEquals(start, -z[i] * tan(Math.toRadians(fov[i] / 2.0)).toFloat(), 0.5f)
        assertEquals(-distance / 2, z.last(), 0.01f)
    }

    @Test
    fun orbitAndHandheldComeBackToWhereTheyStarted() {
        for (move in listOf(CameraMove.Orbit, CameraMove.Handheld)) {
            val keys = scene(camera(2)).withCameraMove(2, move, 0, 2000).cam(2).keys(PropRef.Location)
            assertTrue(keys.size > 4)
            assertEquals(keys.first().v, keys.last().v)
        }
        val shaky = scene(camera(2)).withCameraMove(2, CameraMove.Handheld, 0, 2000)
        assertEquals(shaky, scene(camera(2)).withCameraMove(2, CameraMove.Handheld, 0, 2000))
        assertTrue(shaky.cam(2).keys(PropRef.Rotation).isNotEmpty())
    }

    @Test
    fun anotherMoveReplacesTheLastOne() {
        val first = scene(camera(2)).withCameraMove(2, CameraMove.PushIn, 0, 3000)
        val second = first.withCameraMove(2, CameraMove.Pan, 0, 1000, clearToMs = 3000)
        val keys = second.cam(2).keys(PropRef.Location)
        assertEquals(2, keys.size)
        assertEquals(0.25f, keys.last().t, 1e-4f)
        assertEquals(-distance, values(keys.last().v)[2], 0.01f)
        assertEquals(720f, values(keys.last().v)[0], 0.5f)
    }

    @Test
    fun movesStayInsideTheCameraClip() {
        val s = scene(camera(2, start = 1000, end = 2000))
        assertEquals(1000 to 2000, s.cameraMoveSpan(2, 0, 5000))
        assertEquals(1500 to 2000, s.cameraMoveSpan(2, 1500, 3000))
        assertNull(s.cameraMoveSpan(9, 0, 1000))
    }
}
