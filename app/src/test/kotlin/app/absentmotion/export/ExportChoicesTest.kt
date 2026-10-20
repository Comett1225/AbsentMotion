package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChoicesTest {
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
    )

    private val check: (ExportSettings) -> Support = { s -> report.check(s, s.shortSide, (s.shortSide * 16 / 9 + 1) and 1.inv()) }

    private val encoders: (String) -> Boolean = { mime -> report.encodersFor(mime).isNotEmpty() }

    @Test fun audioSummariesReadLikeTheRow() {
        assertEquals("AAC · 128 kbps · 44.1 kHz · Stereo", audioSummary(AudioSettings()))
        assertEquals("Opus · 96 kbps · 48 kHz · Mono", audioSummary(AudioSettings(AudioCodec.Opus, 96, 44100, 1)))
        assertEquals("WAV 24-bit · 48 kHz · Stereo", audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Wav24, audio = AudioSettings(sampleRate = 48000))))
        assertEquals("MP3 · 192 kbps · 44.1 kHz · Stereo", audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Mp3, audio = AudioSettings(bitrateKbps = 192))))
        assertEquals("OGG (Opus) · 128 kbps · 48 kHz · Mono", audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Ogg, audio = AudioSettings(channels = 1))))
    }

    @Test fun codecsThePhoneCannotEncodeAreGreyedWithTheReason() {
        val choices = codecChoices(ExportSettings(1080, 30), check).associate { it.value to it.reason }
        assertEquals(listOf(Codec.H264, Codec.Hevc, Codec.Av1, Codec.Apv), choices.keys.toList())
        assertNull(choices[Codec.H264])
        assertNull(choices[Codec.Av1])
        assertEquals("This phone has no APV encoder.", choices[Codec.Apv])
    }

    @Test fun resolutionsTooLargeForTheCodecAreGreyed() {
        val choices = resolutionChoices(ExportSettings(1080, 30), check).associate { it.value.shortSide to it.enabled }
        assertTrue(choices.getValue(1080))
        assertFalse(choices.getValue(2160))
        assertTrue(resolutionChoices(ExportSettings(1080, 30, Codec.Hevc), check).first { it.value.shortSide == 2160 }.enabled)
    }

    @Test fun containersNeedACodecThePhoneHas() {
        val choices = containerChoices(ExportSettings(1080, 30), check).associate { it.value to it.reason }
        assertNull(choices[Container.Mp4])
        assertNull(choices[Container.Mkv])
        assertNull(choices[Container.WebM])
        val noVp9OrAv1 = DeviceReport("k", listOf(video("video/avc", true)))
        val webm = containerChoices(ExportSettings(1080, 30)) { noVp9OrAv1.check(it, 1080, 1920) }.first { it.value == Container.WebM }
        assertEquals("WebM needs VP9 or AV1 video, which this phone cannot make at 1080p.", webm.reason)
    }

    @Test fun switchingContainerKeepsOrReplacesTheCodecAndAudio() {
        val mp4 = ExportSettings(1080, 30, Codec.H264)
        assertEquals(mp4.copy(container = Container.Mkv), withContainer(mp4, Container.Mkv, check))
        val webm = withContainer(mp4, Container.WebM, check)
        assertEquals(Codec.Av1, webm.codec)
        assertEquals(AudioCodec.Opus, webm.audio.codec)
        assertEquals(AudioCodec.Aac, withContainer(webm, Container.Mov, check).audio.codec)
    }

    @Test fun audioOptionsExplainContainerAndPhoneLimits() {
        val mp4 = audioCodecChoices(Container.Mp4, encoders).associate { it.value to it.reason }
        assertNull(mp4[AudioCodec.Aac])
        assertEquals("MP4 files cannot hold Opus audio. Opus works in WebM and MKV.", mp4[AudioCodec.Opus])
        assertEquals("This phone has no Opus encoder.", audioCodecChoices(Container.Mkv, encoders).first { it.value == AudioCodec.Opus }.reason)
        assertEquals(listOf(false, true), sampleRateChoices("Opus").map { it.enabled })
        assertEquals("Opus always uses 48 kHz.", sampleRateChoices("Opus").first().reason)
        assertTrue(sampleRateChoices(null).all { it.enabled })
        assertTrue(bitrateChoices(AudioFormat.Flac).none { it.enabled })
        assertTrue(bitrateChoices(AudioFormat.Mp3).all { it.enabled })
        val formats = audioFormatChoices(encoders).associate { it.value to it.reason }
        assertEquals("This phone has no FLAC encoder.", formats[AudioFormat.Flac])
        assertNull(formats[AudioFormat.Mp3])
        assertNull(formats[AudioFormat.Wav16])
        assertNull(formats[AudioFormat.M4a])
    }

    @Test fun slowSoftwareCodecWarnsAndSuggestsAHardwareOne() {
        val av1 = ExportSettings(1080, 60, Codec.Av1)
        val support = check(av1)
        assertTrue(support is Support.SlowSoftwareOnly)
        assertEquals(
            "Your device does not support hardware AV1 encoding at 1080p 60fps. Software encoding may be available, but exporting could take significantly longer.",
            codecWarning(av1, support),
        )
        val better = betterChoice(av1, support, check)
        assertEquals(av1.copy(codec = Codec.H264), better)
        assertEquals("Use H.264", suggestionLabel(av1, better!!))
        assertNull(betterChoice(better, check(better), check))
        assertNull(codecWarning(better, check(better)))
    }

    @Test fun unsupportedSettingsUseTheCheckSuggestionAndBlockTheButton() {
        val big = ExportSettings(2160, 30, Codec.H264)
        val support = check(big)
        assertTrue(support is Support.Unsupported)
        assertEquals("Use H.265", suggestionLabel(big, betterChoice(big, support, check)!!))
        assertEquals("Not supported on this phone", exportBlock(StorageStatus.Enough, support))
        assertEquals("Not enough storage", exportBlock(StorageStatus.NotEnough("x"), Support.Supported))
        assertNull(exportBlock(StorageStatus.Low("x"), Support.SlowSoftwareOnly("x")))
        assertEquals("Use 720p · MKV", suggestionLabel(big, big.copy(shortSide = 720, container = Container.Mkv)))
    }

    @Test fun temperatureAndVerificationTexts() {
        assertEquals("Normal", thermalLabel(ThermalLevel.Normal))
        assertEquals("Hot — exports slowed", thermalLabel(ThermalLevel.Hot))
        assertNull(exportHeatText(ThermalLevel.Warm))
        assertEquals("Your phone is hot — export slowed down to protect it", exportHeatText(ThermalLevel.Hot))
        assertEquals("Checked: plays correctly", verificationLine(Verification(emptyList(), 1, true, true, 5)))
        assertEquals("Checked: The file has no video track.", verificationLine(Verification(listOf("The file has no video track."), 1, null, null, 5)))
    }

    @Test fun shortLabels() {
        assertEquals(listOf("H.264", "H.265", "AV1", "VP9", "APV"), Codec.entries.map { it.shortName })
        assertEquals("4K", sizeLabel(2160))
        assertEquals("44.1 kHz", khz(44100))
        assertEquals("48 kHz", khz(48000))
    }
}
