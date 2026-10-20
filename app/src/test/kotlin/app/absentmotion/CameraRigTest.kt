package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cameraFov
import app.absentmotion.model.cameraFovOf
import app.absentmotion.model.cameraFrame
import app.absentmotion.model.cameraGesture
import app.absentmotion.model.cameraZoomOf
import app.absentmotion.model.defaultCameraId
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withCameraFrameScaled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
