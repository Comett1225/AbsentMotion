package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.sceneXml
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

    @Test fun aLoopingClipPlaysOnAtItsLoopPointLikeTheExport() {
        val rate = 48_000
        val block = 1024
        val dt = 1000.0 / rate
        val clip = AudioClip.of(sceneXml("""<audio id="1" src="tone" startTime="0" endTime="5999" inTime="1000" outTime="2500" loop="true"/>""")).single()
        val export = OfflineMix({ ToneSource(440.0) }, listOf(clip), Resampler.ULTRA, { _, _ -> null }, FakeFx(), rate).use { it.floats(0, 5_500_000) }
        val stream = LayerStream("tone", clip.mediaAt(0.0) - LayerStream.LEAD_MS, clip.inMs) { ToneSource(440.0) }
        val rack = FxRack(rate) { _, _ -> null }
        val out = FloatArray(2 * block)
        var wraps = 0
        try {
            for (i in 0 until export.size / 2 / block) {
                val t0 = i * block * dt
                val wrapping = clip.span(t0, block, dt)!!.to < block
                if (wrapping) wraps++ else awaitCondition("sound at $t0 ms") { stream.prepare(clip.wrap(clip.mediaAt(t0))) }
                mixStreams(listOf(clip), out, block, t0, rate, Resampler.ULTRA, rack) { stream }
                for (k in 0 until 2 * block) assertEquals("block $i at ${t0.toInt()} ms", export[2 * i * block + k], out[k], 1e-4f)
            }
        } finally {
            stream.close()
        }
        assertEquals(3, wraps)
    }
}
