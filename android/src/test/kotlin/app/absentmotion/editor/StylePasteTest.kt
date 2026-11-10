package app.absentmotion.editor

import app.absentmotion.shapeLayer
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class StylePasteTest {
    @Test fun videoStyleIncludesTrimSpeedAndLoopAndStillFillsClearThem() {
        val source = shapeLayer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip", "inTime" to "100", "outTime" to "900", "speed" to "0.5", "loop" to "true"),
            children = listOf(XmlNode("gain", mapOf("value" to "0.4")), XmlNode("speedMap", mapOf("value" to "0.5"))))
        val target = Scene.create("p", 100, 100, 30, 0).add(shapeLayer())
        val pasted = target.withPastedStyle(1, 0, source, emptyList(), setOf(StylePart.ColorFill)).layer(1)!!
        for (attr in listOf("inTime", "outTime", "speed", "loop")) assertEquals(attr, source.node[attr], pasted.node[attr])
        assertEquals(source.node.child("speedMap"), pasted.node.child("speedMap"))
        assertEquals(source.node.child("gain"), pasted.node.child("gain"))
        val cleared = Scene(target.node.copy(children = target.node.children.filterNot { it.name in Scene.LAYER_TAGS } + pasted.node))
            .withPastedStyle(1, 0, shapeLayer(), emptyList(), setOf(StylePart.ColorFill)).layer(1)!!
        assertNull(cleared.node["fillVideo"])
        assertNull(cleared.node["inTime"])
        assertNull(cleared.node.child("speedMap"))
    }

    @Test fun defaultOpacityReplacesDimmedOpacity() {
        val target = shapeLayer(children = listOf(XmlNode("transform", children = listOf(XmlNode("opacity", mapOf("value" to "0.3")), XmlNode("location", mapOf("value" to "12,34,0"))))))
        val out = Scene.create("p", 100, 100, 30, 0).add(target).withPastedStyle(1, 0, shapeLayer(), emptyList(), setOf(StylePart.Blending)).layer(1)!!
        assertNull(out.node.child("transform")?.child("opacity"))
        assertEquals("12,34,0", out.node.child("transform")?.child("location")?.get("value"))
    }

    @Test fun fillPasteLeavesAudioAndGroupTimingAlone() {
        val audio = Layer(XmlNode("audio", mapOf("id" to "1", "startTime" to "0", "endTime" to "999", "src" to "file:///a.wav", "inTime" to "200", "speed" to "2"), listOf(XmlNode("gain", mapOf("value" to "0.3")))))
        val scene = Scene.create("p", 100, 100, 30, 0).add(audio)
        val out = scene.withPastedStyle(1, 0, shapeLayer(), emptyList(), setOf(StylePart.ColorFill)).layer(1)!!
        assertEquals("200", out.node["inTime"])
        assertEquals("2", out.node["speed"])
        assertNotNull(out.node.child("gain"))
    }
}
