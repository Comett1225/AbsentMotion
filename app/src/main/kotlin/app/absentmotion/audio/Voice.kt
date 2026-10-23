package app.absentmotion.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal class Voice {
    var ready = false
    private var lastEnd = Double.NaN
    private var lastStep = 0.0
    private var lastGain = 0f
    var win = FloatArray(0)
        private set
    private var ghost = FloatArray(0)
    private val sample = FloatArray(2)
    private val sinc = SincBank()

    var base = 0L
        private set
    var size = 0
        private set
    val last: Long get() = base + size - 1

    var jump = false
        private set
    var ghostBase = 0L
        private set
    var ghostSize = 0
        private set
    var ghostOk = false

    fun begin(pos0: Double, step: Double, count: Int) {
        base = floor(pos0).toLong() - Resampler.TAPS
        size = (ceil(pos0 + step * (count - 1)).toLong() + Resampler.TAPS - base + 1).toInt()
        if (win.size < 2 * size) win = FloatArray(2 * size)
        jump = ready && abs(pos0 - lastEnd) > 2.5
        ghostBase = if (jump) floor(lastEnd).toLong() - Resampler.TAPS else 0L
        ghostSize = if (jump) ceil(lastStep * XFADE).toInt() + 2 * Resampler.TAPS + 2 else 0
        ghostOk = false
    }

    fun ghostWindow(): FloatArray {
        if (ghost.size < 2 * ghostSize) ghost = FloatArray(2 * ghostSize)
        return ghost
    }

    fun render(
        dst: FloatArray, from: Int, count: Int, pos0: Double, step: Double, g0: Float, g1: Float, quality: Int,
        clipMs0: Double, dtMs: Double, clipLenMs: Double,
    ) {
        val fade = if (!ready || jump) min(XFADE, count) else 0
        val inv = 1f / count
        for (k in 0 until count) {
            val p = pos0 + step * k - base
            val j = p.toInt()
            Resampler.sample(win, j, (p - j).toFloat(), quality, step, sample, sinc)
            val t = clipMs0 + k * dtMs
            var gain = (g0 + (g1 - g0) * k * inv) * (minOf(t, clipLenMs - t) / RAMP_MS).coerceIn(0.0, 1.0).toFloat()
            if (k < fade) gain *= k / fade.toFloat()
            val at = 2 * (from + k)
            dst[at] += sample[0] * gain
            dst[at + 1] += sample[1] * gain
        }
        if (ghostOk) for (k in 0 until fade) {
            val p = lastEnd + lastStep * k - ghostBase
            val j = p.toInt()
            Resampler.sample(ghost, j, (p - j).toFloat(), quality, lastStep, sample, sinc)
            val gain = lastGain * (1f - k / fade.toFloat())
            val at = 2 * (from + k)
            dst[at] += sample[0] * gain
            dst[at + 1] += sample[1] * gain
        }
        lastEnd = pos0 + step * count
        lastStep = step
        lastGain = g1
        ready = true
    }

    companion object {
        private const val XFADE = 128
        private const val RAMP_MS = 2.0
    }
}
