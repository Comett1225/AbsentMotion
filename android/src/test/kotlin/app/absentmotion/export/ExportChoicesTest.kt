package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.absentmotion.media.videoSamples

class ExportChoicesTest {
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
    )

    private val withOpus = DeviceReport("k", report.encoders + opus)

    private val check: (ExportSettings) -> Support = { s -> report.check(s, s.shortSide, (s.shortSide * 16 / 9 + 1) and 1.inv()) }

    private val checkWithOpus: (ExportSettings) -> Support = { s -> withOpus.check(s, s.shortSide, (s.shortSide * 16 / 9 + 1) and 1.inv()) }

    private val encoders: (String) -> Boolean = { mime -> report.encodersFor(mime).isNotEmpty() }

    @Test fun audioSummariesReadLikeTheRow() {
        val stereo = txt(R.string.export_channels_stereo)
        val mono = txt(R.string.export_channels_mono)
        assertEquals(txt(R.string.export_audio_summary, "AAC", "128", "44.1 kHz", stereo), audioSummary(AudioSettings()))
        assertEquals(txt(R.string.export_audio_summary, "Opus", "96", "48 kHz", mono), audioSummary(AudioSettings(AudioCodec.Opus, 96, 44100, 1)))
        assertEquals(txt(R.string.export_audio_summary_lossless, txt(R.string.export_audio_wav24), "48 kHz", stereo),
            audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Wav24, audio = AudioSettings(sampleRate = 48000))))
        assertEquals(txt(R.string.export_audio_summary, txt(R.string.export_audio_mp3), "192", "44.1 kHz", stereo),
            audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Mp3, audio = AudioSettings(bitrateKbps = 192))))
        assertEquals(txt(R.string.export_audio_summary, txt(R.string.export_audio_ogg), "128", "48 kHz", mono),
            audioOnlySummary(ExportSettings(360, 30, audioFormat = AudioFormat.Ogg, audio = AudioSettings(channels = 1))))
    }

    @Test fun codecsThePhoneCannotEncodeAreGreyedWithTheReason() {
        val choices = codecChoices(ExportSettings(1080, 30), check).associate { it.value to it.reason }
        assertEquals(listOf(Codec.H264, Codec.Hevc, Codec.Av1, Codec.Apv), choices.keys.toList())
        assertNull(choices[Codec.H264])
        assertNull(choices[Codec.Av1])
        assertTrue(choices[Codec.Apv] != null)
    }

    @Test fun resolutionsTooLargeForTheCodecAreGreyed() {
        val choices = resolutionChoices(ExportSettings(1080, 30), check).associate { it.value.shortSide to it.enabled }
        assertTrue(choices.getValue(1080))
        assertFalse(choices.getValue(2160))
        assertTrue(resolutionChoices(ExportSettings(1080, 30, Codec.Hevc), check).first { it.value.shortSide == 2160 }.enabled)
    }

    @Test fun containersNeedACodecThePhoneHas() {
        val choices = containerChoices(ExportSettings(1080, 30), checkWithOpus).associate { it.value to it.reason }
        assertNull(choices[Container.Mp4])
        assertNull(choices[Container.Mkv])
        assertNull(choices[Container.WebM])
        val noVp9OrAv1 = DeviceReport("k", listOf(video("video/avc", true), aac, opus))
        val webm = containerChoices(ExportSettings(1080, 30)) { noVp9OrAv1.check(it, 1080, 1920) }.first { it.value == Container.WebM }
        assertEquals(txt(R.string.export_reason_container_codecs, "WebM", txt(R.string.export_list_or, "VP9", "AV1"), "1080p"), webm.reason)
        val mkv = containerChoices(ExportSettings(1080, 30)) { Support.Unsupported(txt(R.string.export_block_unsupported), null) }.first { it.value == Container.Mkv }
        assertEquals(txt(R.string.export_reason_container_codecs, "MKV",
            txt(R.string.export_list_or, txt(R.string.export_list_or, txt(R.string.export_list_or, "H.264", "H.265"), "AV1"), "VP9"), "1080p"), mkv.reason)
    }

    @Test fun switchingContainerKeepsOrReplacesTheCodecAndAudio() {
        val mp4 = ExportSettings(1080, 30, Codec.H264)
        assertEquals(mp4.copy(container = Container.Mkv), withContainer(mp4, Container.Mkv, checkWithOpus))
        val webm = withContainer(mp4, Container.WebM, checkWithOpus)
        assertEquals(Codec.Av1, webm.codec)
        assertEquals(AudioCodec.Opus, webm.audio.codec)
        assertEquals(AudioCodec.Aac, withContainer(webm, Container.Mov, checkWithOpus).audio.codec)
    }

    @Test fun aPhoneWithoutAnOpusEncoderCannotPickWebMOrKeepOpusInMkv() {
        val mp4 = ExportSettings(1080, 30, Codec.H264)
        val choices = containerChoices(mp4, check).associate { it.value to it.reason }
        assertNull(choices[Container.Mp4])
        assertNull(choices[Container.Mkv])
        assertEquals(txt(R.string.export_support_no_encoder, "Opus"), choices[Container.WebM])
        val opusMkv = mp4.copy(container = Container.Mkv, audio = AudioSettings(AudioCodec.Opus))
        assertEquals(opusMkv.copy(audio = AudioSettings(AudioCodec.Aac)), withContainer(opusMkv, Container.Mkv, check))
        val support = check(opusMkv)
        assertTrue(support is Support.Unsupported)
        assertEquals(opusMkv.copy(audio = AudioSettings(AudioCodec.Aac)), betterChoice(opusMkv, support, check))
        assertEquals(txt(R.string.export_block_unsupported), exportBlock(StorageStatus.Enough, support))
        val codecs = codecChoices(opusMkv, check).associate { it.value to it.enabled }
        assertEquals(mapOf(Codec.H264 to true, Codec.Hevc to true, Codec.Av1 to true, Codec.Vp9 to false), codecs)
        assertTrue(resolutionChoices(opusMkv, check).first { it.value.shortSide == 1080 }.enabled)
    }

    @Test fun audioOptionsExplainContainerAndPhoneLimits() {
        val mp4 = audioCodecChoices(Container.Mp4, encoders).associate { it.value to it.reason }
        assertNull(mp4[AudioCodec.Aac])
        assertEquals(txt(R.string.export_reason_audio_container, "MP4", "Opus", txt(R.string.export_list_and, "WebM", "MKV")), mp4[AudioCodec.Opus])
        assertEquals(txt(R.string.export_support_no_encoder, "Opus"), audioCodecChoices(Container.Mkv, encoders).first { it.value == AudioCodec.Opus }.reason)
        assertEquals(listOf(false, true), sampleRateChoices("Opus").map { it.enabled })
        assertEquals(txt(R.string.export_reason_only_48k, "Opus"), sampleRateChoices("Opus").first().reason)
        assertTrue(sampleRateChoices(null).all { it.enabled })
        assertTrue(bitrateChoices(AudioFormat.Flac).none { it.enabled })
        assertTrue(bitrateChoices(AudioFormat.Mp3).all { it.enabled })
        val formats = audioFormatChoices(encoders).associate { it.value to it.reason }
        assertEquals(txt(R.string.export_support_no_encoder, txt(R.string.export_audio_flac)), formats[AudioFormat.Flac])
        assertEquals(txt(R.string.export_reason_lossless, txt(R.string.export_audio_flac)), bitrateChoices(AudioFormat.Flac).first().reason)
        assertNull(formats[AudioFormat.Mp3])
        assertNull(formats[AudioFormat.Wav16])
        assertNull(formats[AudioFormat.M4a])
    }

    @Test fun slowSoftwareCodecWarnsAndSuggestsAHardwareOne() {
        val av1 = ExportSettings(1080, 60, Codec.Av1)
        val support = check(av1)
        assertTrue(support is Support.SlowSoftwareOnly)
        assertEquals(txt(R.string.export_warning_slow, "AV1", "1080p", "60"), codecWarning(av1, support))
        val better = betterChoice(av1, support, check)
        assertEquals(av1.copy(codec = Codec.H264), better)
        assertEquals(txt(R.string.export_use, "H.264"), suggestionLabel(av1, better!!))
        assertNull(betterChoice(better, check(better), check))
        assertNull(codecWarning(better, check(better)))
    }

    @Test fun unsupportedSettingsUseTheCheckSuggestionAndBlockTheButton() {
        val big = ExportSettings(2160, 30, Codec.H264)
        val support = check(big)
        assertTrue(support is Support.Unsupported)
        assertEquals(txt(R.string.export_use, "H.265"), suggestionLabel(big, betterChoice(big, support, check)!!))
        assertEquals(txt(R.string.export_warning_unsupported, (support as Support.Unsupported).reason), codecWarning(big, support))
        assertEquals(txt(R.string.export_block_unsupported), exportBlock(StorageStatus.Enough, support))
        assertEquals(txt(R.string.export_block_storage), exportBlock(StorageStatus.NotEnough(txt(R.string.library_text_value, "x")), Support.Supported))
        assertNull(exportBlock(StorageStatus.Low(txt(R.string.library_text_value, "x")), Support.SlowSoftwareOnly(txt(R.string.library_text_value, "x"))))
        assertEquals(txt(R.string.export_use, "720p · MKV"), suggestionLabel(big, big.copy(shortSide = 720, container = Container.Mkv)))
        assertEquals(txt(R.string.export_use_suggested), suggestionLabel(big, big))
    }

    @Test fun temperatureAndVerificationTexts() {
        assertEquals(txt(R.string.export_heat_normal), thermalLabel(ThermalLevel.Normal))
        assertEquals(txt(R.string.export_heat_hot), thermalLabel(ThermalLevel.Hot))
        assertEquals(ThermalLevel.entries.size, ThermalLevel.entries.map(::thermalLabel).toSet().size)
        assertNull(exportHeatText(ThermalLevel.Warm))
        assertEquals(txt(R.string.export_heat_text_hot), exportHeatText(ThermalLevel.Hot))
        assertEquals(txt(R.string.export_checked_ok), verificationLine(Verification(emptyList(), 1, true, true, 5)))
        val problems = listOf(txt(R.string.export_verify_no_video), txt(R.string.export_verify_last_frame))
        assertEquals(txt(R.string.export_checked_problems, problems), verificationLine(Verification(problems, 1, null, null, 5)))
    }

    @Test fun shortLabels() {
        assertEquals(listOf("H.264", "H.265", "AV1", "VP9", "APV"), Codec.entries.map { it.shortName })
        assertEquals("4K", sizeLabel(2160))
        assertEquals("44.1 kHz", khz(44100))
        assertEquals("48 kHz", khz(48000))
    }

    @Test fun exportingAnywayMarksTheMissingFilesSoTheirVideosAreSkipped() {
        val scene = app.absentmotion.model.Scene.parse("""<scene width="100" height="100" fps="30"><media uri="gone" filename="a.mp4"/><media uri="here"/>""" +
            """<shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="gone" s=".rect"/><shape id="2" startTime="0" endTime="999" fillType="media" fillVideo="nested" s=".rect"/></scene>""")
        val marked = scene.withMissing(setOf("gone", "nested"))
        assertTrue(marked.isMissing("gone"))
        assertTrue(marked.isMissing("nested"))
        assertFalse(marked.isMissing("here"))
        assertEquals("a.mp4", marked.media("gone")?.filename)
        assertEquals(scene.layers, marked.layers)
        assertEquals(2, scene.videoSamples(0, playing = false, warm = false).size)
        assertTrue(marked.videoSamples(0, playing = false, warm = false).isEmpty())
    }
}
