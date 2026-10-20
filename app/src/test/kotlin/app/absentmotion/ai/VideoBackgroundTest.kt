package app.absentmotion.ai

import app.absentmotion.media.Yuv
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoBackgroundTest {
    @Test fun theMatteKeepsTheAspectAndNeverUpscales() {
        assertEquals(960 to 540, matteSize(1920, 1080, 540))
        assertEquals(540 to 960, matteSize(1080, 1920, 540))
        assertEquals(640 to 360, matteSize(640, 360, 720))
        assertEquals(484 to 360, matteSize(1450, 1080, 360))
    }

    @Test fun onlyTheFramesTheClipCanShowAreProcessed() {
        val stamps = LongArray(100) { it * 40_000L }
        val used = matteStamps(stamps, 1_010, 2_000)
        assertEquals(1_000_000L, used.first())
        assertEquals(2_000_000L, used.last())
        assertEquals(26, used.size)
        assertEquals(100, matteStamps(stamps, 0, Int.MAX_VALUE).size)
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to "v", "duration" to "8000000")))
        val clip = Layer(XmlNode("shape", mapOf("fillType" to "media", "fillVideo" to "v", "inTime" to "1000")))
        assertEquals(7.0, clip.matteSeconds(media), 1e-9)
        assertEquals(2.5, clip.copy(node = clip.node.with("outTime", "3500")).matteSeconds(media), 1e-9)
    }

    @Test fun stablePixelsAreSmoothedAndMovingEdgesAreKept() {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val out = smoothMatte(bytes(200, 0, 255), bytes(240, 255, 0), bytes(200, 0, 255))
        assertEquals(220, out[0].toInt() and 0xFF)
        assertEquals(128, out[1].toInt() and 0xFF)
        assertEquals(128, out[2].toInt() and 0xFF)
        val moving = smoothMatte(bytes(0), bytes(255), bytes(255))
        assertEquals(255, moving[0].toInt() and 0xFF)
    }

    @Test fun theSmootherHoldsOneFrameAndKeepsEveryTimestamp() {
        val smoother = MatteSmoother()
        val frames = (0 until 5).map { it * 10L to ByteArray(4) { _ -> (it * 10).toByte() } }
        val out = frames.mapNotNull { (pts, m) -> smoother.push(pts, m) } + listOfNotNull(smoother.flush())
        assertEquals(frames.map { it.first }, out.map { it.first })
        assertArrayEquals(ByteArray(4) { 20 }, out[2].second)
        assertNull(smoother.flush())
    }

    @Test fun maskValuesUseLimitedRangeLuma() {
        assertEquals(16, matteLuma(0))
        assertEquals(235, matteLuma(255))
        assertEquals(126, matteLuma(128))
    }

    private fun yuv(width: Int, height: Int, y: Int, u: Int, v: Int): Yuv {
        val plane = { size: Int, value: Int -> ByteBuffer.wrap(ByteArray(size) { value.toByte() }) }
        return Yuv(plane(width * height, y), width, 1, plane(width * height / 4, u), plane(width * height / 4, v), width / 2, 1, 0, 0, width, height)
    }

    @Test fun framesConvertToRgbWithTheirRangeAndStandard() {
        val grey = yuv(8, 4, 126, 128, 128).argb(4, 2)
        assertTrue(grey.all { it == grey[0] })
        assertEquals(128.0, (grey[0] shr 8 and 0xFF).toDouble(), 1.0)
        val red = yuv(4, 4, 63, 102, 240).argb(2, 2, standard = 1)
        assertTrue((red[0] shr 16 and 0xFF) > 230 && (red[0] shr 8 and 0xFF) < 25 && (red[0] and 0xFF) < 25)
        val full = yuv(4, 4, 255, 128, 128).argb(1, 1, fullRange = true)
        assertEquals(0xFFFFFFFF.toInt(), full[0])
    }

    private fun video(effects: List<XmlNode> = emptyList()) = Layer(XmlNode("shape", linkedMapOf(
        "id" to "1", "label" to "Clip", "startTime" to "500", "endTime" to "2500", "fillType" to "media", "fillVideo" to "v.mp4",
        "mediaFillMode" to "fill", "s" to ".rect", "inTime" to "1000", "outTime" to "3000", "speed" to "1.5",
    ), effects)).withTransform("location", 300f, 400f, 0f).withTransform("rotation", 90f).withProp("size", "vec2", 100f, 100f)

    @Test fun theMaskSitsAboveTheVideoInsideAMaskedGroup() {
        val steps = XmlNode("effect", linkedMapOf("id" to "absent.timequant", "hidden" to "false"))
        val glow = XmlNode("effect", linkedMapOf("id" to "absent.glow", "hidden" to "false"))
        val background = XmlNode("shape", linkedMapOf("id" to "7", "startTime" to "0", "endTime" to "3000"))
        val scene = Scene(Scene.create("t", 1080, 1920, 30, 0).node.copy(children = listOf(
            XmlNode("media", mapOf("uri" to "v.mp4")), background, video(listOf(steps, glow)).node,
        )))
        val matte = MediaDeclaration(XmlNode("media", mapOf("uri" to "m.mp4", "type" to "video/mp4")))
        val out = scene.withVideoMatte(scene.layer(1)!!, matte, 200, 100, 2, 3)!!
        assertEquals(listOf(7L, 3L), out.layers.map { it.id })
        assertNotNull(out.media("m.mp4"))
        val group = out.layer(3)!!
        assertEquals(500, group.startTime)
        val inside = group.nested!!.layers
        assertEquals(listOf(1L, 2L), inside.map { it.id })
        val mask = inside[1]
        assertEquals("mask", mask.node["blending"])
        assertEquals("m.mp4", mask.node["fillVideo"])
        assertEquals("stretch", mask.node["mediaFillMode"])
        for (key in listOf("inTime", "outTime", "speed")) assertEquals(inside[0].node[key], mask.node[key])
        assertEquals(inside[0].startTime, mask.startTime)
        assertEquals(inside[0].endTime, mask.endTime)
        assertEquals(listOf(200f, 100f), mask.prop("size")!!.toList())
        assertEquals(90f, mask.transform("rotation", 0f)[0])
        assertEquals("0", mask.node.child("gain")!!["value"])
        assertEquals(listOf("absent.timequant", "absent.lumakey"), mask.effects.map { it["id"] })
        assertEquals(listOf("absent.timequant", "absent.glow"), inside[0].effects.map { it["id"] })
    }

    @Test fun animatedSizeMapsEveryKeyToTheFrameArea() {
        val sized = video().node.copy(children = video().node.children.map {
            if (it.name == "property" && it["name"] == "size") XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2"), listOf(
                XmlNode("kf", linkedMapOf("t" to "0.000000", "v" to "100,100")), XmlNode("kf", linkedMapOf("t" to "1.000000", "v" to "50,200")),
            )) else it
        })
        val scene = Scene(Scene.create("t", 1080, 1920, 30, 0).node.copy(children = listOf(sized)))
        val out = scene.withVideoMatte(scene.layer(1)!!, MediaDeclaration(XmlNode("media", mapOf("uri" to "m.mp4"))), 200, 100, 2, 3)!!
        val mask = out.layer(3)!!.nested!!.layer(2)!!
        assertEquals(listOf(200f, 100f), mask.copy(t = 0f).prop("size")!!.toList())
        assertEquals(listOf(400f, 200f), mask.copy(t = 1f).prop("size")!!.toList())
    }

    @Test fun layersWithoutAVideoAreRefused() {
        val image = Layer(XmlNode("shape", linkedMapOf("id" to "1", "fillType" to "media", "fillImage" to "p.png", "s" to ".rect")))
        val scene = Scene(Scene.create("t", 100, 100, 30, 0).node.copy(children = listOf(image.node)))
        assertNull(scene.withVideoMatte(image, MediaDeclaration(XmlNode("media", mapOf("uri" to "m.mp4"))), 10, 10, 2, 3))
    }
}
