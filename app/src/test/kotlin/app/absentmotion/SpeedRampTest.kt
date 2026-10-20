package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.RAMP_EASE
import app.absentmotion.model.SpeedRamp
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.retimedVideo
import app.absentmotion.model.speedRampKeys
import app.absentmotion.model.withFreezeFrame
import app.absentmotion.model.withSpeedRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampTest {
    private val fps = 30

    private fun video(start: Int, end: Int, vararg children: XmlNode) = Layer(XmlNode("shape", linkedMapOf("id" to "1",
        "startTime" to "$start", "endTime" to "$end", "fillType" to "media", "fillVideo" to "content://v", "inTime" to "500"), children.toList()))

    private fun media(layer: Layer, projectMs: Int) = VideoTiming(layer).mediaMs((projectMs - layer.startTime).toDouble())

    private fun span(layer: Layer) = media(layer, layer.endTime) - media(layer, layer.startTime)

    @Test fun everyPresetCoversTheClipWithEasedKeys() {
        for (ramp in SpeedRamp.entries) {
            val keys = speedRampKeys(ramp, 500e6f, 4500e6f)
            assertEquals(ramp.name, 500e6f, keys.first().t, 1f)
            assertEquals(ramp.name, 4500e6f, keys.last().t, 1f)
            assertTrue(ramp.name, keys.zipWithNext().all { (a, b) -> b.t > a.t })
            assertNull(keys.first().e)
            assertTrue(ramp.name, keys.drop(1).all { it.e == RAMP_EASE })
            assertTrue(ramp.name, keys.all { it.v.toFloat() in 0.001f..4f })
        }
    }

    @Test fun thePresetsHaveTheirShape() {
        fun speeds(ramp: SpeedRamp) = ramp.points.map { it.second }
        assertTrue(speeds(SpeedRamp.FlashIn).first() > speeds(SpeedRamp.FlashIn).last())
        assertTrue(speeds(SpeedRamp.FlashOut).first() < speeds(SpeedRamp.FlashOut).last())
        for (ramp in listOf(SpeedRamp.Hero, SpeedRamp.Bullet)) {
            val s = speeds(ramp)
            assertTrue(s.first() > 1f && s.last() > 1f && s.min() < 1f)
        }
        assertEquals(4f, speeds(SpeedRamp.JumpCut).max())
        assertEquals(1f, speeds(SpeedRamp.JumpCut).first())
        assertEquals(listOf("Montage", "Hero", "Bullet", "JumpCut", "FlashIn", "FlashOut"), SpeedRamp.entries.map { it.name })
    }

    @Test fun applyingAPresetKeepsTheMediaAndRetimesTheClip() {
        val layer = video(1000, 4999)
        for (ramp in SpeedRamp.entries) {
            val ramped = layer.withSpeedRamp(ramp, ease = null)
            assertEquals(ramp.name, span(layer), span(ramped), 2.0)
            assertEquals(ramp.name, ramp.points.size, ramped.node.child("speedMap")!!.keyframes.size)
            assertEquals(ramp.name, ramp.points.first().second.toDouble(), VideoTiming(ramped).speed(0.0).toDouble(), 1e-3)
        }
        assertTrue(layer.withSpeedRamp(SpeedRamp.FlashOut, ease = null).endTime < layer.endTime)
        assertTrue(layer.withSpeedRamp(SpeedRamp.Hero, ease = null).endTime > layer.endTime)
    }

    @Test fun freezeHoldsThePlayheadFrameForTheChosenTime() {
        val layer = video(1000, 4999)
        val frozen = layer.withFreezeFrame(2000, 2000, fps)!!
        assertEquals(6999.0, frozen.endTime.toDouble(), 1000.0 / fps)
        val held = media(layer, 2000)
        for (ms in 2000..4000 step 50) assertEquals("at $ms", held, media(frozen, ms), 1.05)
        assertEquals(media(layer, 1500), media(frozen, 1500), 0.01)
        assertEquals(media(layer, 3000) , media(frozen, 5000), 1.5)
        assertEquals(span(layer), span(frozen), 2.0)
    }

    @Test fun freezeWorksOnARampAndKeepsTheRampAroundIt() {
        val ramp = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "500000000", "v" to "1")),
            XmlNode("kf", mapOf("t" to "4500000000", "v" to "2", "e" to "linear")),
        ))
        val layer = video(0, 2999, ramp)
        val before = VideoTiming(layer)
        val frozen = layer.withFreezeFrame(1500, 1000, fps)!!
        val after = VideoTiming(frozen)
        assertEquals(layer.endTime + 1000.0, frozen.endTime.toDouble(), 1000.0 / fps)
        assertEquals(before.mediaMs(1500.0), after.mediaMs(2000.0), 1.05)
        assertEquals(before.speed(1000.0).toDouble(), after.speed(1000.0).toDouble(), 1e-3)
        assertEquals(before.speed(2000.0).toDouble(), after.speed(3000.0).toDouble(), 0.01)
    }

    @Test fun freezeOnlyWorksInsideTheClip() {
        val layer = video(1000, 4999)
        assertNull(layer.withFreezeFrame(1000, 2000, fps))
        assertNull(layer.withFreezeFrame(4999, 2000, fps))
        assertNull(layer.withFreezeFrame(500, 2000, fps))
        assertNull(layer.withFreezeFrame(2000, 0, fps))
    }

    @Test fun retimingAFrozenClipKeepsTheHold() {
        val frozen = video(1000, 4999).withFreezeFrame(2000, 2000, fps)!!
        val length = frozen.endTime - frozen.startTime
        val halved = frozen.retimedVideo(frozen.startTime, frozen.startTime + length / 2)
        val held = media(frozen, 2000)
        for (ms in 1500..2500 step 50) assertEquals("at $ms", held, media(halved, ms), 1.05)
    }

    @Test fun aLongVideoStillGetsADistinctHold() {
        val layer = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "3599999",
            "fillType" to "media", "fillVideo" to "content://v", "inTime" to "0")))
        val frozen = layer.withFreezeFrame(3_000_000, 3000, fps)!!
        assertEquals(4, frozen.node.child("speedMap")!!.keyframes.size)
        assertEquals(layer.endTime + 3000.0, frozen.endTime.toDouble(), 1000.0 / fps)
        val held = media(layer, 3_000_000)
        for (ms in 3_000_000..3_003_000 step 100) assertEquals("at $ms", held, media(frozen, ms), 3.0)
    }
}
