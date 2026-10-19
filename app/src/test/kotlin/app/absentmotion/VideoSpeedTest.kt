package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.retimedVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSpeedTest {
    private fun video(start: Int, end: Int, vararg children: XmlNode) = Layer(XmlNode("shape", linkedMapOf("id" to "1",
        "startTime" to "$start", "endTime" to "$end", "fillType" to "media", "fillVideo" to "content://v", "inTime" to "500"), children.toList()))

    private fun span(layer: Layer) = VideoTiming(layer).let { it.mediaMs((layer.endTime - layer.startTime).toDouble()) - it.mediaMs(0.0) }

    @Test fun retimingARampKeepsItsShapeAndTheMediaItShows() {
        val ramp = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "500000000", "v" to "1")),
            XmlNode("kf", mapOf("t" to "2500000000", "v" to "3", "e" to "linear")),
        ))
        val layer = video(1000, 3000, ramp)
        val retimed = layer.retimedVideo(1000, 2000)
        val keys = retimed.node.child("speedMap")!!.keyframes
        assertEquals(listOf(500_000_000f, 2_500_000_000f), keys.map { it.t })
        assertEquals(listOf(null, "linear"), keys.map { it.e })
        assertEquals(3f, keys[1].v.toFloat() / keys[0].v.toFloat(), 1e-5f)
        assertEquals(2f, keys[0].v.toFloat(), 1e-5f)
        assertEquals(span(layer), span(retimed), 0.5)
        assertEquals("2000", retimed.node["endTime"])
    }

    @Test fun retimingAConstantSpeedStillFitsTheSameMedia() {
        val layer = video(0, 2000).let { it.copy(node = it.node.with("speed", "2")) }
        val retimed = layer.retimedVideo(500, 2000)
        assertEquals(span(layer), span(retimed), 0.5)
        assertEquals(null, retimed.node.child("speedMap"))
    }
}
