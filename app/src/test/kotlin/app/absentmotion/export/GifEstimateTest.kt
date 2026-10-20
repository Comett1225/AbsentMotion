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
        val flatSize = gifSampleEstimate(w, h, 50, listOf(flat))
        val noisySize = gifSampleEstimate(w, h, 50, listOf(noisy))
        assertTrue("flat=$flatSize noisy=$noisySize", flatSize < noisySize / 3)
        val out = ByteArrayOutputStream()
        GifWriter(out, w, h).apply { repeat(50) { frame(flat, (it + 1) * 100L) }; finish() }
        assertEquals(out.size().toLong(), flatSize)
        val mixed = gifSampleEstimate(w, h, 50, listOf(flat, noisy))
        assertTrue("flat=$flatSize mixed=$mixed noisy=$noisySize", mixed in (flatSize + 1) until noisySize)
        assertEquals((flatSize + noisySize) / 2.0, mixed.toDouble(), 50.0)
    }
}
