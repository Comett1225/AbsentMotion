package app.absentmotion.editor

import androidx.camera.core.AspectRatio
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.ui.camera.cameraBlocked
import app.absentmotion.ui.camera.cameraRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureTest {
    private fun media(width: Int, height: Int, orientation: Int, type: String, duration: Long = -1) = MediaDeclaration(XmlNode("media", mapOf(
        "uri" to "content://media/external/1", "title" to "capture", "type" to type, "width" to "$width", "height" to "$height",
        "orientation" to "$orientation", "duration" to "$duration",
    )))

    @Test fun theSensorShapeThatCropsLeastIsChosen() {
        assertEquals(AspectRatio.RATIO_16_9, cameraRatio(9f / 16f, portrait = true))
        assertEquals(AspectRatio.RATIO_16_9, cameraRatio(16f / 9f, portrait = false))
        assertEquals(AspectRatio.RATIO_4_3, cameraRatio(1f, portrait = true))
        assertEquals(AspectRatio.RATIO_4_3, cameraRatio(4f / 5f, portrait = true))
        assertEquals(AspectRatio.RATIO_4_3, cameraRatio(9f / 16f, portrait = false))
    }

    @Test fun aTurnedVideoBecomesAPortraitProjectAtTheChosenResolution() {
        val scene = Scene.create("Clip", 1080, 1080, 30, 0).fromCapture(media(1920, 1080, 90, "video/mp4", 2_000_000), true, 720, 3)!!
        assertEquals(720, scene.width)
        assertEquals(1280, scene.height)
        val layer = scene.layers.single()
        assertEquals("content://media/external/1", layer.node["fillVideo"])
        assertEquals(0, layer.startTime)
        assertEquals(1999, layer.endTime)
        assertEquals("content://media/external/1", scene.media.single().uri)
    }

    @Test fun newClipsCoverTheirLengthWithoutAnExtraFrame() {
        for (fps in listOf(24, 25, 30, 60)) {
            val s = Scene.create("t", 100, 100, fps, 0)
            for (frame in listOf(0, 7, 15)) assertEquals(frame + 2 * fps - 1, s.lastShownFrame(s.endTimeFor(frame, 2000)))
        }
        val thirty = Scene.create("Clip", 1080, 1080, 30, 0)
        val clip = thirty.videoLayer(media(1920, 1080, 0, "video/mp4", 1_000_000), 15)!!
        assertEquals(500 to 1499, clip.startTime to clip.endTime)
        assertEquals(15, thirty.lastShownFrame(thirty.endTimeFor(15, 0)))
    }

    @Test fun aLargePhotoIsScaledToTheResolutionAndKeepsItsShape() {
        val scene = Scene.create("Photo", 1080, 1920, 30, 0).fromCapture(media(4000, 3000, 0, "image/jpeg"), false, 1080, 3)!!
        assertEquals(1440, scene.width)
        assertEquals(1080, scene.height)
        assertEquals("content://media/external/1", scene.layers.single().node["fillImage"])
    }

    @Test fun anEmptyCaptureMakesNoProject() {
        assertEquals(null, Scene.create("x", 100, 100, 30, 0).fromCapture(media(0, 0, 0, "image/jpeg"), false, 1080, 3))
    }

    @Test fun onlyARefusalAfterAnEarlierOneSendsToSettings() {
        assertFalse(cameraBlocked(granted = false, rationale = false, refusedBefore = false))
        assertFalse(cameraBlocked(granted = false, rationale = true, refusedBefore = false))
        assertFalse(cameraBlocked(granted = false, rationale = true, refusedBefore = true))
        assertTrue(cameraBlocked(granted = false, rationale = false, refusedBefore = true))
        assertFalse(cameraBlocked(granted = true, rationale = false, refusedBefore = true))
    }
}
