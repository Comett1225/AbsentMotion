package app.absentmotion.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPlansTest {
    private val aacClip = CompressSource(true, 60_000_000L, 1920, 1080, 30f, 60_000_000L, audio = true, audioAac = true, audioKbps = 160)
    private val opusClip = CompressSource(true, 60_000_000L, 1920, 1080, 30f, 60_000_000L, audio = true, audioAac = false, audioKbps = 0)
    private val silent = CompressSource(true, 60_000_000L, 1920, 1080, 30f, 60_000_000L)
    private val keys = longArrayOf(0, 1_000_000, 2_000_000, 3_000_000, 4_466_667)

    @Test
    fun soundIsCopiedOnlyFromAacAsM4a() {
        val copy = planAudio(aacClip, ExtractChoice(false, 0))
        assertTrue(copy.copy)
        assertEquals(160, copy.kbps)
        assertEquals("m4a", copy.extension)
        assertEquals("audio/mp4", copy.mime)
        assertFalse(planAudio(aacClip, ExtractChoice(true, 0)).copy)
        assertFalse(planAudio(opusClip, ExtractChoice(false, 0)).copy)
        assertEquals(FALLBACK_SOUND_KBPS, planAudio(opusClip, ExtractChoice(false, 0)).kbps)
        val mp3 = planAudio(aacClip, ExtractChoice(true, 192))
        assertEquals("mp3" to "audio/mpeg", mp3.extension to mp3.mime)
        assertEquals(192, mp3.kbps)
        assertFalse(canCopySound(silent, ExtractChoice(false, 0)))
    }

    @Test
    fun soundSizeFollowsTheBitrate() {
        val small = estimateAudio(AudioJob(false, 64, false), 60_000_000L)
        val best = estimateAudio(AudioJob(false, 192, false), 60_000_000L)
        assertEquals(64 * 125L * 60 + 4_096, small)
        assertEquals(3 * (small - 4_096) + 4_096, best)
        assertEquals(4_096L, estimateAudio(AudioJob(true, 128, false), 0))
    }

    @Test
    fun theRangeStaysInsideTheVideoAndKeepsAGap() {
        val whole = fullRange(10_000_000)
        assertEquals(TrimRange(0, 10_000_000), whole)
        assertEquals(TrimRange(0, 4_000_000), fullRange(10_000_000, 4_000_000))
        assertEquals(TrimRange(9_900_000, 10_000_000), moveStart(whole, 20_000_000, 10_000_000))
        assertEquals(TrimRange(0, 10_000_000), moveStart(whole, -5, 10_000_000))
        assertEquals(TrimRange(0, 100_000), moveEnd(whole, 0, 10_000_000))
        assertEquals(TrimRange(0, 10_000_000), moveEnd(whole, 99_000_000, 10_000_000))
        val part = TrimRange(2_000_000, 6_000_000)
        assertEquals(TrimRange(5_900_000, 6_000_000), moveStart(part, 7_000_000, 10_000_000))
        assertEquals(TrimRange(2_000_000, 2_100_000), moveEnd(part, 1_000_000, 10_000_000))
        assertEquals(4_000_000, part.lengthUs)
    }

    @Test
    fun theLongestPartLimitsBothHandles() {
        val limit = 5_000_000L
        val part = TrimRange(10_000_000, 14_000_000)
        assertEquals(TrimRange(9_000_000, 14_000_000), moveStart(part, 1_000_000, 60_000_000, limit))
        assertEquals(TrimRange(10_000_000, 15_000_000), moveEnd(part, 40_000_000, 60_000_000, limit))
        assertEquals(TrimRange(0, 5_000_000), fullRange(60_000_000, limit))
        assertEquals(TrimRange(0, 100_000), moveEnd(TrimRange(0, 100_000), 0, 60_000_000, limit))
    }

    @Test
    fun aShortVideoStillHasARange() {
        val range = TrimRange(0, 50_000)
        assertEquals(range, moveStart(range, 10_000, 50_000))
        assertEquals(range, moveEnd(range, 90_000, 50_000))
    }

    @Test
    fun keyframesAreFoundNearestAndStepped() {
        assertEquals(1_000_000, nearestKeyframe(1_400_000, keys))
        assertEquals(1_000_000, nearestKeyframe(1_500_000, keys))
        assertEquals(2_000_000, nearestKeyframe(1_500_001, keys))
        assertEquals(0, nearestKeyframe(-5, keys))
        assertEquals(4_466_667, nearestKeyframe(9_000_000, keys))
        assertEquals(3_000_000, nearestKeyframe(3_000_000, keys))
        assertEquals(1_234, nearestKeyframe(1_234, LongArray(0)))
        assertEquals(3_000_000, stepKeyframe(2_000_000, keys, true))
        assertEquals(1_000_000, stepKeyframe(2_000_000, keys, false))
        assertEquals(2_000_000, stepKeyframe(1_500_000, keys, true))
        assertEquals(1_000_000, stepKeyframe(1_500_000, keys, false))
        assertEquals(4_466_667, stepKeyframe(4_466_667, keys, true))
        assertEquals(0, stepKeyframe(0, keys, false))
    }

    @Test
    fun theFastStartNeverPassesTheEnd() {
        assertEquals(1_000_000, snapStart(1_300_000, keys, 8_000_000))
        assertEquals(2_000_000, snapStart(3_400_000, keys, 3_050_000))
        assertEquals(500_000, snapStart(500_000, LongArray(0), 8_000_000))
        assertEquals(0, snapStart(40_000, longArrayOf(0), 8_000_000))
    }

    @Test
    fun fastCopiesOnlyWhenTheCutIsOnAKeyframe() {
        val range = TrimRange(2_000_000, 5_000_000)
        assertTrue(planTrim(range, keys, true).copy)
        assertFalse(planTrim(range, keys, false).copy)
        assertFalse(planTrim(TrimRange(2_500_000, 5_000_000), keys, true).copy)
        assertFalse(planTrim(range, LongArray(0), true).copy)
        assertFalse(planTrim(TrimRange(4_460_000, 5_000_000), keys, true).copy)
        assertTrue(planTrim(TrimRange(4_466_667, 5_000_000), keys, true).copy)
        assertTrue(onKeyframe(2_001_000, keys))
        assertFalse(onKeyframe(2_005_000, keys))
    }

    @Test
    fun trimmedSizeGrowsWithTheLength() {
        val short = estimateTrim(aacClip, TrimJob(TrimRange(0, 6_000_000), true))
        val long = estimateTrim(aacClip, TrimJob(TrimRange(0, 30_000_000), true))
        assertEquals(6_000_000L, short)
        assertEquals(30_000_000L, long)
        val exact = estimateTrim(aacClip, TrimJob(TrimRange(0, 30_000_000), false))
        assertTrue("$exact", exact in 1..aacClip.bytes)
        assertEquals(5_000L, estimateTrim(CompressSource(true, 5_000L, 640, 360, 30f), TrimJob(TrimRange(0, 1), true)))
    }

    @Test
    fun theReEncodeKeepsSizeFramesAndSound() {
        val job = trimVideoJob(aacClip)
        assertEquals(1920 to 1080, job.width to job.height)
        assertEquals(30, job.fps)
        assertTrue(job.copyAudio)
        assertEquals(160, job.audioKbps)
        assertTrue(job.bitrate >= MIN_VIDEO_BITRATE)
        assertTrue(job.bitrate <= bitsFor(1920, 1080, 30, TRIM_BITS_PER_PIXEL))
        assertTrue(job.bitrate <= sourceVideoBitrate(aacClip))
        val phone = trimVideoJob(CompressSource(true, 1_000_000L, 1280, 720, 120f, 10_000_000L, audio = true, audioAac = false))
        assertEquals(60, phone.fps)
        assertFalse(phone.copyAudio)
        assertEquals(FALLBACK_SOUND_KBPS, phone.audioKbps)
        assertEquals(0, trimVideoJob(silent).audioKbps)
        val odd = trimVideoJob(CompressSource(true, 1_000_000L, 1281, 721, 30f, 1_000_000L))
        assertTrue(odd.width % 2 == 0 && odd.height % 2 == 0)
    }

    @Test
    fun gifSizeKeepsTheShapeAndNeverGrows() {
        assertEquals(480 to 270, gifSize(1920, 1080, 0, 480))
        assertEquals(270 to 480, gifSize(1920, 1080, 90, 480))
        assertEquals(1080 to 1920, gifSize(1080, 1920, 0, 4000))
        assertEquals(320 to 240, gifSize(320, 240, 0, 720))
        assertEquals(720 to 405, gifSize(1920, 1080, 0, 720))
        assertEquals(2 to 2, gifSize(1, 1, 0, 480))
        assertEquals(180 to 320, gifSize(1080, 1920, 0, 320))
    }

    @Test
    fun gifFramesFollowTheRateAndTheLimits() {
        val range = TrimRange(1_000_000, 3_000_000)
        val job = planGif(aacClip, 0, GifChoice(480, 15, true), range)
        assertEquals(15, job.fps)
        assertEquals(30, job.frames)
        assertEquals(480 to 270, job.width to job.height)
        val times = gifFrameTimes(job)
        assertEquals(30, times.size)
        assertEquals(1_000_000L, times.first())
        assertEquals(1_066_666L, times[1])
        assertTrue(times.last() < range.endUs)
        assertEquals(66L, gifFrameEndMs(job, 0))
        assertEquals(2000L, gifFrameEndMs(job, 29))
        val cut = planGif(aacClip, 0, GifChoice(480, 10, true), TrimRange(0, 1_050_000))
        assertEquals(11, cut.frames)
        assertEquals(1000L, gifFrameEndMs(cut, 9))
        assertEquals(1050L, gifFrameEndMs(cut, 10))
        assertEquals(12, planGif(CompressSource(true, 1L, 640, 360, 12f), 0, GifChoice(480, 24, true), TrimRange(0, 1_000_000)).fps)
        val long = planGif(aacClip, 0, GifChoice(480, 24, true), TrimRange(0, 120_000_000))
        assertEquals(MAX_GIF_US, long.range.lengthUs)
        assertEquals(720, long.frames)
        assertEquals(1, planGif(aacClip, 0, GifChoice(480, 10, true), TrimRange(5, 6)).frames)
        assertFalse(planGif(aacClip, 0, GifChoice(loop = false), range).loop)
    }

    @Test
    fun gifSizeGrowsWithSizeRateAndLength() {
        val range = TrimRange(0, 4_000_000)
        val a = estimateGif(planGif(aacClip, 0, GifChoice(320, 10, true), range))
        val b = estimateGif(planGif(aacClip, 0, GifChoice(480, 10, true), range))
        val c = estimateGif(planGif(aacClip, 0, GifChoice(480, 24, true), range))
        val d = estimateGif(planGif(aacClip, 0, GifChoice(480, 24, true), TrimRange(0, 8_000_000)))
        assertTrue("$a $b $c $d", a < b && b < c && c < d)
        assertEquals((480L * 270 * 40 * 6 / 10 + 800).toDouble(), estimateGif(planGif(aacClip, 0, GifChoice(480, 10, true), range)).toDouble(), 1.0)
    }

    @Test
    fun framesAreReadInRunsThatStayCloseTogether() {
        val stamps = LongArray(1000) { it * 33_333L }
        val times = LongArray(100) { it * 100_000L }
        val chunks = frameChunks(times, stamps, 90)
        assertEquals(times.indices.toList(), chunks.flatMap { it.toList() })
        for (chunk in chunks) {
            val first = stamps.binarySearch(times[chunk.first]).let { if (it >= 0) it else -it - 1 }
            val last = stamps.binarySearch(times[chunk.last]).let { if (it >= 0) it else -it - 1 }
            assertTrue("$chunk", last - first <= 90)
        }
        assertTrue(chunks.size > 1)
        assertEquals(listOf(0..0), frameChunks(longArrayOf(5), stamps, 90))
        assertEquals(emptyList<IntRange>(), frameChunks(LongArray(0), stamps))
        assertEquals(listOf(0..1, 2..2), frameChunks(longArrayOf(0, 33_333, 900_000), stamps, 3))
    }

    @Test
    fun framePixelsAverageAndTurnLikeThePlayer() {
        val coded = IntArray(6) { 0xFF000000.toInt() or ((it * 10) shl 16) }
        val flat = ByteArray(3 * 2 * 4)
        framePixels(coded, 1, 0, 3, 2, flat)
        assertEquals(listOf(0, 10, 20, 30, 40, 50), (0 until 6).map { flat[it * 4].toInt() and 255 })
        assertTrue((0 until 6).all { flat[it * 4 + 3] == (-1).toByte() })
        val right = ByteArray(2 * 3 * 4)
        framePixels(coded, 1, 90, 2, 3, right)
        assertEquals(listOf(30, 0, 40, 10, 50, 20), (0 until 6).map { right[it * 4].toInt() and 255 })
        val upside = ByteArray(3 * 2 * 4)
        framePixels(coded, 1, 180, 3, 2, upside)
        assertEquals(listOf(50, 40, 30, 20, 10, 0), (0 until 6).map { upside[it * 4].toInt() and 255 })
        val left = ByteArray(2 * 3 * 4)
        framePixels(coded, 1, 270, 2, 3, left)
        assertEquals(listOf(20, 50, 10, 40, 0, 30), (0 until 6).map { left[it * 4].toInt() and 255 })
        val big = IntArray(4 * 4) { 0xFF000000.toInt() or (if (it % 2 == 0) 200 shl 16 else 100 shl 16) }
        val small = ByteArray(2 * 2 * 4)
        framePixels(big, 2, 0, 2, 2, small)
        assertEquals(listOf(150, 150, 150, 150), (0 until 4).map { small[it * 4].toInt() and 255 })
    }

    @Test
    fun timesAreShownLikeAPlayer() {
        assertEquals("0:00.0", clock(0))
        assertEquals("0:02.5", clock(2_500_000))
        assertEquals("1:05.1", clock(65_100_000))
        assertEquals("1:00:00.0", clock(3_600_000_000L))
        assertEquals("0:00.0", clock(-5))
        assertEquals("0:05", clockWhole(4_600_000))
        assertEquals("1:01:01", clockWhole(3_661_000_000L))
    }

    @Test
    fun theGifLoopBlockIsOptionalAndTheOldOutputIsUnchanged() {
        fun gif(vararg loop: Boolean): ByteArray = java.io.ByteArrayOutputStream().also { out ->
            val writer = if (loop.isEmpty()) app.absentmotion.export.GifWriter(out, 4, 4) else app.absentmotion.export.GifWriter(out, 4, 4, loop[0])
            writer.frame(ByteArray(64) { 100 }, 100)
            writer.finish()
        }.toByteArray()
        val default = gif()
        val looped = gif(true)
        val once = gif(false)
        assertTrue(default.contentEquals(looped))
        assertTrue(String(looped, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertFalse(String(once, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals(looped.size - 19, once.size)
        assertEquals("GIF89a", String(once, 0, 6))
    }

    @Test
    fun aCopyKeepsTheWholeRangeOfBothTracksAndStopsAtTheNextKeyframe() {
        val window = CopyWindow(1_000_000, 3_000_000, 2)
        val written = ArrayList<Pair<Int, Long>>()
        fun feed(track: Int, video: Boolean, time: Long, sync: Boolean = false): Boolean {
            val take = window.take(track, video, time, sync)
            if (take) written += track to time
            return window.done
        }
        assertFalse(feed(1, false, 900_000))
        assertFalse(feed(0, true, 999_000, true))
        assertFalse(feed(0, true, 1_000_000, true))
        assertFalse(feed(1, false, 1_005_000))
        assertFalse(feed(0, true, 1_033_333))
        assertFalse(feed(0, true, 2_966_667))
        assertFalse(feed(0, true, 3_000_000))
        assertFalse(feed(0, true, 3_033_333))
        assertFalse(feed(1, false, 3_000_001))
        assertFalse(feed(0, true, 3_066_667))
        assertTrue(feed(0, true, 4_000_000, true))
        assertTrue(window.done)
        assertEquals(listOf(0 to 999_000L, 0 to 1_000_000L, 1 to 1_005_000L, 0 to 1_033_333L, 0 to 2_966_667L, 0 to 3_000_000L), written)
        val tail = CopyWindow(0, 1_000_000, 1)
        assertTrue(tail.take(0, true, 500_000, true))
        assertFalse(tail.take(0, true, 1_500_000, false))
        assertFalse(tail.done)
        assertFalse(tail.take(0, true, 3_100_000, false))
        assertTrue(tail.done)
        val sound = CopyWindow(0, Long.MAX_VALUE, 1)
        assertTrue(sound.take(0, false, 4_000_000_000L, true))
        assertFalse(sound.done)
    }

    @Test
    fun aCopyWithBFramesKeepsTheFramesTheEndNeeds() {
        val order = listOf(0L to true, 300_000L to false, 100_000L to false, 200_000L to false, 600_000L to false, 400_000L to false,
            500_000L to false, 900_000L to false, 700_000L to false, 800_000L to false, 1_200_000L to true, 1_100_000L to false)
        val last = lastDecodedUs(order.asSequence(), 500_000)
        assertEquals(500_000L, last)
        fun copied(window: CopyWindow) = order.filter { (t, sync) -> window.take(0, true, t, sync) }.map { it.first }
        assertEquals(listOf(0L, 300_000L, 100_000L, 200_000L, 600_000L, 400_000L, 500_000L), copied(CopyWindow(0, 500_000, 1, last)))
        assertEquals(listOf(0L, 300_000L, 100_000L, 200_000L, 400_000L, 500_000L), copied(CopyWindow(0, 500_000, 1)))
        assertNull(lastDecodedUs(emptySequence(), 500_000))
    }

    @Test
    fun anExactTrimBetweenTwoFramesStartsFromTheFrameOnScreen() {
        val oneFps = sequenceOf(0L, 2_000_000L, 1_000_000L, 3_000_000L)
        assertEquals(1_000_000L, app.absentmotion.media.firstShownUs(oneFps, 1_200_000, 1_500_000))
        assertEquals(1_200_000L, app.absentmotion.media.firstShownUs(oneFps, 1_200_000, 2_100_000))
        assertEquals(1_200_000L, app.absentmotion.media.firstShownUs(emptySequence(), 1_200_000, 1_500_000))
    }
}
