package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class PresetDomainsTest {
    private fun layer(kind: String = "shape", attrs: Map<String, String> = emptyMap(), children: List<XmlNode> = emptyList()) =
        Layer(XmlNode(kind, mapOf("id" to "1", "startTime" to "0", "endTime" to "2000") + attrs, children))

    @Test fun shapePresetsPreserveTextTypographyAndMediaTimingAndGain() {
        val preset = layer(children = listOf(XmlNode("shadow")))
        val text = layer("text", mapOf("font" to "custom", "size" to "48", "align" to "center"))
        val applied = applyPreset(text, preset)
        for (attr in listOf("font", "size", "align")) assertEquals(attr, text.node[attr], applied.node[attr])
        val video = layer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip", "speed" to "2"), children = listOf(XmlNode("gain", mapOf("value" to "0.25"))))
        val out = applyPreset(video, preset)
        assertEquals(video.endTime, out.endTime)
        assertEquals(video.node["speed"], out.node["speed"])
        assertEquals(video.node.child("gain"), out.node.child("gain"))
    }
}
