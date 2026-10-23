package app.absentmotion.audio

import app.absentmotion.awaitCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStreamTest {
    @Test fun aFailedStreamIsDroppedBeforeTheNextPlayAndAHealthyOneIsKept() {
        val silent = object : PcmSource {
            override val durationUs = 0L
            override fun seek(us: Long) {}
            override fun next(): PcmChunk? = null
            override fun close() {}
        }
        val broken = LayerStream("broken", 0.0) { silent }
        val healthy = LayerStream("healthy", 0.0) { ToneSource(440.0) }
        try {
            awaitCondition("the broken stream to fail") { broken.failed }
            awaitCondition("the healthy stream to be ready") { healthy.prepare(100.0) }
            val streams = mutableMapOf(1L to broken, 2L to healthy)
            streams.dropFailed()
            assertEquals(listOf(2L), streams.keys.toList())
            assertTrue(!healthy.failed)
        } finally {
            broken.close()
            healthy.close()
        }
    }
}
