package app.absentmotion.audio

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class NativeFxTest {
    @Before fun needsTheHostLibrary() = Assume.assumeTrue("build it with tools/audio_dsp.sh --jvm", loaded)

    private fun chain(rate: Int, fx: AudioFx) = chain(rate, listOf(fx))

    private fun chain(rate: Int, effects: List<AudioFx>): ClipFx {
        val spec = effects.spec()!!
        return NativeFx.chain(spec, rate)!!.also { it.set(spec.values) }
    }

    private fun sine(hz: Double, rate: Int, seconds: Double, level: Double) =
        FloatArray(2 * (rate * seconds).toInt()).also { for (i in 0 until it.size / 2) { val v = (level * sin(2 * PI * hz * i / rate)).toFloat(); it[2 * i] = v; it[2 * i + 1] = v } }

    private fun peak(buf: FloatArray, from: Int, to: Int): Float { var p = 0f; for (i in 2 * from until 2 * to) p = maxOf(p, abs(buf[i])); return p }

    private fun db(x: Float) = 20 * log10(x.toDouble())

    @Test fun parametersReachTheRightEffectControls() {
        val r = 48_000
        val low = sine(40.0, r, 2.0, 0.1).also { chain(r, AudioFx.of(FxKind.Eq, "lowFreq" to 400f, "lowGain" to 12f)).use { fx -> fx.process(it, r * 2) } }
        assertEquals(12.0, db(peak(low, r, 2 * r) / 0.1f), 0.6)
        val mid = sine(1000.0, r, 2.0, 0.1).also { chain(r, AudioFx.of(FxKind.Eq, "mid2Freq" to 1000f, "mid2Gain" to -9f, "mid2Q" to 2f)).use { fx -> fx.process(it, r * 2) } }
        assertEquals(-9.0, db(peak(mid, r, 2 * r) / 0.1f), 0.3)
        val cut = sine(5000.0, r, 1.0, 0.5).also { chain(r, AudioFx.of(FxKind.LowPass, "freq" to 500f, "slope" to 24f)).use { fx -> fx.process(it, r) } }
        assertTrue(peak(cut, r / 2, r) < 0.5f * 1e-3f)
        val hum = sine(100.0, r, 1.0, 0.5).also { chain(r, AudioFx.of(FxKind.HighPass, "freq" to 2000f)).use { fx -> fx.process(it, r) } }
        assertTrue(peak(hum, r / 2, r) < 0.5f * 0.01f)
        val click = FloatArray(2 * 1000).also { it[0] = 1f; it[1] = 1f }
        chain(r, AudioFx.of(FxKind.Echo, "time" to 10f, "feedback" to 0f, "level" to 1f)).use { it.process(click, 1000) }
        assertEquals(0f, peak(click, 0, 479))
        assertEquals(1f, click[2 * 480], 1e-4f)
        val loud = FloatArray(2 * r) { 0.5f }.also { chain(r, AudioFx.of(FxKind.Compressor, "threshold" to -20f, "ratio" to 4f, "knee" to 0f)).use { fx -> fx.process(it, r) } }
        assertEquals(-20 + (db(0.5f) + 20) / 4, db(loud.last()), 0.3)
        val quiet = FloatArray(2 * r) { 0.01f }.also { chain(r, AudioFx.of(FxKind.Gate, "threshold" to -20f, "range" to 60f)).use { fx -> fx.process(it, r) } }
        assertEquals(-60.0, db(quiet.last() / 0.01f), 0.5)
        val hall = FloatArray(2 * r).also { it[0] = 1f; it[1] = 1f }
        chain(r, AudioFx.of(FxKind.Reverb, "wet" to 1f, "dry" to 0f, "preDelay" to 20f)).use { it.process(hall, r) }
        assertEquals(0f, peak(hall, 0, 959))
        assertTrue(peak(hall, 960, r) > 1e-4f)
    }

    @Test fun pitchEffectsMoveTheFrequency() {
        val r = 48_000
        fun crossings(fx: AudioFx): Int {
            val buf = sine(440.0, r, 2.0, 0.2)
            chain(r, fx).use { it.process(buf, 2 * r) }
            var n = 0
            for (i in r until 2 * r) if (buf[2 * i - 2] < 0f && buf[2 * i] >= 0f) n++
            return n
        }
        assertEquals(880.0, crossings(AudioFx.of(FxKind.HighPitch, "amount" to 12f)).toDouble(), 20.0)
        assertEquals(220.0, crossings(AudioFx.of(FxKind.LowPitch, "amount" to 12f)).toDouble(), 10.0)
        assertEquals(440 * 2.0.pow(5.0 / 12), crossings(AudioFx.of(FxKind.Voice, "pitch" to 5f)).toDouble(), 12.0)
        assertEquals(440.0, crossings(AudioFx.of(FxKind.Voice)).toDouble(), 3.0)
    }

    @Test fun mixAndBypassLeaveTheSoundDry() {
        val r = 44_100
        for (fx in listOf(AudioFx.of(FxKind.Eq, "lowGain" to 12f).withMix(0f), AudioFx.of(FxKind.Eq, "lowGain" to 12f).withBypass(true))) {
            val input = sine(60.0, r, 0.5, 0.2)
            val out = input.copyOf().also { chain(r, fx).use { c -> c.process(it, it.size / 2) } }
            for (i in out.indices) assertEquals(input[i], out[i], 1e-6f)
        }
    }

    @Test fun aChainGivesTheSameSoundForAnyBlockSize() {
        val r = 48_000
        val all = FxKind.entries.map { AudioFx.of(it) }
        val input = FloatArray(2 * r).also { var s = 12345; for (i in it.indices) { s = s * 1103515245 + 12345; it[i] = ((s ushr 8) and 0xffff) / 65536f - 0.5f } }
        val whole = input.copyOf().also { chain(r, all).use { c -> c.process(it, r) } }
        val parts = input.copyOf()
        chain(r, all).use { c ->
            var at = 0
            var n = 1
            val block = FloatArray(2 * 1024)
            while (at < r) {
                val count = minOf(n, r - at)
                System.arraycopy(parts, 2 * at, block, 0, 2 * count)
                c.process(block, count)
                System.arraycopy(block, 0, parts, 2 * at, 2 * count)
                at += count
                n = n * 5 % 1021 + 1
            }
        }
        for (i in whole.indices) assertEquals(whole[i], parts[i], 1e-6f)
    }

    @Test fun exportWithEffectsMatchesTheDspAppliedToTheDryMix() {
        val chainFx = listOf(
            AudioFx.of(FxKind.Eq, "lowGain" to 6f, "mid2Gain" to 3f),
            AudioFx.of(FxKind.Compressor, "threshold" to -24f, "ratio" to 3f),
            AudioFx.of(FxKind.Reverb, "room" to 0.6f, "wet" to 0.3f, "preDelay" to 12f),
            AudioFx.of(FxKind.Echo, "time" to 120f, "feedback" to 0.3f, "level" to 0.3f),
        )
        val base = Scene.parse("""<scene width="100" height="100" fps="30"><audio id="1" src="t" startTime="0" endTime="2999" outTime="30000"><gain value="0.5"/></audio></scene>""")
        val wetScene = base.update(1) { it.withAudioEffects(chainFx) }
        fun render(scene: Scene) = OfflineMix({ ToneSource(330.0, 44_100) }, AudioClip.of(scene), Resampler.ULTRA).use { it(0, 4_000_000) }
        val dry = render(base)
        val wet = render(wetScene)
        val reference = FloatArray(dry.size) { dry[it] / 32767f }
        chain(44_100, chainFx).use { it.process(reference, reference.size / 2) }
        var worst = 0f
        for (i in wet.indices) worst = maxOf(worst, abs(wet[i] - reference[i] * 32767f))
        assertTrue("differs by $worst", worst <= 4f)
        assertTrue(wet.maxOf { abs(it.toInt()) } in 5_000..29_000)
        assertTrue(abs(wet[2 * 44_100 * 3 + 2 * 4410].toInt()) + abs(wet[2 * 44_100 * 3 + 2 * 4411].toInt()) > 0)
    }

    private fun scene(gain: Float, count: Int) = Scene.parse("""<scene width="100" height="100" fps="30">${
        (1..count).joinToString("") { """<audio id="$it" src="t$it" startTime="0" endTime="3999" outTime="30000"><gain value="$gain"/></audio>""" }
    }</scene>""")

    private fun mix(scene: Scene, master: ClipFx? = null) = AudioClip.of(scene).let { clips ->
        val open = { uri: String -> ToneSource(200.0 + 110 * uri.drop(1).toInt(), 48_000) }
        if (master == null) OfflineMix(open, clips, Resampler.ULTRA) else OfflineMix(open, clips, Resampler.ULTRA, master = master)
    }

    @Test fun theExportLimiterKeepsTheCeilingAndTheTiming() {
        val ceiling = (10.0.pow(CEILING_DB / 20.0) * 32767).roundToInt()
        val loud = mix(scene(1.5f, 3)).use { it(0, 4_000_000) }
        assertTrue(loud.all { abs(it.toInt()) <= ceiling })
        assertTrue(loud.maxOf { abs(it.toInt()) } > ceiling - 100)
        assertTrue(loud.count { abs(it.toInt()) >= ceiling - 1 } < loud.size / 50)
        val quiet = scene(0.3f, 2)
        val native = mix(quiet).use { it(0, 4_000_000) }
        val plain = mix(quiet, SafeLimiter(44_100)).use { it(0, 4_000_000) }
        assertTrue(plain.maxOf { abs(it.toInt()) } > 5_000)
        for (i in native.indices) assertEquals("at $i", plain[i].toFloat(), native[i].toFloat(), 1f)
        val jumped = mix(quiet).use { it(0, 500_000); it(2_000_000, 3_000_000) }
        val fresh = mix(quiet, SafeLimiter(44_100)).use { it(2_000_000, 3_000_000) }
        for (i in jumped.indices) assertEquals(fresh[i].toFloat(), jumped[i].toFloat(), 1f)
    }

    @Test fun theExportLimiterGivesTheSameSoundForAnyRequestSize() {
        val sc = scene(1.5f, 3)
        val whole = mix(sc).use { it(0, 3_000_000) }
        val pieces = mix(sc).use { m ->
            val parts = ArrayList<ShortArray>()
            var us = 0L
            var step = 3_000L
            while (us < 3_000_000L) {
                val end = minOf(3_000_000L, us + step)
                parts += m(us, end)
                us = end
                step = step * 11 % 250_007 + 500
            }
            parts.flatMap { it.asList() }.toShortArray()
        }
        assertTrue(whole.contentEquals(pieces))
    }

    private companion object {
        val loaded: Boolean = run {
            val lib = File("build/hostfx").listFiles { f -> f.name.startsWith("libamfx.") }?.firstOrNull() ?: return@run false
            runCatching { System.load(lib.absolutePath) }.isSuccess && NativeFx.available
        }
    }
}
