package app.absentmotion.editor

import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class PresetDomainsTest {
    @Test fun shapePresetsPreserveTextTypographyAndMediaTimingAndGain() {
        val preset = shapeLayer(children = listOf(XmlNode("shadow")))
        val text = shapeLayer("text", mapOf("font" to "custom", "size" to "48", "align" to "center"))
        val applied = applyPreset(text, preset)
        for (attr in listOf("font", "size", "align")) assertEquals(attr, text.node[attr], applied.node[attr])
        val video = shapeLayer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip", "speed" to "2"), children = listOf(XmlNode("gain", mapOf("value" to "0.25"))))
        val out = applyPreset(video, preset)
        assertEquals(video.endTime, out.endTime)
        assertEquals(video.node["speed"], out.node["speed"])
        assertEquals(video.node.child("gain"), out.node.child("gain"))
    }

    @Test fun aPresetThatReplacesAnAnimationsPartsAlsoDropsItsRecord() {
        val preset = shapeLayer(children = listOf(XmlNode("shadow")))
        val base = sceneOf(shapeLayer(id = 1).node)
        val blurred = base.withAnimation(1, MotionSlot.In, "blur", 500).layer(1)!!
        assertNotNull(blurred.motion(MotionSlot.In))
        assertNull(applyPreset(blurred, preset).motion(MotionSlot.In))
        val slid = base.withAnimation(1, MotionSlot.Out, "slide-left", 500).layer(1)!!
        val kept = applyPreset(slid, preset)
        assertEquals(slid.motion(MotionSlot.Out), kept.motion(MotionSlot.Out))
        assertEquals(slid.node.child("transform")?.child("location"), kept.node.child("transform")?.child("location"))
    }

    @Test fun audioEffectsMakeAPresetAndReachOtherClipsWithSound() {
        val echo = XmlNode(AUDIO_FX_TAG, children = listOf(XmlNode("fx", mapOf("type" to "echo"))))
        val source = shapeLayer("audio", mapOf("src" to "content://a"), listOf(echo))
        assertTrue(canSavePreset(source))
        assertFalse(canSavePreset(shapeLayer("audio", mapOf("src" to "content://a"))))
        assertEquals(echo, applyPreset(shapeLayer("audio", mapOf("src" to "content://b")), source).node.child(AUDIO_FX_TAG))
        val video = shapeLayer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip"))
        assertEquals(echo, applyPreset(video, source).node.child(AUDIO_FX_TAG))
        assertNull(applyPreset(shapeLayer(), source).node.child(AUDIO_FX_TAG))
    }
}
