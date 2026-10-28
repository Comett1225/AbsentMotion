package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyTest {
    private val video = Expectation(video = true, audio = true, durationUs = 2_000_000, frameUs = 33_333)
    private val good = listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/mp4a-latm", 2_023_311))

    @Test fun aCompleteFilePasses() {
        assertEquals(emptyList<UiText>(), judge(good, video, true, true))
        assertTrue(Verification(emptyList(), 2_000_000, true, true, 12).ok)
    }

    @Test fun aacPrimingAndOneFrameOfSlackAreAllowed() {
        assertEquals(emptyList<UiText>(), judge(listOf(ProbedTrack("video/avc", 1_967_000), ProbedTrack("audio/opus", 2_069_000)), video, true, true))
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 1_900_000), ProbedTrack("audio/opus", 1_950_000)), video, true, true).size)
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/opus", 2_200_000)), video, true, true).size)
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 2_050_000)), video.copy(audio = false), true, true).size)
    }

    @Test fun missingTracksAndUndecodableFramesAreReported() {
        assertEquals(listOf(txt(R.string.export_verify_no_audio)), judge(good.take(1), video, true, true))
        assertEquals(listOf(txt(R.string.export_verify_no_video)), judge(good.drop(1), video.copy(audio = true), null, null).take(1))
        assertEquals(listOf(txt(R.string.export_verify_empty)), judge(emptyList(), video, null, null))
        val frames = judge(good, video, false, false)
        assertEquals(2, frames.size)
        assertEquals(txt(R.string.export_verify_first_frame), frames[0])
        assertEquals(emptyList<UiText>(), judge(good, video, null, null))
    }

    private fun flat(value: Float) = FloatArray(LookCells * LookCells) { value }

    @Test fun aBlackOrFrozenMiddleIsReportedAndARealOneIsNot() {
        val moving = FrameLooks(1_000_000, flat(0.2f), FloatArray(LookCells * LookCells) { if (it % 2 == 0) 0.9f else 0.3f })
        assertTrue(middleLooksRight(moving, flat(0.21f), FloatArray(LookCells * LookCells) { if (it % 2 == 0) 0.85f else 0.33f }))
        assertFalse(middleLooksRight(moving, flat(0.21f), flat(0f)))
        assertFalse(middleLooksRight(moving, flat(0.6f), flat(0.6f)))
        assertFalse(middleLooksRight(FrameLooks(1_000_000, flat(0.2f), flat(0.35f)), flat(0.2f), flat(0.2f)))
        val still = FrameLooks(1_000_000, flat(0.5f), flat(0.5f))
        assertTrue(middleLooksRight(still, flat(0.52f), flat(0.52f)))
        val dark = FrameLooks(1_000_000, flat(0.02f), flat(0.03f))
        assertTrue(middleLooksRight(dark, flat(0f), flat(0f)))
        assertEquals(listOf(txt(R.string.export_verify_middle_frame)), judge(good, video, true, true, false))
        assertEquals(emptyList<UiText>(), judge(good, video, true, true, null))
    }

    @Test fun drawnAndDecodedPicturesAreMeasuredTheSameWay() {
        val rgba = ByteArray(16 * 16 * 4) { i -> if (i % 4 == 3) -1 else if ((i / 4) % 16 < 8) -1 else 0 }
        val drawn = rgbaLook(rgba, 16, 16)
        assertEquals(1f, drawn[0], 0.01f)
        assertEquals(0f, drawn[LookCells - 1], 0.01f)
        val transparent = rgbaLook(ByteArray(16 * 16 * 4) { i -> if (i % 4 == 3) 0 else -1 }, 16, 16)
        assertEquals(0f, transparent.max(), 0.001f)
        val y = java.nio.ByteBuffer.allocate(64 * 32)
        for (row in 0 until 32) for (x in 0 until 64) y.put(row * 64 + x, (if (x < 32) 235 else 16).toByte())
        val decoded = yLook(y, 64, 1, 0, 0, 64, 32)
        assertEquals(1f, decoded[0], 0.01f)
        assertEquals(0f, decoded[LookCells - 1], 0.01f)
        assertTrue(lookDistance(drawn, decoded) < 0.01f)
    }

    @Test fun aFileTurnedAQuarterIsReadBackUpright() {
        val upright = FloatArray(LookCells * LookCells) { it.toFloat() }
        val stored = FloatArray(upright.size) { i -> val u = i % LookCells; val v = i / LookCells; upright[u * LookCells + (LookCells - 1 - v)] }
        assertTrue(uprightLook(stored, 90).contentEquals(upright))
        assertTrue(uprightLook(uprightLook(uprightLook(uprightLook(stored, 90), 90), 90), 90).contentEquals(stored))
        assertTrue(uprightLook(uprightLook(stored, 90), 270).contentEquals(stored))
        assertTrue(uprightLook(uprightLook(stored, 180), 180).contentEquals(stored))
        assertTrue(uprightLook(stored, 0) === stored)
    }

    @Test fun slowDevicesGetMoreTimeForBigVideos() {
        assertEquals(800L + 1080L * 1920 / 5_000, decodeBudgetMs(1080, 1920))
        assertTrue(decodeBudgetMs(3840, 2160) > decodeBudgetMs(1080, 1920))
        assertTrue(decodeBudgetMs(256, 256) < 900L)
        assertEquals(5_000L, decodeBudgetMs(8192, 8192))
    }

    @Test fun audioOnlyNeedsNoVideo() {
        val audio = Expectation(video = false, audio = true, durationUs = 2_000_000, frameUs = 30_000)
        assertEquals(emptyList<UiText>(), judge(listOf(ProbedTrack("audio/mpeg", 2_000_000)), audio, null, null))
        assertEquals(txt(R.string.export_verify_duration, "1.000", "2.000"), judge(listOf(ProbedTrack("audio/raw", 1_000_000)), audio, null, null).single())
    }

    @Test fun eachExpectedTrackIsCheckedOnItsOwn() {
        val shortAudio = listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/mp4a-latm", 900_000))
        assertEquals(listOf(txt(R.string.export_verify_duration, "0.900", "2.000")), judge(shortAudio, video, true, true))
        val shortVideo = listOf(ProbedTrack("video/avc", 900_000), ProbedTrack("audio/mp4a-latm", 2_020_000))
        assertEquals(listOf(txt(R.string.export_verify_duration, "0.900", "2.000")), judge(shortVideo, video, true, true))
        val bothShort = listOf(ProbedTrack("video/avc", 900_000), ProbedTrack("audio/mp4a-latm", 900_000))
        assertEquals(2, judge(bothShort, video, true, true).size)
        assertEquals(emptyList<UiText>(), judge(listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/mp4a-latm", 1_920_000)), video, true, true))
        assertEquals(emptyList<UiText>(), judge(listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/mp4a-latm", 0)), video, true, true))
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 0), ProbedTrack("audio/mp4a-latm", 0)), video, true, true).size)
    }

    @Test fun aFirstFrameThatDecodesButLooksWrongIsReported() {
        val drawn = FrameLooks(1_000_000, flat(0.6f), flat(0.4f))
        assertTrue(firstLooksRight(drawn, flat(0.62f)))
        assertFalse(firstLooksRight(drawn, flat(0f)))
        assertEquals(listOf(txt(R.string.export_verify_first_look)), judge(good, video, true, true, null, false))
        assertEquals(listOf(txt(R.string.export_verify_first_frame)), judge(good, video, false, true, null, false))
        assertEquals(emptyList<UiText>(), judge(good, video, true, true, null, true))
        assertEquals(emptyList<UiText>(), judge(good, video, true, true, null, null))
    }

    @Test fun aReadbackThatCouldNotDecodeAnythingIsMarkedUncheckedButNotFailed() {
        assertEquals(emptyList<UiText>(), judge(good, video, null, null, null, null))
        assertTrue(isUnchecked(video, 0, null))
        assertFalse(isUnchecked(video, 0, true))
        assertFalse(isUnchecked(video, 0, false))
        assertFalse(isUnchecked(video, -1, null))
        assertFalse(isUnchecked(video.copy(video = false), 0, null))
    }

    @Test fun aContainerThatReportsOneDurationForEveryTrackDoesNotFlagAGoodVideoWithLongerAudio() {
        val matroska = listOf(ProbedTrack("video/avc", 2_024_000), ProbedTrack("audio/mp4a-latm", 2_024_000))
        assertEquals(emptyList<UiText>(), judge(matroska, video, true, true))
        assertEquals(2, judge(listOf(ProbedTrack("video/avc", 2_300_000), ProbedTrack("audio/mp4a-latm", 2_300_000)), video, true, true).size)
    }
}
