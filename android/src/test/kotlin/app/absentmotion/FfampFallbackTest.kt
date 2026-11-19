package app.absentmotion

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import app.absentmotion.ffamp.FfampPcm
import app.absentmotion.ffamp.colorStandardOf
import app.absentmotion.ffamp.nearestStamp
import app.absentmotion.ffamp.shownStandard
import app.absentmotion.ffamp.yuvToRgb
import app.absentmotion.media.DecodedFrame
import app.absentmotion.media.Demuxer
import app.absentmotion.media.DesktopFrameInput
import app.absentmotion.media.FfampReaders
import app.absentmotion.media.FrameGrabber
import app.absentmotion.media.Yuv
import app.absentmotion.media.ffampOrSystem
import app.absentmotion.media.ffampRescues
import app.absentmotion.media.pickFfamp
import app.absentmotion.media.soundFound
import app.absentmotion.media.systemOrFfamp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class FfampFallbackTest {
    private val ffamp = object : FfampReaders {
        override fun demuxer(context: Context, uri: Uri): Demuxer = error("unused")
        override fun grabber(context: Context, uri: Uri): FrameGrabber = error("unused")
        override fun softFrames(context: Context, uri: String, rescue: Boolean): DesktopFrameInput? = error("unused")
        override fun softFrame(yuv: Yuv, pts: Long, standard: Int, fullRange: Boolean): DecodedFrame = error("unused")
        override fun reads(context: Context, uri: Uri, kind: String): Boolean = error("unused")
    }

    private fun read(reader: FfampReaders?, open: () -> String): Pair<String, Int> {
        var fellBack = 0
        return ffampOrSystem(reader, { fellBack++ }, { open() }) { "system" } to fellBack
    }

    @Test fun standardNeverAsksFfamp() {
        assertEquals("system" to 0, read(null) { error("FFamp was asked") })
    }

    @Test fun ffampReadsWhatItCanOpen() {
        assertEquals("ffamp" to 0, read(ffamp) { "ffamp" })
    }

    @Test fun aFileFfampCannotOpenGoesToTheSystemReader() {
        assertEquals("system" to 1, read(ffamp) { throw IOException("FFamp cannot open it") })
    }

    @Test fun aMissingFfampLibraryGoesToTheSystemReader() {
        assertEquals("system" to 1, read(ffamp) { throw UnsatisfiedLinkError("no libamffamp.so") })
    }

    @Test fun aFileTheSystemReadsNeverAsksFfamp() {
        assertFalse(ffampRescues(HashMap(), "video/\nfile:///a.mp4\n1", { true }) { error("FFamp was asked") })
    }

    @Test fun aFileOnlyFfampReadsGoesToFfampWithTheSettingOff() {
        assertTrue(ffampRescues(HashMap(), "video/\nfile:///a.avi\n1", { false }) { true })
    }

    @Test fun aFileNeitherReadsStaysWithTheSystem() {
        assertFalse(ffampRescues(HashMap(), "video/\nfile:///a.mp4\n1", { false }) { false })
    }

    @Test fun anUnopenableFileIsNotProbed() {
        assertFalse(ffampRescues(HashMap(), null, { error("probed") }) { error("probed") })
    }

    @Test fun eachFileVersionIsProbedOnce() {
        val known = HashMap<String, Boolean>()
        var probes = 0
        repeat(3) { assertTrue(ffampRescues(known, "audio/\nfile:///a.avi\n1", { probes++; false }) { true }) }
        assertEquals(1, probes)
        assertFalse(ffampRescues(known, "audio/\nfile:///a.avi\n2", { probes++; true }) { true })
        assertEquals(2, probes)
    }

    @Test fun theMediaReaderSettingUsesFfampWithoutProbing() {
        assertSame(ffamp, pickFfamp(ffamp, { true }, { error("platform asked") }) { error("probed") })
    }

    @Test fun phonesWithoutFfampNeverProbeIt() {
        assertNull(pickFfamp(ffamp, { false }, { false }) { error("probed") })
        assertNull(pickFfamp(null, { error("setting read") }, { true }) { error("probed") })
    }

    @Test fun withTheSettingOffOnlyRescuedFilesGoToFfamp() {
        assertSame(ffamp, pickFfamp(ffamp, { false }, { true }) { true })
        assertNull(pickFfamp(ffamp, { false }, { true }) { false })
    }

    @Test fun soundTheSystemListsNeverAsksFfamp() {
        assertTrue(soundFound(true, { error("FFamp was asked") }) { error("FFamp was asked") })
    }

    @Test fun soundTheSystemDropsIsFoundWhenFfampDecodesIt() {
        assertTrue(soundFound(false, { true }) { error("listing asked") })
        assertFalse(soundFound(false, { false }) { error("listing asked") })
    }

    @Test fun aFileTheSystemCannotOpenCountsAsSilentOnlyWhenFfampFindsNoSound() {
        assertFalse(soundFound(null, { error("decode asked") }) { false })
        assertTrue(soundFound(null, { error("decode asked") }) { true })
        assertTrue(soundFound(null, { error("decode asked") }) { null })
    }

    @Test fun onlyPcmThatIsNot16BitGoesToFfampsDecoder() {
        val lpcm = 0x6c70636dL
        assertEquals(listOf(true, false, true, true, true), listOf(8L, 16L, 24L, 32L, 64L).map { FfampPcm.pcmNeedsFfamp(lpcm, it) })
        assertFalse(FfampPcm.pcmNeedsFfamp(0x6d703461L, 24L))
        assertTrue(FfampPcm.pcmNeedsFfamp(lpcm, 16L, 1L))
        assertFalse(FfampPcm.pcmNeedsFfamp(lpcm, 16L, 0L))
    }

    @Test fun theColourMatrixPicksTheStandardAndUnspecifiedFallsBackToThePrimaries() {
        assertEquals(MediaFormat.COLOR_STANDARD_BT601_NTSC, colorStandardOf(1, 6))
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, colorStandardOf(2, 1))
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, colorStandardOf(9, 10))
        assertEquals(MediaFormat.COLOR_STANDARD_BT601_PAL, colorStandardOf(5, 2))
        assertNull(colorStandardOf(2, 2))
        assertNull(colorStandardOf(10, 2))
    }

    @Test fun theClosestFrameIsTheNearestStampOnEitherSide() {
        val stamps = longArrayOf(0, 33_366, 66_733, 100_100)
        assertEquals(33_366L, nearestStamp(stamps, 33_366))
        assertEquals(33_366L, nearestStamp(stamps, 40_000))
        assertEquals(66_733L, nearestStamp(stamps, 60_000))
        assertEquals(100_100L, nearestStamp(stamps, 100_101))
        assertEquals(0L, nearestStamp(stamps, -5))
        assertEquals(7L, nearestStamp(LongArray(0), 7))
    }

    @Test fun aWorkingSystemDecoderNeverAsksFfamp() {
        assertEquals("system", systemOrFfamp<String, String>({ "system" }, { error("FFamp was asked") }) { it })
    }

    @Test fun aSystemDecoderThatFailsHandsOverToFfamp() {
        val failed = IllegalStateException("configure failed")
        var seen: Exception? = null
        assertEquals("ffamp", systemOrFfamp<String, String>({ throw failed }, { seen = it; "rescue" }) { if (it == "rescue") "ffamp" else "?" })
        assertSame(failed, seen)
    }

    @Test fun whenFfampCannotHelpTheSystemErrorStands() {
        val failed = IllegalStateException("decoder stalled")
        assertSame(failed, assertThrows(IllegalStateException::class.java) { systemOrFfamp<String, String>({ throw failed }, { null }) { it } })
    }

    @Test fun aCancelledReadIsNeverRescued() {
        assertThrows(CancellationException::class.java) { systemOrFfamp<String, String>({ throw CancellationException() }, { error("FFamp was asked") }) { it } }
    }

    @Test fun ffampSoundSitsOnTheFileClock() {
        val lead = 500_000L
        assertEquals(0L, FfampPcm.frameAt(0, lead, 48_000, 480_000))
        assertEquals(lead, FfampPcm.ptsOf(FfampPcm.frameAt(0, lead, 48_000, 480_000), lead, 48_000))
        assertEquals(48_000L, FfampPcm.frameAt(1_500_000, lead, 48_000, 480_000))
        assertEquals(1_500_000L, FfampPcm.ptsOf(48_000, lead, 48_000))
        assertEquals(480_000L, FfampPcm.frameAt(60_000_000, lead, 48_000, 480_000))
        assertEquals(1_000_000L, FfampPcm.ptsOf(FfampPcm.frameAt(1_000_000, 0, 44_100, 441_000), 0, 44_100))
    }

    private fun shown(standard: Int, fullRange: Boolean, height: Int, y: Int, u: Int, v: Int): List<Int> {
        val k = yuvToRgb(shownStandard(standard, height), fullRange)
        val l = k[1] * (y - k[0])
        return listOf((l + k[2] * (v - 128) + 128) shr 8, (l - k[3] * (u - 128) - k[4] * (v - 128) + 128) shr 8, (l + k[5] * (u - 128) + 128) shr 8).map { it.coerceIn(0, 255) }
    }

    @Test fun ffampPicturesFollowTheStreamColourAndFallBackToTheHeightRule() {
        assertArrayEquals(intArrayOf(16, 298, 409, 100, 208, 516), yuvToRgb(shownStandard(0, 576), false))
        assertArrayEquals(intArrayOf(16, 298, 459, 55, 136, 541), yuvToRgb(shownStandard(0, 1080), false))
        assertEquals(listOf(16, 64, 128, 200, 235), listOf(16, 64, 128, 200, 235).map { shown(MediaFormat.COLOR_STANDARD_BT709, true, 1080, it, 128, 128)[0] })
        val pure = { c: List<Int> -> c.map { if (it > 250) 255 else if (it < 5) 0 else it } }
        assertEquals(listOf(0, 255, 0), pure(shown(MediaFormat.COLOR_STANDARD_BT601_PAL, false, 1080, 145, 54, 34)))
        assertEquals(listOf(255, 0, 0), pure(shown(MediaFormat.COLOR_STANDARD_BT709, false, 480, 63, 102, 240)))
    }
}
