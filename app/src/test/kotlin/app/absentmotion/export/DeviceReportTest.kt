package app.absentmotion.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {
    private fun video(mime: String, hardware: Boolean, maxPixels: Long = 1920L * 1088, rate: Double = 1920.0 * 1088 * 60) =
        EncoderCaps("enc.$mime.$hardware", mime, hardware, 64..4096, 64..4096, 2, 2, maxPixels, rate, 1..100_000_000, listOf(1, 8))

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

    @Test fun oddSizesMissTheAlignment() {
        assertTrue(report.check(ExportSettings(1080, 30, Codec.H264), 1081, 1920) is Support.Unsupported)
    }

    @Test fun jsonRoundTrip() {
        assertEquals(report, DeviceReport.fromJson(JSONObject(report.toJson().toString())))
        assertEquals(1080.0 * 1920 * 60, report.probe!!.pixelsPerSecond, 1.0)
    }
}
