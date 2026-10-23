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

    @Test fun aSpeedRampPresetLandsOnTheTrimmedPartOfTheClip() {
        fun video(inTime: Int, children: List<XmlNode> = emptyList()) =
            shapeLayer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip", "inTime" to "$inTime", "outTime" to "60000"), children = children)
        val ramp = XmlNode("speedMap").withKeyframes(listOf(Keyframe(0f, "1"), Keyframe(1e9f, "3")))
        val preset = video(0, listOf(ramp))
        val span = VideoTiming(preset).let { it.mediaMs(2000.0) - it.mediaMs(0.0) }
        for (trim in listOf(0, 5000, 20000)) {
            val applied = applyPreset(video(trim), preset)
            val keys = applied.node.child("speedMap")!!.keyframes
            assertEquals(trim * 1e6, keys.first().t.toDouble(), 1e4)
            assertEquals((trim + 1000 * 2000 / span) * 1e6, keys.last().t.toDouble(), 1e5)
            assertEquals(1f, VideoTiming(applied).speed(0.0), 1e-3f)
        }
    }
}
