package app.absentmotion.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {
    private fun video(mime: String, hardware: Boolean, maxPixels: Long = 1920L * 1088, rate: Double = 1920.0 * 1088 * 60) =
        EncoderCaps("enc.$mime.$hardware", mime, hardware, 64..4096, 64..4096, 2, 2,
            ProbeSizes.filter { (w, h) -> w.toLong() * h <= maxPixels }.map { (w, h) -> SizePoint(w, h, rate / (w.toDouble() * h)) },
            1..100_000_000, listOf(1, 8))

    private val report = DeviceReport(
        "k",
        listOf(
            video("video/avc", true),
            video("video/hevc", true, 3840L * 2160, 3840.0 * 2160 * 30),
            video("video/av01", false),
            EncoderCaps("aac", "audio/mp4a-latm", false, bitrates = 8000..960000, sampleRates = listOf(44100, 48000), maxChannels = 6),
        ),
        ProbeResult(Codec.H264, 1080, 1920, 31, 600, 100),
    )

    @Test fun hardwareEncoderThatFitsIsSupported() {
        assertEquals(Support.Supported, report.check(ExportSettings(1080, 30, Codec.H264), 1080, 1920))
    }

    @Test fun softwareOnlyCodecIsSlow() {
        assertTrue(report.check(ExportSettings(1080, 30, Codec.Av1), 1080, 1920) is Support.SlowSoftwareOnly)
    }

    @Test fun missingCodecSuggestsHardwareCodec() {
        val result = report.check(ExportSettings(1080, 30, Codec.Apv), 1080, 1920) as Support.Unsupported
        assertEquals(Codec.H264, result.suggestion?.codec)
    }

    @Test fun tooLargeForH264SuggestsHevcAtTheSameSize() {
        val result = report.check(ExportSettings(2160, 30, Codec.H264), 2160, 3840) as Support.Unsupported
        assertEquals(ExportSettings(2160, 30, Codec.Hevc), result.suggestion)
    }

    @Test fun frameRateOverTheRatingIsSlow() {
        assertTrue(report.check(ExportSettings(2160, 60, Codec.Hevc), 2160, 3840) is Support.SlowSoftwareOnly)
    }

    @Test fun tooLargeForEveryCodecSuggestsTheLargestSizeThatFits() {
        val result = report.check(ExportSettings(2160, 30, Codec.Av1), 4320, 7680) as Support.Unsupported
        assertEquals(ExportSettings(1440, 30, Codec.Hevc), result.suggestion)
    }

    @Test fun tallPhoneShapedVideosAreCheckedAgainstTallProbes() {
        val tall = DeviceReport("k", listOf(video("video/avc", true, 1088L * 2400)))
        assertEquals(Support.Supported, tall.check(ExportSettings(1080, 30, Codec.H264), 1080, 2400))
        assertEquals(Support.Supported, tall.check(ExportSettings(1080, 30, Codec.H264), 2340, 1080))
    }

    @Test fun portraitAndLandscapeAreCheckedSeparately() {
        val landscapeOnly = DeviceReport("k", listOf(EncoderCaps("av1", "video/av01", false, 2..1920, 2..1920, 1, 1, listOf(SizePoint(1920, 1080, 30.0)))))
        assertTrue(landscapeOnly.check(ExportSettings(1080, 30, Codec.Av1), 1920, 1080) is Support.SlowSoftwareOnly)
        assertTrue(landscapeOnly.check(ExportSettings(1080, 30, Codec.Av1), 1080, 1920) is Support.Unsupported)
        assertTrue(landscapeOnly.check(ExportSettings(720, 30, Codec.Av1), 1280, 720) is Support.SlowSoftwareOnly)
    }

    @Test fun containerRulesComeFirst() {
        val webmH264 = ExportSettings(1080, 30, Codec.H264, container = Container.WebM)
        val result = report.check(webmH264, 1080, 1920) as Support.Unsupported
        assertEquals(Container.Mp4, result.suggestion?.container)
        assertEquals(Codec.H264, result.suggestion?.codec)
        val mkvOpus = ExportSettings(1080, 30, Codec.H264, container = Container.Mov, audio = AudioSettings(AudioCodec.Opus))
        assertTrue(report.check(mkvOpus, 1080, 1920, hasAudio = false) is Support.Supported)
        assertEquals(AudioCodec.Aac, (report.check(mkvOpus, 1080, 1920) as Support.Unsupported).suggestion?.audio?.codec)
    }

    @Test fun oddSizesMissTheAlignment() {
        assertTrue(report.check(ExportSettings(1080, 30, Codec.H264), 1081, 1920) is Support.Unsupported)
    }

    @Test fun jsonRoundTrip() {
        assertEquals(report, DeviceReport.fromJson(JSONObject(report.toJson().toString())))
        assertEquals(1080.0 * 1920 * 60, report.probe!!.pixelsPerSecond, 1.0)
    }
}
