package app.absentmotion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class BeatsTest {
    private val rate = 16000
    private val frameMs = 1000.0 / 30

    private fun track(seconds: Int, firstBeatMs: Double, seed: Long, hz: Double, decayMs: Double): Pair<FloatArray, List<Double>> {
        val pcm = FloatArray(seconds * rate)
        val random = Random(seed)
        for (i in pcm.indices) pcm[i] = (random.nextGaussian() * 0.02).toFloat()
        fun hit(ms: Double, amplitude: Double, hz: Double, decayMs: Double) {
            val start = (ms * rate / 1000).toInt()
            for (k in 0 until (decayMs * 5 * rate / 1000).toInt()) {
                val i = start + k
                if (i >= pcm.size) break
                val t = k.toDouble() / rate
                val carrier = if (hz > 0) sin(2 * PI * hz * t) else random.nextGaussian()
                pcm[i] += (amplitude * carrier * exp(-t * 1000 / decayMs)).toFloat()
            }
        }
        val beats = generateSequence(firstBeatMs) { it + 500.0 }.takeWhile { it < seconds * 1000 - 600 }.toList()
        beats.forEach { hit(it, 0.7, hz, decayMs) }
        beats.filterIndexed { i, _ -> i % 3 != 1 }.forEach { hit(it + 250, 0.25, 0.0, 12.0) }
        listOf(130.0, 760.0, 1390.0, 2230.0, 3170.0, 4410.0, 5650.0).forEach { hit(firstBeatMs + it, 0.25, 0.0, 12.0) }
        return pcm to beats
    }

    @Test
    fun marksTheBeatsOfA120BpmClickTrack() {
        for ((first, seed) in listOf(430.0 to 1L, 90.0 to 2L, 275.0 to 3L, 611.0 to 4L, 333.0 to 5L, 205.0 to 6L)) {
            val thump = seed % 2 == 1L
            val (pcm, truth) = track(30, first, seed, if (thump) 200.0 else 1000.0, if (thump) 40.0 else 10.0)
            val found = Beats.detect(pcm, rate)
            val missed = truth.filter { t -> found.none { abs(it - t) <= frameMs } }
            assertTrue("first beat $first: missed ${missed.size} of ${truth.size}: found ${found.take(8)}", missed.isEmpty())
            val extra = found.filter { f -> truth.none { abs(f - it) <= frameMs } }
            assertTrue("first beat $first: extra beats $extra", extra.isEmpty())
        }
    }

    @Test
    fun silenceHasNoBeats() {
        assertTrue(Beats.detect(FloatArray(10 * rate), rate).isEmpty())
    }

    @Test
    fun chunkedAnalysisGivesTheSameBeatsAsOneBuffer() {
        val (pcm, _) = track(30, 430.0, 1L, 200.0, 40.0)
        val whole = Beats.detect(pcm, rate)
        assertTrue(whole.size > 40)
        for (size in listOf(1, 367, 368, 369, 511, 512, 513, 4096, 16000, 100_003)) {
            val stream = Beats.Stream(rate)
            var at = 0
            while (at < pcm.size) {
                val count = minOf(size, pcm.size - at)
                stream.add(pcm.copyOfRange(at, at + count), count)
                at += count
            }
            assertEquals("chunks of $size", whole, stream.beats())
        }
    }

    @Test
    fun aChunkMayBeOnlyPartlyUsed() {
        val (pcm, _) = track(20, 90.0, 2L, 1000.0, 10.0)
        val stream = Beats.Stream(rate)
        val padded = FloatArray(5000)
        var at = 0
        while (at < pcm.size) {
            val count = minOf(3000, pcm.size - at)
            System.arraycopy(pcm, at, padded, 0, count)
            padded.fill(9f, count, padded.size)
            stream.add(padded, count)
            at += count
        }
        assertEquals(Beats.detect(pcm, rate), stream.beats())
    }
}
