package app.absentmotion.ui.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.videoTimeUs
import app.absentmotion.ui.editor.panels.withMediaFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFillReplaceTest {
    private val trimmed = Layer(XmlNode("text", linkedMapOf(
        "id" to "1", "startTime" to "0", "endTime" to "3000", "fillType" to "media", "fillVideo" to "content://old",
        "inTime" to "5000", "outTime" to "8000",
    )))

    private fun video(uri: String, ms: Long) = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "type" to "video/mp4", "duration" to "${ms * 1000}")))

    @Test fun aShorterReplacementStartsInsideTheNewClip() {
        val replaced = trimmed.withMediaFill(video("content://short", 1000))
        val fill = replaced.mediaFill!!
        assertEquals(0, fill.inTime)
        assertEquals(1000, fill.outTime)
        val scene = Scene(Scene.create("t", 100, 100, 30, 0).node.copy(children = listOf(replaced.node)))
        for (ms in listOf(0, 500, 2999)) assertTrue(replaced.videoTimeUs(ms, scene) in 0..1_000_000L)
    }

    @Test fun aLongerReplacementKeepsTheTrimStart() {
        val fill = trimmed.withMediaFill(video("content://long", 20_000)).mediaFill!!
        assertEquals(5000, fill.inTime)
        assertEquals(20_000, fill.outTime)
    }

    @Test fun anImageReplacementDropsTheVideoTrim() {
        val image = MediaDeclaration(XmlNode("media", mapOf("uri" to "content://pic", "type" to "image/png")))
        val replaced = trimmed.withMediaFill(image)
        assertEquals(null, replaced.node["inTime"])
        assertEquals(null, replaced.node["outTime"])
        assertEquals("content://pic", replaced.node["fillImage"])
    }
}
