package app.absentmotion.audio

import app.absentmotion.awaitCondition
import app.absentmotion.sceneXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LayerStreamTest {
    @Test fun aLatePcmPacketCanStillCompleteTheCurrentBlock() {
        val release = CountDownLatch(1)
        val tone = ToneSource(440.0)
        val source = object : PcmSource by tone {
            private var chunks = 0
            override fun next(): PcmChunk? {
                if (chunks++ == 1) check(release.await(3, TimeUnit.SECONDS))
                return tone.next()
            }
        }
        val stream = LayerStream("late", 0.0) { source }
        val out = FloatArray(1024)
        val dt = 1000.0 / 48_000
        fun mix(deadline: Long) = stream.mix(out, 0, 512, 15.0, dt, 1f, 1f, Resampler.ULTRA, 15.0, dt, 3000.0, deadline)
        try {
            awaitCondition("first decoded packet", 3_000) { stream.prepare(4.0, 5.0) }
            assertFalse(mix(0))
            Thread { Thread.sleep(20); release.countDown() }.start()
            assertTrue(mix(System.nanoTime() + 1_000_000_000L))
            assertTrue(out.any { it != 0f })
        } finally {
            release.countDown()
            stream.close()
        }
    }

    @Test fun aFailedStreamIsDroppedBeforeTheNextPlayAndAHealthyOneIsKept() {
        val damaged = object : PcmSource {
            override val durationUs = 0L
            override fun seek(us: Long) {}
            override fun next(): PcmChunk? = throw IllegalStateException("damaged packet")
            override fun close() {}
        }
        val broken = LayerStream("broken", 0.0) { damaged }
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

    @Test fun playingFromTheStartBeforeTheFirstSoundIsDecodedGetsReadyInsteadOfSeekingAgain() {
        val tone = ToneSource(440.0)
        val slow = object : PcmSource by tone { override fun seek(us: Long) { Thread.sleep(5); tone.seek(us) } }
        val stream = LayerStream("tone", 1000.0 - LayerStream.LEAD_MS) { slow }
        try {
            awaitCondition("the stream to be ready at 0 ms", 5_000) { stream.prepare(0.0) }
        } finally {
            stream.close()
        }
    }

    private class ShortTone(private val endUs: Long) : PcmSource {
        private val tone = ToneSource(440.0)
        private var at = 0L
        override val durationUs = endUs
        override fun seek(us: Long) { at = us; tone.seek(us) }
        override fun next(): PcmChunk? {
            if (at >= endUs) return null
            return tone.next()?.also { at = it.ptsUs + it.frames * 1_000_000L / it.rate }
        }
        override fun close() {}
    }

    @Test fun startingPastTheEndOfTheSoundStaysSilentUntilTheLoopComesBack() {
        val stream = LayerStream("tone", 2000.0) { ShortTone(1_000_000) }
        try {
            awaitCondition("the empty tail to be ready") { stream.prepare(2000.0 + LayerStream.LEAD_MS) }
            assertFalse(stream.failed)
            val out = FloatArray(2048)
            awaitCondition("sound after the wrap") {
                stream.mix(out, 0, 1024, 100.0, 1000.0 / 48_000, 1f, 1f, Resampler.ULTRA, 0.0, 1000.0 / 48_000, 3000.0)
                stream.rate > 0
            }
            assertFalse(stream.failed)
        } finally {
            stream.close()
        }
        val clip = AudioClip.of(sceneXml("""<audio id="1" src="tone" startTime="0" endTime="5999" outTime="3000" loop="true"/>""")).single()
        val lost = ArrayList<String>()
        val export = OfflineMix({ ShortTone(1_000_000) }, listOf(clip), Resampler.ULTRA, { _, _ -> null }, FakeFx(), 48_000).use { mix ->
            mix.floats(1_500_000, 4_000_000).also { lost += mix.unreadable }
        }
        assertTrue(export.copyOfRange(2 * 48_000 * 2, 2 * 48_000 * 2 + 4800).any { it != 0f })
        assertTrue(lost.isEmpty())
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

    @Test fun aSoundInALoopingGroupPlaysOnAtEveryRepeatLikeTheExport() {
        val rate = 48_000
        val block = 1024
        val dt = 1000.0 / rate
        val scene = app.absentmotion.model.Scene.parse("""
            <scene width="100" height="100" fps="30"><embedScene id="1" startTime="0" endTime="11999">
            <scene width="100" height="100" fps="30" totalTime="4999" retime="loop">
            <audio id="2" src="tone" startTime="0" endTime="4999" outTime="20000"/>
            </scene></embedScene></scene>""")
        val clip = AudioClip.of(scene).single()
        val export = OfflineMix({ ToneSource(440.0) }, listOf(clip), Resampler.ULTRA, { _, _ -> null }, FakeFx(), rate).use { it.floats(0, 11_000_000) }
        val stream = LayerStream("tone", clip.cue(0.0).first - LayerStream.LEAD_MS) { ToneSource(440.0) }
        val rack = FxRack(rate) { _, _ -> null }
        val out = FloatArray(2 * block)
        var wraps = 0
        try {
            for (i in 0 until export.size / 2 / block) {
                val t0 = i * block * dt
                val wrapping = clip.span(t0, block, dt)!!.to < block
                if (wrapping) wraps++ else awaitCondition("sound at $t0 ms") { stream.prepare(clip.mediaAt(t0)) }
                mixStreams(listOf(clip), out, block, t0, rate, Resampler.ULTRA, rack) { stream }
                for (k in 0 until 2 * block) assertEquals("block $i at ${t0.toInt()} ms", export[2 * i * block + k], out[k], 1e-4f)
            }
        } finally {
            stream.close()
        }
        assertEquals(2, wraps)
    }

    @Test fun onlyClipsThatCanStillSoundAskForAudioFocus() {
        val clips = AudioClip.of(sceneXml(
            """<shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="early.mp4" s=".rect"/>""",
            """<shape id="2" startTime="0" endTime="4999" fillType="media" fillVideo="silent.mp4" s=".rect"/>""",
        ))
        val known = mapOf("early.mp4" to true, "silent.mp4" to false)
        assertTrue(clips.audibleAfter(0.0, known::get))
        assertFalse(clips.audibleAfter(2000.0, known::get))
        assertTrue(clips.audibleAfter(2000.0) { null })
    }

    @Test fun aGifClipMarkedSilentOpensNoSound() {
        val gif = """<media uri="gif.mp4" type="video/mp4" duration="2000000" audio="none"/><shape id="1" startTime="0" endTime="1999" fillType="media" fillVideo="gif.mp4" s=".rect"/>"""
        assertTrue(AudioClip.of(sceneXml(gif)).isEmpty())
        assertEquals(1, AudioClip.of(sceneXml(gif.replace(" audio=\"none\"", ""))).size)
    }
}
