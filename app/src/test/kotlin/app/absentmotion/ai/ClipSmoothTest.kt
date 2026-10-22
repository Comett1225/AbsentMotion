package app.absentmotion.ai

import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSmoothTest {
    private val media = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "raw.mp4", "width" to "1920", "height" to "1080", "fps" to "30.00",
        "duration" to "4000000", "orientation" to "0")))

    private fun clip(speed: String, out: Int = 4000) = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0",
        "endTime" to "${(out / speed.toFloat()).toInt()}", "fillType" to "media", "fillVideo" to "raw.mp4", "inTime" to "0", "outTime" to "$out", "speed" to speed)))

    @Test fun slowClipsGetEnoughFramesForTheProjectAndNormalClipsOnlyBlur() {
        val flow = Smoothing(FrameInterpolation.Flow)
        assertEquals(120, smoothPlan(clip("0.25"), media, 30, flow)!!.fps)
        assertEquals(60, smoothPlan(clip("1"), media, 60, flow)!!.fps)
        assertEquals(240, smoothPlan(clip("0.01"), media, 30, flow)!!.fps)
        assertNull(smoothPlan(clip("1"), media, 30, flow))
        assertNull(smoothPlan(clip("0.25"), media, 30, Smoothing(blur = MotionBlur.Off)))
        val blurOnly = smoothPlan(clip("2"), media, 30, Smoothing(blur = MotionBlur.Medium))!!
        assertEquals(30, blurOnly.fps)
        assertEquals(2f, blurOnly.blurScale, 1e-3f)
        val slow = smoothPlan(clip("0.25"), media, 30, flow)!!
        assertEquals(1f, slow.blurScale, 1e-3f)
        assertEquals(1920 to 1080, slow.width to slow.height)
        val big = smoothPlan(clip("0.5"), MediaDeclaration(media.node.with("width", "3840").with("height", "2160")), 30, flow)!!
        assertEquals(1920 to 1080, big.width to big.height)
        val turned = smoothPlan(clip("0.5"), MediaDeclaration(media.node.with("orientation", "90")), 30, flow)!!
        assertEquals(1920 to 1080, turned.width to turned.height)
        assertEquals(4.0, slow.seconds, 1e-9)
        assertEquals(481, slow.frames)
    }

    @Test fun theChosenSettingsAreStoredWithTheMark() {
        for (s in listOf(Smoothing(), Smoothing(FrameInterpolation.Flow, SmoothLevel.High, MotionBlur.Low), Smoothing(FrameInterpolation.Blend, SmoothLevel.Low))) {
            assertEquals(s, smoothingOf(s.option))
        }
        assertNull(smoothingOf("Flow:Huge:Off"))
        assertNull(smoothingOf(null))
    }

    @Test fun restoringASmoothClipBringsBackTheOriginalAndItsMediaDetails() {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30"><media uri="raw.mp4" width="1920" height="1080" fps="30.00" duration="4000000"/>
            <shape id="1" startTime="0" endTime="3999" fillType="media" fillVideo="raw.mp4" s=".rect"/></scene>""")
        val original = scene.media("raw.mp4")!!
        val result = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "smooth.mp4", "fps" to "60.00")))
        val smoothed = scene.update(1) { it.copy(node = it.node.with("fillVideo", "smooth.mp4")).withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4", "Flow:Medium:Off", original) }
            .withMedia(result)
        assertNull(smoothed.media("raw.mp4"))
        val saved = Scene.parse(smoothed.node.toXml())
        val mark = saved.layer(1)!!.aiMarks.single()
        assertEquals(AiKind.Smooth, mark.kind)
        assertTrue(mark.restorable)
        assertEquals(original, mark.media)
        val again = saved.update(1) { it.copy(node = it.node.with("fillVideo", "second.mp4")).withAiMark(AiKind.Smooth, "raw.mp4", "second.mp4", "Blend:High:Off", null) }
        assertEquals(original, again.layer(1)!!.aiMarks.single().media)
        val restored = saved.withoutAiMark(1, AiKind.Smooth)
        assertEquals("raw.mp4", restored.layer(1)!!.node["fillVideo"])
        assertEquals(original, restored.media("raw.mp4"))
        assertNull(restored.media("smooth.mp4"))
        assertTrue(restored.layer(1)!!.aiMarks.isEmpty())
    }
}
