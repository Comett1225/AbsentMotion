package app.absentmotion.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePlanTest {
    private val phone = CompressSource(true, 20_000_000L, 1920, 1080, 30f, 10_000_000L, audio = true, audioAac = true, audioKbps = 128)
    private fun clip(source: CompressSource = phone, rotation: Int = 0, video: String = "avc|1920|1080|a", sound: String? = "aac|48000|2") = MergeFacts(source, rotation, video, sound)

    @Test fun matchingClipsAreCopiedAndKeepTheirSize() {
        val clips = listOf(clip(), clip(phone.let { CompressSource(true, 6_000_000L, 1920, 1080, 30f, 3_000_000L, true, true, 128) }))
        val job = planMerge(clips)
        assertTrue(job.copy)
        assertEquals(26_000_000L, estimateMerge(clips, job))
        assertEquals(13_000_000L, mergeLengthUs(clips))
        assertArrayEquals(longArrayOf(0, 10_000_000L, 13_000_000L), mergeStarts(clips))
    }

    @Test fun anyDifferenceMeansReEncoding() {
        assertFalse(planMerge(listOf(clip())).copy)
        assertFalse(planMerge(listOf(clip(), clip(video = "avc|1280|720|a"))).copy)
        assertFalse(planMerge(listOf(clip(), clip(sound = null))).copy)
        assertFalse(planMerge(listOf(clip(), clip(sound = "aac|44100|2"))).copy)
        assertTrue(planMerge(listOf(clip(sound = null), clip(sound = null))).copy)
    }

    @Test fun reEncodingFollowsTheFirstClipAndTheSourceBitrate() {
        val small = CompressSource(true, 1_000_000L, 640, 480, 24f, 2_000_000L)
        val job = planMerge(listOf(clip(rotation = 90), clip(small, video = "avc|640|480", sound = null)))
        assertFalse(job.copy)
        assertEquals(1080 to 1920, job.width to job.height)
        assertEquals(30, job.fps)
        assertEquals(128, job.audioKbps)
        val expected = (sourceVideoBitrate(phone).toDouble() * 10 + sourceVideoBitrate(small).toDouble() * 2) / 12
        assertEquals(expected, job.bitrate.toDouble(), 2.0)
        assertEquals(estimateVideo(VideoJob(1080, 1920, 30, job.bitrate, 128, false, 0), 12_000_000L), estimateMerge(listOf(clip(rotation = 90), clip(small, video = "x", sound = null)), job))
        assertEquals(0, planMerge(listOf(clip(small, sound = null), clip(small, video = "y", sound = null))).audioKbps)
    }

    @Test fun theBitrateStaysWithinSensibleBounds() {
        val tiny = CompressSource(true, 10_000L, 320, 240, 15f, 10_000_000L)
        assertEquals(MIN_VIDEO_BITRATE, planMerge(listOf(clip(tiny, video = "a", sound = null), clip(tiny, video = "b", sound = null))).bitrate)
        val huge = CompressSource(true, 2_000_000_000L, 640, 360, 30f, 10_000_000L)
        assertEquals(bitsFor(640, 360, 30, 0.25), planMerge(listOf(clip(huge, video = "a", sound = null), clip(huge, video = "b", sound = null))).bitrate)
    }
}
