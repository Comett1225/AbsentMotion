package app.absentmotion.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

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

    private var rate = 0.0
    private var played = 0L
    private var aStart = 0L
    private var aQ = 0.0
    private var aOn = false
    private var bStart = 0L
    private var bQ = 0.0
    private var bOn = false
    private val template = FloatArray(MATCH)

    fun begin(pos0: Double, step: Double, count: Int, pitch: Double = 0.0) {
        jump = ready && abs(pos0 - lastEnd) > 2.5
        rate = if (pitch > 0.0 && step > 0.0) pitch else 0.0
        if (rate == 0.0 || !ready || jump) { aOn = false; bOn = false }
        if (rate > 0.0) {
            val behind = min(pos0, min(if (aOn) aQ + rate * (played - aStart) else pos0, if (bOn) bQ + rate * (played - bStart) else pos0))
            val ahead = max(pos0 + max(step, rate) * (count - 1 + GRAIN), if (bOn) bQ + rate * GRAIN else pos0)
            base = floor(behind - rate * HOP - SEARCH).toLong() - Resampler.TAPS - 2
            size = (ceil(ahead + rate * GRAIN + SEARCH + MATCH).toLong() + Resampler.TAPS + 2 - base).toInt()
        } else {
            base = floor(pos0).toLong() - Resampler.TAPS
            size = (ceil(pos0 + step * (count - 1)).toLong() + Resampler.TAPS - base + 1).toInt()
        }
        if (win.size < 2 * size) win = FloatArray(2 * size)
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
        if (rate > 0.0 && !bOn) { bStart = played - HOP; bQ = pos0 - rate * HOP; bOn = true }
        for (k in 0 until count) {
            if (rate > 0.0) stretched(played + k, pos0 + step * (k + HOP) - rate * HOP, quality)
            else {
                val p = pos0 + step * k - base
                val j = p.toInt()
                Resampler.sample(win, j, (p - j).toFloat(), quality, step, sample, sinc)
            }
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
        played += count
        ready = true
    }

    private fun stretched(n: Long, wanted: Double, quality: Int) {
        if (n >= bStart + HOP) {
            val start = bStart + HOP
            val q = matched(bQ + rate * HOP, wanted)
            aStart = bStart; aQ = bQ; aOn = true
            bStart = start; bQ = q
        }
        var l = 0f
        var r = 0f
        if (aOn) { val x = (n - aStart).toInt(); if (x in 0 until GRAIN) { grainSample(aQ + rate * x, quality); l += sample[0] * HANN[x]; r += sample[1] * HANN[x] } }
        val x = (n - bStart).toInt()
        if (x in 0 until GRAIN) { grainSample(bQ + rate * x, quality); l += sample[0] * HANN[x]; r += sample[1] * HANN[x] }
        sample[0] = l
        sample[1] = r
    }

    private fun grainSample(at: Double, quality: Int) {
        val p = (at - base).coerceIn(Resampler.TAPS.toDouble(), (size - Resampler.TAPS - 2).toDouble())
        val j = p.toInt()
        Resampler.sample(win, j, (p - j).toFloat(), quality, rate, sample, sinc)
    }

    private fun mono(i: Long): Float {
        val at = (i - base).coerceIn(0L, size - 1L).toInt()
        return win[2 * at] + win[2 * at + 1]
    }

    private fun matched(natural: Double, wanted: Double): Double {
        val from = natural.roundToLong()
        for (i in 0 until MATCH) template[i] = mono(from + i)
        val centre = wanted.roundToLong()
        fun score(d: Long, stride: Int): Double {
            var dot = 0.0
            var energy = 1e-9
            var i = 0
            while (i < MATCH) {
                val c = mono(centre + d + i)
                dot += c * template[i]
                energy += c * c
                i += stride
            }
            return dot / sqrt(energy)
        }
        var best = 0L
        var top = score(0, 2)
        var d = -SEARCH.toLong()
        while (d <= SEARCH) { val v = score(d, 2); if (v > top) { top = v; best = d }; d += 2 }
        val coarse = best
        top = score(coarse, 1)
        for (e in coarse - 2..coarse + 2) { if (e == coarse || abs(e) > SEARCH) continue; val v = score(e, 1); if (v > top) { top = v; best = e } }
        return centre + best + (natural - from)
    }

    companion object {
        private const val XFADE = 128
        private const val RAMP_MS = 2.0
        private const val GRAIN = 2048
        private const val HOP = GRAIN / 2
        private const val SEARCH = 384
        private const val MATCH = 768
        private val HANN = FloatArray(GRAIN) { (0.5 - 0.5 * cos(2 * PI * it / GRAIN)).toFloat() }
    }
}
