package app.absentmotion.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressPlanTest {
    private val phoneClip = CompressSource(true, 120_000_000L, 1920, 1080, 30f, 60_000_000L, audio = true, audioAac = true, audioKbps = 256)

    @Test
    fun levelsShrinkInOrderAndKeepEvenSizes() {
        val low = planVideo(phoneClip, CompressLevel.Low)
        val medium = planVideo(phoneClip, CompressLevel.Medium)
        val high = planVideo(phoneClip, CompressLevel.High)
        assertEquals(1920 to 1080, low.width to low.height)
        assertEquals(1280 to 720, high.width to high.height)
        assertTrue(low.copyAudio)
        val sizes = listOf(low, medium, high).map { estimateVideo(it, phoneClip.durationUs) }
        assertTrue(sizes.toString(), sizes[0] < phoneClip.bytes && sizes[1] < sizes[0] && sizes[2] < sizes[1])
        assertEquals(1080 to 1920, scaledToShort(1080, 1920, 1080))
        val (w, h) = scaledToShort(1081, 1923, 720)
        assertTrue("$w x $h", w % 2 == 0 && h % 2 == 0 && w == 720)
    }

    @Test
    fun discordBitrateLeavesRoomForSoundAndHeaders() {
        val job = planVideo(phoneClip, CompressLevel.Discord, discord = DiscordLimit.Free)
        assertTrue(job.fits)
        assertEquals(DiscordLimit.Free.bytes, job.limitBytes)
        val bytes = (job.bitrate + job.audioKbps * 1000L) * phoneClip.durationUs / 8_000_000
        assertTrue("$bytes", bytes <= DiscordLimit.Free.bytes * 0.93)
        val retried = smallerBitrate(job, phoneClip.durationUs, DiscordLimit.Free.bytes + 2_000_000)
        assertTrue(retried < job.bitrate)
    }

    @Test
    fun aClipTooLongForTheLimitDoesNotFit() {
        val film = CompressSource(true, 4_000_000_000L, 1920, 1080, 30f, 3L * 3600 * 1_000_000, audio = true)
        assertFalse(planVideo(film, CompressLevel.Discord, discord = DiscordLimit.Free).fits)
    }

    @Test
    fun picturesGetSmallerWithEachLevel() {
        val sizes = listOf(CompressLevel.Low, CompressLevel.Medium, CompressLevel.High).map { estimatePicture(planPicture(it), 4000, 3000, 8192) }
        assertTrue(sizes.toString(), sizes[0] > sizes[1] && sizes[1] > sizes[2])
        assertEquals(2048, pictureSide(planPicture(CompressLevel.Medium), 4000, 3000, 8192))
        assertEquals(3000, pictureSide(planPicture(CompressLevel.Low), 4000, 3000, 3000))
    }
}
