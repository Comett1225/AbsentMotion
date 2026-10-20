package app.absentmotion.export

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GifEstimateTest {
    @Test fun flatAndNoisyFramesGetEstimatesFromTheirEncoding() {
        val w = 64
        val h = 64
        val flat = ByteArray(w * h * 4) { if (it % 4 == 0 || it % 4 == 3) 255.toByte() else 0 }
        val random = Random(17)
        val noisy = ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else random.nextInt(256).toByte() }
        val flatSize = gifSampleEstimate(w, h, 50, listOf(flat)).bytes
        val noisySize = gifSampleEstimate(w, h, 50, listOf(noisy)).bytes
        assertTrue("flat=$flatSize noisy=$noisySize", flatSize < noisySize / 3)
        val out = ByteArrayOutputStream()
        GifWriter(out, w, h).apply { repeat(50) { frame(flat, (it + 1) * 100L) }; finish() }
        assertEquals(out.size().toLong(), flatSize)
        val mixed = gifSampleEstimate(w, h, 50, listOf(flat, noisy))
        assertTrue("flat=$flatSize mixed=${mixed.bytes} noisy=$noisySize", mixed.bytes in (flatSize + 1) until noisySize)
        assertEquals((flatSize + noisySize) / 2.0, mixed.bytes.toDouble(), 50.0)
        assertEquals(2, mixed.encodeMs.size)
        assertTrue(mixed.encodeMs.all { it >= 0.0 })
    }

    @Test fun gifTimeIsTheRenderTimePlusTheEncodingOfEveryFrame() {
        val sample = GifSample(1000, encodeMs = listOf(400.0, 40.0, 50.0, 45.0, 60.0), renderMs = listOf(900.0, 10.0, 12.0, 11.0, 9.0))
        assertEquals(1_200L + 100 * 50, gifTimeMs(100, sample, renderMs = 1_200))
        assertEquals(1_200L + 300 * 50, gifTimeMs(300, sample, renderMs = 1_200))
        assertEquals(DefaultSetupMs + 100 * 11 + 100 * 50, gifTimeMs(100, sample, renderMs = null))
    }

    @Test fun oneSlowSampleDoesNotDecideTheEstimate() {
        val cold = GifSample(1000, encodeMs = listOf(3_000.0, 30.0, 31.0))
        assertEquals(500L + 600 * 31, gifTimeMs(600, cold, 500))
        assertEquals(500L + 600 * 31, gifTimeMs(600, GifSample(1000, encodeMs = listOf(31.0)), 500))
    }
}
