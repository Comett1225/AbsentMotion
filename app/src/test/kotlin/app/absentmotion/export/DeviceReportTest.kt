package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {
    private fun video(mime: String, hardware: Boolean, maxPixels: Long = 1920L * 1088, rate: Double = 1920.0 * 1088 * 60) =
        EncoderCaps("enc.$mime.$hardware", mime, hardware, 64..4096, 64..4096, 2, 2,
            ProbeSizes.filter { (w, h) -> w.toLong() * h <= maxPixels }.map { (w, h) -> SizePoint(w, h, rate / (w.toDouble() * h)) },
            1..100_000_000, listOf(1, 8))

    private val aac = EncoderCaps("aac", "audio/mp4a-latm", false, bitrates = 8000..960000, sampleRates = listOf(44100, 48000), maxChannels = 6)

    private val opus = EncoderCaps("opus", "audio/opus", false, bitrates = 6000..510000, sampleRates = listOf(48000), maxChannels = 2)

    private val report = DeviceReport(
        "k",
        listOf(
            video("video/avc", true),
            video("video/hevc", true, 3840L * 2160, 3840.0 * 2160 * 30),
            video("video/av01", false),
            aac,
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
        val tall = DeviceReport("k", listOf(video("video/avc", true, 1088L * 2400), aac))
        assertEquals(Support.Supported, tall.check(ExportSettings(1080, 30, Codec.H264), 1080, 2400))
        assertEquals(Support.Supported, tall.check(ExportSettings(1080, 30, Codec.H264), 2340, 1080))
    }

    @Test fun portraitAndLandscapeAreCheckedSeparately() {
        val landscapeOnly = DeviceReport("k", listOf(EncoderCaps("av1", "video/av01", false, 2..1920, 2..1920, 1, 1, listOf(SizePoint(1920, 1080, 30.0))), aac))
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

    @Test fun soundThePhoneCannotEncodeBlocksTheExportAndSuggestsAac() {
        val webm = ExportSettings(1080, 30, Codec.Av1, container = Container.WebM, audio = AudioSettings(AudioCodec.Opus))
        val refused = report.check(webm, 1080, 1920) as Support.Unsupported
        assertTrue(refused.sound)
        assertEquals(txt(R.string.export_support_no_encoder, "Opus"), refused.reason)
        assertEquals(webm.copy(container = Container.Mp4, audio = AudioSettings(AudioCodec.Aac)), refused.suggestion)
        assertTrue(report.check(webm, 1080, 1920, hasAudio = false) is Support.SlowSoftwareOnly)
        assertTrue(DeviceReport("k", report.encoders + opus).check(webm, 1080, 1920) is Support.SlowSoftwareOnly)
        val mkv = ExportSettings(1080, 30, Codec.H264, container = Container.Mkv, audio = AudioSettings(AudioCodec.Opus))
        val kept = report.check(mkv, 1080, 1920) as Support.Unsupported
        assertEquals(mkv.copy(audio = AudioSettings(AudioCodec.Aac)), kept.suggestion)
        val vp9 = ExportSettings(1080, 30, Codec.Vp9, container = Container.WebM, audio = AudioSettings(AudioCodec.Opus))
        val withVp9 = DeviceReport("k", report.encoders + video("video/x-vnd.on2.vp9", false))
        assertEquals(vp9.copy(container = Container.Mkv, audio = AudioSettings(AudioCodec.Aac)), (withVp9.check(vp9, 1080, 1920) as Support.Unsupported).suggestion)
        assertTrue(report.check(vp9, 1080, 1920) is Support.Unsupported && !(report.check(vp9, 1080, 1920) as Support.Unsupported).sound)
    }

    @Test fun oddSizesMissTheAlignment() {
        assertTrue(report.check(ExportSettings(1080, 30, Codec.H264), 1081, 1920) is Support.Unsupported)
    }

    @Test fun jsonRoundTrip() {
        assertEquals(report, DeviceReport.fromJson(JSONObject(report.toJson().toString())))
        assertEquals(1080.0 * 1920 * 60, report.probe!!.pixelsPerSecond, 1.0)
        val android13 = report.copy(sdk = 33)
        assertEquals(android13, DeviceReport.fromJson(JSONObject(android13.toJson().toString())))
    }

    @Test fun aCustomSizeTheSparseProbesMissIsConfirmedExactly() {
        val wide = EncoderCaps("enc.avc", "video/avc", true, 64..4096, 64..2160, 2, 2,
            ProbeSizes.filter { (w, h) -> w <= 3840 && h <= 2160 }.map { (w, h) -> SizePoint(w, h, 30.0) }, 1..100_000_000)
        val device = DeviceReport("k", listOf(wide, aac))
        assertTrue(device.check(ExportSettings(720, 30), 4096, 720) is Support.Unsupported)
        assertEquals(listOf(wide), device.unconfirmed(4096, 720))
        assertEquals(emptyList<EncoderCaps>(), device.unconfirmed(1920, 1080))
        assertEquals(emptyList<EncoderCaps>(), device.unconfirmed(5000, 720))
        val confirmed = device.withSizes(mapOf("enc.avc video/avc" to SizePoint(4096, 720, 60.0)))
        assertEquals(Support.Supported, confirmed.check(ExportSettings(720, 30), 4096, 720))
        assertEquals(emptyList<EncoderCaps>(), confirmed.unconfirmed(4096, 720))
    }

    @Test fun soundSettingsTheEncoderRefusesAreCaughtWithAFix() {
        val narrow = EncoderCaps("aac.hw", "audio/mp4a-latm", true, bitrates = 64_000..192_000, sampleRates = listOf(48000), maxChannels = 2)
        val device = DeviceReport("k", listOf(video("video/avc", true), narrow))
        val s = ExportSettings(1080, 30, audio = AudioSettings(AudioCodec.Aac, 320, 44100, 2))
        val refused = device.check(s, 1080, 1920) as Support.Unsupported
        assertTrue(refused.sound)
        assertEquals(txt(R.string.export_support_audio_settings, "AAC"), refused.reason)
        assertEquals(AudioSettings(AudioCodec.Aac, 192, 48000, 2), refused.suggestion?.audio)
        assertEquals(Support.Supported, device.check(refused.suggestion!!, 1080, 1920))
        assertEquals(Support.Supported, device.check(s, 1080, 1920, hasAudio = false))
        assertEquals(Support.Supported, report.check(s, 1080, 1920))
    }

    @Test fun mp4WithAv1OrApvNeedsTheAndroidVersionWhoseMuxerWritesIt() {
        val all = DeviceReport("k", listOf(video("video/avc", true), video("video/av01", true), video("video/apv", true), video("video/x-vnd.on2.vp9", true), aac, opus))
        val av1 = ExportSettings(1080, 30, Codec.Av1)
        assertEquals(Support.Supported, all.check(av1, 1080, 1920))
        val old = all.copy(sdk = 33)
        val refused = old.check(av1, 1080, 1920) as Support.Unsupported
        assertEquals(txt(R.string.export_problem_android, "MP4", "AV1", "14"), refused.reason)
        assertEquals(Container.WebM, refused.suggestion?.container)
        assertEquals(Codec.Av1, refused.suggestion?.codec)
        val apv = old.copy(sdk = 35).check(ExportSettings(1080, 30, Codec.Apv), 1080, 1920) as Support.Unsupported
        assertEquals(txt(R.string.export_problem_android, "MP4", "APV", "16"), apv.reason)
        assertEquals(Codec.H264, apv.suggestion?.codec)
        assertEquals(Support.Supported, all.copy(sdk = 36).check(ExportSettings(1080, 30, Codec.Apv), 1080, 1920))
        assertEquals(Support.Supported, old.check(av1.copy(container = Container.Mkv), 1080, 1920))
    }

    @Test fun audioOnlyFormatsAreCheckedAgainstTheEncodersAndTheAndroidVersion() {
        val flac = EncoderCaps("flac", "audio/flac", false, bitrates = 1..21_000_000, sampleRates = listOf(44100, 48000), maxChannels = 2)
        val device = DeviceReport("k", listOf(aac, opus, flac), sdk = 30)
        assertNull(device.checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.Flac)))
        assertNull(device.checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.Ogg)))
        assertNull(device.checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.Wav24)))
        assertEquals(txt(R.string.export_problem_android, "Ogg", "Opus", "10"), device.copy(sdk = 28).checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.Ogg)))
        assertEquals(txt(R.string.export_support_no_encoder, "FLAC"), report.checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.Flac)))
        val mono = EncoderCaps("aac1", "audio/mp4a-latm", false, bitrates = 8000..960000, sampleRates = listOf(44100), maxChannels = 1)
        assertEquals(txt(R.string.export_support_audio_settings, "AAC"),
            DeviceReport("k", listOf(mono)).checkAudioOnly(ExportSettings(360, 30, audioFormat = AudioFormat.M4a)))
    }
}
