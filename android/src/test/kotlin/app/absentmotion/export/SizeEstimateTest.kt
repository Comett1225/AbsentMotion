package app.absentmotion.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizeEstimateTest {
    @Test fun longSoundIsSampledInSpreadSecondsAndShortSoundWhole() {
        assertNull(audioSampleStarts(12_999_999))
        assertArrayEquals(longArrayOf(1, 3, 6, 8, 11, 13).map { it * 1_000_000L }.toLongArray(), audioSampleStarts(15_000_000))
        val seen = ArrayList<Pair<Long, Long>>()
        val joined = windowedMix({ from, to -> seen += from to to; ShortArray(((to - from) / 1000).toInt()) }, longArrayOf(5_000_000, 9_000_000))
        assertEquals(1500, joined(500_000, 2_000_000).size)
        assertEquals(listOf(5_500_000L to 6_000_000L, 9_000_000L to 10_000_000L), seen)
    }

    @Test fun wavSizeIsExact() {
        val s = ExportSettings(360, 30, audioFormat = AudioFormat.Wav24, audio = AudioSettings(sampleRate = 48000, channels = 1))
        assertEquals(44L + 48000 * 3 * 10, wavBytes(s, 10_000_000))
        assertEquals(44L + 44100 * 2 * 2 * 2, wavBytes(ExportSettings(360, 30, audioFormat = AudioFormat.Wav16), 2_000_000))
    }

    @Test fun learnedSizesNeverMixFormatsOrTheFallbackFormula() {
        val video = ExportSettings(1080, 30)
        val keys = listOf(
            ExportSizes.key(ExportKind.Video, video, true), ExportSizes.key(ExportKind.Video, video, false),
            ExportSizes.key(ExportKind.Video, video.copy(codec = Codec.Hevc), true), ExportSizes.key(ExportKind.Video, video.copy(container = Container.Mkv), true),
            ExportSizes.key(ExportKind.Sequence, video, true), ExportSizes.key(ExportKind.Sequence, video.copy(image = ImageKind.Jpeg), true),
            ExportSizes.key(ExportKind.Audio, video.copy(audioFormat = AudioFormat.Mp3), true), ExportSizes.key(ExportKind.Audio, video.copy(audioFormat = AudioFormat.Ogg), true),
            ExportSizes.key(ExportKind.Gif, video, true),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(ExportSizes.key(ExportKind.Video, video.copy(container = Container.WebM), true), ExportSizes.key(ExportKind.Video, video, true))
        listOf(ExportKind.Frame, ExportKind.FrameAs, ExportKind.Package, ExportKind.Xml).forEach { assertNull(ExportSizes.key(it, video, true)) }
        assertNull(ExportSizes.key(ExportKind.Audio, video.copy(audioFormat = AudioFormat.Wav16), true))
    }
}
