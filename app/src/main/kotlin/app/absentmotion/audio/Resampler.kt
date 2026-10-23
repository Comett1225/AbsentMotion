package app.absentmotion.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object Resampler {
    const val LOW = 0
    const val HIGH = 1
    const val ULTRA = 2

    const val TAPS = 8
    const val PHASES = 256
    private val fixed = Array(PHASES + 1) { kernel(it / PHASES.toDouble(), 1.0, FloatArray(2 * TAPS)) }

    fun kernel(frac: Double, fc: Double, out: FloatArray): FloatArray {
        var sum = 0.0
        for (m in 0 until 2 * TAPS) {
            val x = (m - (TAPS - 1)) - frac
            val u = x / TAPS
            val window = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
            val arg = PI * fc * x
            val w = (if (arg == 0.0) fc else fc * sin(arg) / arg) * window
            out[m] = w.toFloat()
            sum += w
        }
        for (m in out.indices) out[m] = (out[m] / sum).toFloat()
        return out
    }

    fun sample(win: FloatArray, j: Int, frac: Float, quality: Int, step: Double, out: FloatArray, sinc: SincBank) {
        when (quality) {
            LOW -> {
                val a = 2 * j
                out[0] = win[a] + (win[a + 2] - win[a]) * frac
                out[1] = win[a + 1] + (win[a + 3] - win[a + 1]) * frac
            }
            HIGH -> for (ch in 0..1) {
                val p0 = win[2 * (j - 1) + ch]
                val p1 = win[2 * j + ch]
                val p2 = win[2 * (j + 1) + ch]
                val p3 = win[2 * (j + 2) + ch]
                out[ch] = p1 + 0.5f * frac * (p2 - p0 + frac * (2 * p0 - 5 * p1 + 4 * p2 - p3 + frac * (3 * (p1 - p2) + p3 - p0)))
            }
            else -> {
                val w = if (step <= 1.0001) fixed[(frac * PHASES + 0.5f).toInt()] else sinc.kernel(frac, step)
                var l = 0f
                var r = 0f
                val first = 2 * (j - (TAPS - 1))
                for (m in 0 until 2 * TAPS) {
                    l += win[first + 2 * m] * w[m]
                    r += win[first + 2 * m + 1] * w[m]
                }
                out[0] = l
                out[1] = r
            }
        }
    }
}

internal class SincBank {
    private var key = -1
    private var cutoff = 1.0
    private val phases = arrayOfNulls<FloatArray>(Resampler.PHASES + 1)

    var built = 0
        private set

    fun kernel(frac: Float, step: Double): FloatArray {
        val k = (step * STEP_STEPS + 0.5).toInt()
        if (k != key) {
            key = k
            cutoff = STEP_STEPS / k.toDouble()
            phases.fill(null)
        }
        val at = (frac * Resampler.PHASES + 0.5f).toInt()
        return phases[at] ?: Resampler.kernel(at / Resampler.PHASES.toDouble(), cutoff, FloatArray(2 * Resampler.TAPS)).also { phases[at] = it; built++ }
    }

    private companion object {
        const val STEP_STEPS = 256
    }
}
