package app.absentmotion.ai

import android.media.MediaFormat
import app.absentmotion.audio.PcmChunk
import app.absentmotion.audio.PcmSource
import app.absentmotion.audio.ToneSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AiSoundTest {
    private class Source(private val frames: List<Int>, private val rate: Int) : PcmSource {
        private var index = 0
        private var value = 1
        private val chunk = PcmChunk()
        override val durationUs = -1L
        override fun seek(us: Long) = Unit
        override fun close() = Unit

        override fun next(): PcmChunk? {
            if (index >= frames.size) return null
            val count = frames[index++]
            chunk.pcm = ShortArray(count * 2 + 8) { -1 }
            for (f in 0 until count) {
                chunk.pcm[2 * f] = value.toShort()
                chunk.pcm[2 * f + 1] = (-value).toShort()
                value++
            }
            chunk.frames = count
            chunk.rate = rate
            chunk.ptsUs = 0
            return chunk
        }
    }

    private fun first(source: Source, ptsUs: Long = 0): PcmChunk = source.next()!!.also { it.ptsUs = ptsUs }

    private fun left(pcm: ShortArray) = IntArray(pcm.size / 2) { pcm[2 * it].toInt() }

    @Test fun theFeedGivesExactlyTheFramesAskedForAcrossChunkEdges() {
        val source = Source(listOf(5, 3, 7), 1000)
        val feed = PcmFeed(source, first(source), 1000)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), left(feed.read(4)))
        assertArrayEquals(intArrayOf(5, 6, 7, 8, 9, 10), left(feed.read(6)))
        val rest = feed.read(8)
        assertEquals(8, rest.size / 2)
        assertArrayEquals(intArrayOf(11, 12, 13, 14, 15, 0, 0, 0), left(rest))
        assertTrue(feed.read(3).all { it.toInt() == 0 })
        assertEquals(6, feed.read(3).size)
    }

    @Test fun theRightChannelFollowsTheLeftOne() {
        val source = Source(listOf(2), 1000)
        val feed = PcmFeed(source, first(source), 1000)
        assertArrayEquals(shortArrayOf(1, -1, 2, -2), feed.read(2))
    }

    @Test fun soundThatStartsLateIsPaddedWithSilence() {
        val source = Source(listOf(4), 1000)
        val feed = PcmFeed(source, first(source, ptsUs = 3_000), 1000)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 2, 3, 4, 0), left(feed.read(8)))
    }

    @Test fun blocksFromTheEncoderMapToWholeFramesWithoutDrift() {
        for (rate in listOf(44_100, 48_000, 22_050, 11_025)) for (want in listOf(1001, 22_050 / 2, 777)) {
            val source = Source(List(40) { 4410 }, rate)
            val feed = PcmFeed(source, first(source), rate)
            var fed = 0L
            repeat(60) {
                val block = feed.mix(fed * 1_000_000 / rate, (fed + want) * 1_000_000 / rate)
                assertEquals("$rate Hz, block $it of $want", want, block.size / 2)
                fed += want
            }
        }
    }

    @Test fun aChangedSampleRateIsRefused() {
        val start = Source(listOf(2), 1000).let { first(it) }
        val other = Source(listOf(2, 2), 2000)
        val feed = PcmFeed(other, start, 1000)
        feed.read(2)
        assertTrue(runCatching { feed.read(2) }.isFailure)
    }

    @Test fun convertedSoundStopsWhereTheClipStops() {
        assertEquals(10_000_000L, soundEndUs(60_000_000L, 10_000_000L))
        assertEquals(8_000_000L, soundEndUs(8_000_000L, 10_000_000L))
        assertEquals(10_000_000L, soundEndUs(-1L, 10_000_000L))
        assertEquals(8_000_000L, soundEndUs(8_000_000L, -1L))
        assertEquals(300_000_000L, soundEndUs(-1L, 0L))
    }

    @Test fun onlyTrackTheMp4WriterTakesAreCopiedAsTheyAre() {
        for (mime in listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB)) assertTrue(mime, soundIsCopied(mime))
        for (mime in listOf(MediaFormat.MIMETYPE_AUDIO_MPEG, MediaFormat.MIMETYPE_AUDIO_VORBIS, MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_RAW,
            MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_AC3)) assertFalse(mime, soundIsCopied(mime))
    }

    @Test fun soundIsEncodedAtARateTheAacEncoderTakes() {
        assertEquals(44_100, aacRate(88_200))
        assertEquals(48_000, aacRate(96_000))
        assertEquals(44_100, aacRate(176_400))
        assertEquals(48_000, aacRate(192_000))
        assertEquals(48_000, aacRate(64_000))
        assertEquals(48_000, aacRate(48_000))
        assertEquals(22_050, aacRate(22_050))
    }

    @Test fun soundAbove48kHzIsResampledInTimeAndInTune() {
        val tone = ToneSource(1000.0, 96_000)
        val feed = PcmFeed(tone, tone.next()!!, 96_000)
        val out = Downsampled(feed::read, 96_000, 48_000).mix(0, 1_000_000)
        assertEquals(2 * 48_000, out.size)
        for (i in 64 until 48_000) assertEquals("frame $i", 0.5 * sin(2 * PI * 1000.0 * i / 48_000) * 32767, out[2 * i].toDouble(), 330.0)
    }
}
