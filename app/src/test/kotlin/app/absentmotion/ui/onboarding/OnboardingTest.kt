package app.absentmotion.ui.onboarding

import app.absentmotion.export.Codec
import app.absentmotion.export.DeviceReport
import app.absentmotion.export.EncoderCaps
import app.absentmotion.export.ProbeResult
import app.absentmotion.export.ProbeSizes
import app.absentmotion.export.SizePoint
import app.absentmotion.export.sizeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    @Test fun aNewInstallStartsWithTheLanguageScreen() {
        assertEquals(OnboardingStep.Language, FirstRun.start(0))
    }

    @Test fun aChosenLanguageResumesAtThePhoneCheck() {
        assertEquals(OnboardingStep.Check, FirstRun.start(ONBOARDING_LANGUAGE_CHOSEN))
    }

    @Test fun finishedOnboardingNeverShowsAgain() {
        assertNull(FirstRun.start(ONBOARDING_DONE))
        assertNull(FirstRun.start(ONBOARDING_DONE + 1))
    }

    @Test fun exportSizesUseShortNames() {
        assertEquals("4K", sizeLabel(2160))
        assertEquals("1440p", sizeLabel(1440))
        assertEquals("1080p", sizeLabel(1080))
        assertEquals("720p", sizeLabel(720))
    }

    private fun video(mime: String, hardware: Boolean, maxPixels: Long, rate: Double) =
        EncoderCaps("enc.$mime", mime, hardware, 64..4096, 64..4096, 2, 2,
            ProbeSizes.filter { (w, h) -> w.toLong() * h <= maxPixels }.map { (w, h) -> SizePoint(w, h, rate / (w.toDouble() * h)) })

    private val encoders = listOf(
        video("video/avc", true, 1920L * 1088, 1920.0 * 1088 * 60),
        video("video/hevc", true, 3840L * 2160, 3840.0 * 2160 * 30),
        video("video/av01", false, 1920L * 1088, 1920.0 * 1088 * 30),
    )

    @Test fun phoneCheckReadsTheDeviceReport() {
        val result = phoneCheckResult(DeviceReport("k", encoders), 0)
        val formats = result.formats.associateBy { it.name }
        assertEquals(FormatSupport(Codec.H264.label, 1080, true), formats[Codec.H264.label])
        assertEquals(FormatSupport(Codec.Hevc.label, 2160, true), formats[Codec.Hevc.label])
        assertEquals(FormatSupport(Codec.Av1.label, 1080, false), formats[Codec.Av1.label])
        assertEquals(FormatSupport(Codec.Apv.label, 0, false), formats[Codec.Apv.label])
        assertEquals(PhoneSpeed.Good, result.speed)
    }

    @Test fun phoneSpeedComesFromTheMeasuredExport() {
        fun speed(ms: Long) = phoneCheckResult(DeviceReport("k", encoders, ProbeResult(Codec.H264, 1080, 1920, 31, ms, 100)), 0).speed
        assertEquals(PhoneSpeed.Fast, speed(600))
        assertEquals(PhoneSpeed.Good, speed(2000))
        assertEquals(PhoneSpeed.Basic, speed(6000))
    }
}
