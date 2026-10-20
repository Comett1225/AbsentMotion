package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyTest {
    private val video = Expectation(video = true, audio = true, durationUs = 2_000_000, frameUs = 33_333)
    private val good = listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/mp4a-latm", 2_023_311))

    @Test fun aCompleteFilePasses() {
        assertEquals(emptyList<String>(), judge(good, video, true, true))
        assertTrue(Verification(emptyList(), 2_000_000, true, true, 12).ok)
    }

    @Test fun aacPrimingAndOneFrameOfSlackAreAllowed() {
        assertEquals(emptyList<String>(), judge(listOf(ProbedTrack("video/avc", 1_967_000), ProbedTrack("audio/opus", 2_069_000)), video, true, true))
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 1_900_000), ProbedTrack("audio/opus", 1_950_000)), video, true, true).size)
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 2_000_000), ProbedTrack("audio/opus", 2_200_000)), video, true, true).size)
        assertEquals(1, judge(listOf(ProbedTrack("video/avc", 2_050_000)), video.copy(audio = false), true, true).size)
    }

    @Test fun missingTracksAndUndecodableFramesAreReported() {
        assertEquals(listOf("The file has no sound although the project has audio."), judge(good.take(1), video, true, true))
        assertEquals(listOf("The file has no video track."), judge(good.drop(1), video.copy(audio = true), null, null).take(1))
        assertEquals(listOf("The file has no audio or video in it."), judge(emptyList(), video, null, null))
        val frames = judge(good, video, false, false)
        assertEquals(2, frames.size)
        assertTrue(frames[0].contains("first"))
        assertEquals(emptyList<String>(), judge(good, video, null, null))
    }

    @Test fun audioOnlyNeedsNoVideo() {
        val audio = Expectation(video = false, audio = true, durationUs = 2_000_000, frameUs = 30_000)
        assertEquals(emptyList<String>(), judge(listOf(ProbedTrack("audio/mpeg", 2_000_000)), audio, null, null))
        assertTrue(judge(listOf(ProbedTrack("audio/raw", 1_000_000)), audio, null, null).single().contains("1.000 s instead of 2.000 s"))
    }
}
