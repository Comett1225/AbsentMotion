package app.absentmotion.editor

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameScreenshotTest {
    private val media = MediaDeclaration(XmlNode("media", mapOf("uri" to "file:///v.mp4", "width" to "1920", "height" to "1080", "orientation" to "90", "duration" to "4000000", "type" to "video/mp4")))

    private fun clip() = shapeLayer(
        attrs = mapOf("fillType" to "media", "fillVideo" to "file:///v.mp4", "inTime" to "500", "speed" to "2", "parent" to "7", "blending" to "screen", "mediaFillMode" to "fit"),
        children = listOf(
            XmlNode("transform", children = listOf(XmlNode("scale", mapOf("value" to "3,3")))),
            XmlNode("effect", mapOf("id" to "absent.crop")),
            XmlNode("border"),
            XmlNode("speedMap", mapOf("value" to "2")),
        ),
        id = 5, start = 1000, end = 3000,
    )

    @Test fun rawFrameKeepsTimingAndDropsEdits() {
        val raw = sceneOf(clip().node).rawFrame(clip(), media) { _, _ -> true }!!
        assertEquals(1080, raw.width)
        assertEquals(1920, raw.height)
        val layer = raw.scene.layers.single()
        assertEquals("500", layer.node["inTime"])
        assertEquals("2", layer.node["speed"])
        assertEquals(1000, layer.startTime)
        assertEquals("fill", layer.node["mediaFillMode"])
        assertNull(layer.node["parent"])
        assertNull(layer.node["blending"])
        assertTrue(layer.node.children.none { it.name == "effect" })
        assertTrue(layer.node.children.none { it.name == "border" })
        assertEquals("2", layer.node.child("speedMap")!!["value"])
        assertEquals(90f, layer.node.child("transform")!!.child("rotation")!!["value"]!!.toFloat(), 0f)
        assertEquals(listOf("file:///v.mp4"), raw.scene.media.map { it.uri })
    }

    @Test fun rawFrameHalvesUntilItFits() {
        val raw = sceneOf(clip().node).rawFrame(clip(), media) { w, h -> w * h <= 300_000 }!!
        assertEquals(270 to 480, raw.width to raw.height)
    }
}
