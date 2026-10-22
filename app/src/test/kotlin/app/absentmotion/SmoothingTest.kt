package app.absentmotion

import app.absentmotion.export.ExportSettings
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.export.frameTimeMs
import app.absentmotion.export.settingsFromJson
import app.absentmotion.export.settingsToJson
import app.absentmotion.media.smoothFrame
import app.absentmotion.model.FramePair
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exportFrame
import app.absentmotion.model.exportFrameMs
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {
    private val thirty = LongArray(400) { it * 1_000_000L / 30 }

    private fun clip(speed: String = "1") = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "9999", "fillType" to "media",
        "fillVideo" to "content://clip", "inTime" to "0", "outTime" to "10000", "s" to ".rect", "speed" to speed)))

    private fun Scene.at(frame: Int, fps: Int) = exportFrameMs(frameTimeMs(this, fps, frame), fps)

    private fun frame(smoothing: Smoothing, k: Int, fps: Int, project: Int = 30, shown: Long = -1L) =
        Scene.create("t", 1920, 1080, project, 0).let { scene -> clip().smoothFrame(scene.at(k, fps), scene, thirty, fps, 1.0, smoothing) { shown } }

    @Test fun savedSettingsFromBeforeLevelsBecomeMediumWithoutMotionBlur() {
        for (method in FrameInterpolation.entries) {
            val old = JSONObject().put("shortSide", 720).put("fps", 60).put("interpolation", method.name)
            val loaded = settingsFromJson(old, ExportSettings(1080, 30))
            assertEquals(method, loaded.interpolation)
            assertEquals(SmoothLevel.Medium, loaded.interpolationLevel)
            assertEquals(MotionBlur.Off, loaded.motionBlur)
        }
        val chosen = ExportSettings(720, 60, interpolation = FrameInterpolation.Flow, interpolationLevel = SmoothLevel.High, motionBlur = MotionBlur.Low)
        assertEquals(chosen, settingsFromJson(JSONObject(settingsToJson(chosen).toString()), ExportSettings(1080, 30)))
        val broken = JSONObject().put("interpolation", "Blend").put("interpolationLevel", "Extreme").put("motionBlur", 7)
        assertEquals(Smoothing(FrameInterpolation.Blend), settingsFromJson(broken, ExportSettings(1080, 30)).smoothing)
    }

    @Test fun onlyOpticalFlowLowAndHighChangeTheFlowSettings() {
        val medium = floatArrayOf(4f, 5f, 6f, 1f, 0.15f, 4f)
        for (level in SmoothLevel.entries) assertArrayEquals(medium, Smoothing(FrameInterpolation.Blend, level).flowParams(medium), 0f)
        assertArrayEquals(medium, Smoothing(FrameInterpolation.Flow, SmoothLevel.Medium).flowParams(medium), 0f)
        assertArrayEquals(floatArrayOf(8f, 4f, 4f, 1f, 0.15f, 4f), Smoothing(FrameInterpolation.Flow, SmoothLevel.Low).flowParams(medium), 0f)
        assertArrayEquals(floatArrayOf(2f, 6f, 8f, 2f, 0.15f, 4f), Smoothing(FrameInterpolation.Flow, SmoothLevel.High).flowParams(medium), 0f)
        assertFalse(Smoothing().active)
        assertTrue(Smoothing(blur = MotionBlur.Low).active)
        assertEquals(listOf(0f, 90f, 180f, 360f), MotionBlur.entries.map { it.shutter })
    }

    @Test fun mediumPicksTheSameFramesAsBeforeAndOffWithoutBlurLeavesTheFrameAlone() {
        val scene = Scene.create("t", 1920, 1080, 30, 0)
        for (method in listOf(FrameInterpolation.Blend, FrameInterpolation.Flow)) for (k in 0 until 120) {
            val got = frame(Smoothing(method), k, 60)
            val old = clip().exportFrame(scene.at(k, 60), scene, thirty, 60)
            assertEquals(old, got?.pair)
            got ?: continue
            assertEquals(listOf(got.pair.a, got.pair.b, 0f), listOf(got.before, got.after, got.blur))
            assertFalse(got.soft)
        }
        for (k in 0 until 60) assertNull(frame(Smoothing(), k, 60))
        for (k in 0 until 60) assertNull(frame(Smoothing(FrameInterpolation.Flow), k, 30))
    }

    @Test fun blendLowLeansOnTheNearestFrameAndHighMixesFourFrames() {
        val step = 1_000_000L / 30
        val low = (0 until 4).map { frame(Smoothing(FrameInterpolation.Blend, SmoothLevel.Low), 40 + it, 120)!!.pair.t }
        listOf(0f, 0f, 0.5f, 1f).zip(low).forEach { (want, got) -> assertEquals(want, got, 1e-3f) }
        val soft = frame(Smoothing(FrameInterpolation.Blend, SmoothLevel.High), 41, 120)!!
        assertTrue(soft.soft)
        assertEquals(listOf(thirty[9], thirty[10], thirty[11], thirty[12]), listOf(soft.before, soft.pair.a, soft.pair.b, soft.after))
        val held = frame(Smoothing(FrameInterpolation.Blend, SmoothLevel.High), 40, 120)!!
        assertEquals(FramePair(thirty[10], thirty[11], 0f), held.pair)
        assertTrue(held.soft)
        assertEquals(thirty[10] - step, held.before)
    }

    @Test fun motionBlurCoversTheShutterShareOfEachFrame() {
        for ((blur, share) in listOf(MotionBlur.Low to 0.25f, MotionBlur.Medium to 0.5f, MotionBlur.High to 1f)) {
            val got = frame(Smoothing(blur = blur), 10, 30, shown = thirty[10])!!
            assertEquals(FramePair(thirty[10], thirty[11], 0f), got.pair)
            assertEquals(share, got.blur, 1e-3f)
        }
        assertEquals(0.25f, frame(Smoothing(FrameInterpolation.Flow, blur = MotionBlur.Medium), 21, 60)!!.blur, 1e-3f)
        assertEquals(1f, frame(Smoothing(blur = MotionBlur.Medium, blurScale = 2f), 10, 30, shown = thirty[10])!!.blur, 1e-3f)
        assertEquals(0f, frame(Smoothing(blur = MotionBlur.High), 10, 30, shown = thirty.last())!!.blur, 0f)
    }
}
