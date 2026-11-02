package app.absentmotion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    private fun tone(frames: Int, hz: Double, rate: Int) = FloatArray(2 * frames).also { w ->
        for (i in 0 until frames) { val v = sin(2 * PI * hz * i / rate).toFloat(); w[2 * i] = v; w[2 * i + 1] = -v }
    }

    @Test fun aDownsampledToneFollowsTheOriginalWithTheCachedKernels() {
        val source = 48_000
        val win = tone(4_000, 1_000.0, source)
        val step = source / 44_100.0
        val bank = SincBank()
        val out = FloatArray(2)
        var worst = 0.0
        for (i in 0 until 3_500) {
            val position = 20 + i * step
            val j = position.toInt()
            Resampler.sample(win, j, (position - j).toFloat(), Resampler.ULTRA, step, out, bank)
            val want = sin(2 * PI * 1_000.0 * position / source)
            worst = maxOf(worst, abs(out[0] - want), abs(out[1] + want))
        }
        assertTrue("worst error $worst", worst < 0.01)
    }

    @Test fun kernelsAreBuiltOncePerPhaseAndNotOncePerFrame() {
        val win = tone(4_000, 500.0, 48_000)
        val bank = SincBank()
        val out = FloatArray(2)
        val step = 48_000 / 44_100.0
        for (i in 0 until 20_000) {
            val position = 20 + (i % 3_000) * step
            val j = position.toInt()
            Resampler.sample(win, j, (position - j).toFloat(), Resampler.ULTRA, step, out, bank)
        }
        assertTrue("built ${bank.built}", bank.built in 1..Resampler.PHASES + 1)
        val before = bank.built
        Resampler.sample(win, 100, 0.5f, Resampler.ULTRA, 2.0, out, bank)
        assertTrue(bank.built > before)
        Resampler.sample(win, 100, 0.5f, Resampler.ULTRA, 2.0, out, bank)
        assertEquals(before + 1, bank.built)
    }

    @Test fun aStepWithinOneStepSizeSharesItsKernels() {
        val bank = SincBank()
        assertTrue(bank.kernel(0.25f, 1.5000) === bank.kernel(0.25f, 1.5009))
        val one = bank.kernel(0.25f, 1.5000)
        val other = bank.kernel(0.25f, 1.6)
        assertTrue(one !== other)
        assertEquals(1.0, other.sum().toDouble(), 1e-5)
    }

    @Test fun theFastPathStillUsesTheFixedTableAtUnitSpeed() {
        val win = tone(200, 300.0, 44_100)
        val bank = SincBank()
        val out = FloatArray(2)
        Resampler.sample(win, 50, 0.4f, Resampler.ULTRA, 1.0, out, bank)
        assertEquals(0, bank.built)
        assertEquals(sin(2 * PI * 300.0 * 50.4 / 44_100).toFloat(), out[0], 0.01f)
    }
}
