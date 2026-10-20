package app.absentmotion.audio

import app.absentmotion.model.Scene
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class ToneSource(private val hz: Double, private val rate: Int = 48_000, private val level: Double = 0.5) : PcmSource {
    override val durationUs = 30_000_000L
    private var at = 0L
    private val chunk = PcmChunk()

    override fun seek(us: Long) { at = us.coerceAtLeast(0) * rate / 1_000_000 / FRAME * FRAME }

    override fun next(): PcmChunk? {
        val n = minOf(FRAME.toLong(), durationUs * rate / 1_000_000 - at).toInt()
        if (n <= 0) return null
        if (chunk.pcm.size < 2 * n) chunk.pcm = ShortArray(2 * n)
        for (i in 0 until n) {
            val v = (level * sin(2 * PI * hz * (at + i) / rate) * 32767).roundToInt().toShort()
            chunk.pcm[2 * i] = v
            chunk.pcm[2 * i + 1] = v
        }
        chunk.frames = n
        chunk.rate = rate
        chunk.ptsUs = at * 1_000_000 / rate
        at += n
        return chunk
    }

    override fun close() {}

    private companion object { const val FRAME = 1024 }
}

internal open class FakeFx : ClipFx {
    override fun fits(spec: FxSpec) = true
    override fun set(values: FloatArray) {}
    override fun process(buf: FloatArray, frames: Int) {}
    override fun reset() {}
    override fun close() {}
}

internal class GainFx(private val gain: Float) : FakeFx() {
    override fun process(buf: FloatArray, frames: Int) { for (i in 0 until 2 * frames) buf[i] *= gain }
}

internal class EchoFx(private val delay: Int, private val feedback: Float) : FakeFx() {
    private val ring = FloatArray(2 * delay)
    private var at = 0

    override fun process(buf: FloatArray, frames: Int) {
        for (k in 0 until frames) {
            for (c in 0..1) {
                val y = buf[2 * k + c] + feedback * ring[2 * at + c]
                ring[2 * at + c] = y
                buf[2 * k + c] = y
            }
            at = (at + 1) % delay
        }
    }

    override fun reset() { ring.fill(0f); at = 0 }
}

class FxMixTest {
    private val withFx = """<audioEffects><fx type="eq"/></audioEffects>"""

    private fun audio(id: Int, start: Int, end: Int, fx: Boolean, extra: String = "") =
        """<audio id="$id" src="tone$id" startTime="$start" endTime="$end" outTime="30000" $extra>${if (fx) withFx else ""}</audio>"""

    private fun scene(vararg layers: String) = Scene.parse("""<scene width="100" height="100" fps="30">${layers.joinToString("")}</scene>""")

    private fun mix(scene: Scene, make: FxMaker) = OfflineMix({ uri -> ToneSource(if (uri == "tone1") 440.0 else 660.0) }, AudioClip.of(scene), Resampler.ULTRA, make)

    private fun render(scene: Scene, ms: Int, make: FxMaker): ShortArray = mix(scene, make).use { it(0, ms * 1000L) }

    private fun level(pcm: ShortArray, fromMs: Int, toMs: Int): Int {
        val rate = 44_100
        var peak = 0
        for (i in 2 * (fromMs * rate / 1000) until minOf(pcm.size, 2 * (toMs * rate / 1000))) peak = maxOf(peak, abs(pcm[i].toInt()))
        return peak
    }

    @Test fun scratchMixingEqualsTheDirectPathWhenTheChainDoesNothing() {
        val plain = render(scene(audio(1, 0, 2999, false), audio(2, 500, 2499, false)), 3000) { _, _ -> FakeFx() }
        val routed = render(scene(audio(1, 0, 2999, false), audio(2, 500, 2499, true)), 3000) { _, _ -> FakeFx() }
        val both = render(scene(audio(1, 0, 2999, true), audio(2, 500, 2499, true)), 3000) { _, _ -> FakeFx() }
        assertTrue(level(plain, 600, 2400) > 10_000)
        assertArrayEquals(plain, routed)
        assertArrayEquals(plain, both)
    }

    @Test fun withoutEffectsTheExportEqualsThePreviousMixer() {
        val half = { layer: String -> layer.replace("</audio>", """<gain value="0.5"/></audio>""") }
        val sc = scene(half(audio(1, 0, 2999, false, """speed="1.5"""")), half(audio(2, 0, 1999, false)), half(audio(3, 0, 2499, false, """loop="true" outTime="700"""")))
        val clips = AudioClip.of(sc)
        val now = OfflineMix({ uri -> ToneSource(if (uri == "tone1") 440.0 else 660.0) }, clips, Resampler.ULTRA, { _, _ -> null }, SafeLimiter(44_100)).use { it(0, 3_000_000) }
        val rate = 44_100
        val block = FloatArray(2048)
        val sources = HashMap<Long, OfflineSource>()
        val voices = HashMap<Long, Voice>()
        val before = ShortArray(2 * rate * 3)
        val dt = 1000.0 / rate
        for (i in 0 until (rate * 3 + 1023) / 1024) {
            block.fill(0f)
            val t0 = i * 1024 * dt
            for (clip in AudioClip.of(sc)) {
                var next = 0
                while (true) {
                    val span = clip.span(t0, 1024, dt, next) ?: break
                    next = span.to
                    if (!span.inMedia) { voices[clip.id]?.ready = false; continue }
                    val source = sources.getOrPut(clip.id) { OfflineSource(clip.uri) { uri -> ToneSource(if (uri == "tone1") 440.0 else 660.0) } }
                    val r = source.open(span.pos)
                    val voice = voices.getOrPut(clip.id) { Voice() }
                    val count = span.to - span.from
                    voice.begin(span.pos * r / 1000.0, span.step * r / 1000.0, count)
                    if (voice.jump) { source.window(voice.ghostBase, voice.ghostSize, voice.ghostWindow()); voice.ghostOk = true }
                    source.window(voice.base, voice.size, voice.win)
                    voice.render(block, span.from, count, span.pos * r / 1000.0, span.step * r / 1000.0, span.g0, span.g1, Resampler.ULTRA, span.clipMs0, dt, span.lengthMs)
                }
            }
            for (k in 0 until 2048) {
                val at = 2 * i * 1024 + k
                if (at < before.size) before[at] = (block[k].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        assertTrue(level(before, 100, 2900) in 8_000..29_000)
        assertArrayEquals(before, now)
    }

    @Test fun eachClipRunsThroughItsOwnChain() {
        val half: FxMaker = { _, _ -> GainFx(0.5f) }
        val mixed = render(scene(audio(1, 0, 1999, true), audio(2, 0, 1999, false)), 2000, half)
        val first = render(scene(audio(1, 0, 1999, true)), 2000, half)
        val second = render(scene(audio(2, 0, 1999, false)), 2000, half)
        val dry = render(scene(audio(1, 0, 1999, false)), 2000, half)
        for (i in mixed.indices) assertEquals(first[i] + second[i].toFloat(), mixed[i].toFloat(), 2f)
        for (i in 2000 until 2400) assertEquals(dry[i] / 2f, first[i].toFloat(), 1f)
    }

    @Test fun aSeekStartsTheChainsFreshLikeANewExport() {
        val echo: FxMaker = { _, _ -> EchoFx(4410, 0.6f) }
        val sc = scene(audio(1, 0, 9999, true))
        val jumped = mix(sc, echo).use { it(0, 2_000_000); it(5_000_000, 6_000_000) }
        val fresh = mix(sc, echo).use { it(5_000_000, 6_000_000) }
        assertArrayEquals(fresh, jumped)
        val carried = mix(sc, echo).use { it(0, 5_000_000); it(5_000_000, 6_000_000) }
        assertTrue(carried.indices.any { abs(carried[it] - fresh[it]) > 100 })
    }

    @Test fun theMixDoesNotDependOnHowItIsRequested() {
        val echo: FxMaker = { _, _ -> EchoFx(3001, 0.5f) }
        val sc = scene(audio(1, 0, 1999, true), audio(2, 700, 2999, false))
        val whole = render(sc, 3000, echo)
        val pieces = mix(sc, echo).use { m ->
            val parts = ArrayList<ShortArray>()
            var us = 0L
            var step = 7_000L
            while (us < 3_000_000L) {
                val end = minOf(3_000_000L, us + step)
                parts += m(us, end)
                us = end
                step = step * 7 % 333_331 + 1_000
            }
            parts.flatMap { it.asList() }.toShortArray()
        }
        assertArrayEquals(whole, pieces)
    }

    @Test fun tailsRingOutAfterTheClipThenStop() {
        val pcm = render(scene(audio(1, 0, 499, true)), 8000) { _, _ -> EchoFx(66_150, 0.5f) }
        assertTrue(level(pcm, 100, 450) > 10_000)
        assertEquals(0, level(pcm, 520, 1480))
        assertTrue(level(pcm, 1550, 1950) > 4_000)
        assertTrue(level(pcm, 3050, 3450) > 2_000)
        val short = render(scene(audio(1, 0, 499, true)), 8000) { _, _ -> EchoFx(4410, 0.5f) }
        assertTrue(level(short, 600, 700) > 1_000)
        assertEquals(0, level(short, 4000, 8000))
    }

    @Test fun thePreviewLimiterNeverExceedsTheCeilingAndLeavesQuietSoundAlone() {
        val ceiling = 10f.pow(CEILING_DB / 20f)
        val limiter = SafeLimiter(48_000)
        var seed = 99
        var size = 1
        repeat(400) {
            val buf = FloatArray(2 * size) { seed = seed * 1103515245 + 12345; ((seed ushr 8) and 0xffff) / 2048f - 16f }
            limiter.process(buf, size)
            assertTrue(buf.all { abs(it) <= ceiling })
            size = size * 3 % 1021 + 1
        }
        limiter.reset()
        val quiet = FloatArray(2 * 4800) { 0.5f * sin(it * 0.01f) }
        val copy = quiet.copyOf()
        limiter.process(quiet, 4800)
        assertArrayEquals(copy, quiet, 0f)
        assertEquals(0.5f, limiter.peak, 1e-3f)
    }

    @Test fun loudClipsAreLimitedInsteadOfClipped() {
        val loud = """<gain value="1.5"/>"""
        val sc = scene(audio(1, 0, 1999, false).replace("</audio>", "$loud</audio>"), audio(2, 0, 1999, false).replace("</audio>", "$loud</audio>"))
        val pcm = OfflineMix({ uri -> ToneSource(if (uri == "tone1") 440.0 else 660.0) }, AudioClip.of(sc), Resampler.ULTRA, { _, _ -> null }, SafeLimiter(44_100)).use { it(0, 2_000_000) }
        val ceiling = (10f.pow(CEILING_DB / 20f) * 32767).roundToInt()
        assertTrue(pcm.all { abs(it.toInt()) <= ceiling })
        assertTrue(level(pcm, 100, 1900) > ceiling - 200)
        assertTrue(pcm.count { abs(it.toInt()) >= ceiling - 1 } < pcm.size / 20)
    }

    @Test fun exportStartingInsideALongClipSoundsAtOnce() {
        val pcm = mix(scene(audio(1, 0, 29_999, true)), { _, _ -> FakeFx() }).use { it(4_500_000, 4_600_000) }
        var peak = 0
        for (i in 2 * 200 until 2 * 1000) peak = maxOf(peak, abs(pcm[i].toInt()))
        assertTrue("peak $peak", peak > 15_000)
    }

    @Test fun previewStartingInsideALongClipSoundsAtOnceThroughTheChain() {
        val clip = AudioClip.of(scene(audio(1, 0, 29_999, true))).single()
        val stream = LayerStream(clip.uri, 4500.0 - LayerStream.LEAD_MS) { ToneSource(440.0) }
        FxRack(48_000) { _, _ -> FakeFx() }.use { rack ->
            try {
                val until = System.nanoTime() + 3_000_000_000L
                while (!stream.prepare(4500.0)) { assertTrue("the stream never became ready", System.nanoTime() < until); Thread.sleep(5) }
                val out = FloatArray(2 * 1024)
                mixStreams(listOf(clip), out, 1024, 4500.0, 48_000, Resampler.ULTRA, rack) { stream }
                var peak = 0f
                for (k in 200 until 1024) peak = maxOf(peak, abs(out[2 * k]))
                assertTrue("peak $peak", peak > 0.45f)
            } finally {
                stream.close()
            }
        }
    }

    @Test fun restartingPreviewDoesNotStackEffectTails() {
        val clip = AudioClip.of(scene(audio(1, 0, 999, true))).single()
        val stream = LayerStream(clip.uri, 500.0 - LayerStream.LEAD_MS) { ToneSource(440.0) }
        try {
            val until = System.nanoTime() + 3_000_000_000L
            while (!stream.prepare(500.0)) { assertTrue("the stream never became ready", System.nanoTime() < until); Thread.sleep(5) }
            fun after(reset: Boolean): Float = FxRack(48_000) { _, _ -> EchoFx(12_000, 0.9f) }.use { rack ->
                val out = FloatArray(2 * 1024)
                var t = 500.0
                repeat(20) {
                    while (!stream.prepare(t)) Thread.sleep(2)
                    mixStreams(listOf(clip), out, 1024, t, 48_000, Resampler.ULTRA, rack) { stream }
                    t += 1024 * 1000.0 / 48_000
                }
                if (reset) rack.reset()
                mixStreams(listOf(clip), out, 1024, 1500.0, 48_000, Resampler.ULTRA, rack) { stream }
                out.maxOf { abs(it) }
            }
            assertTrue(after(false) > 0.1f)
            assertEquals(0f, after(true))
        } finally {
            stream.close()
        }
    }
}
