package app.absentmotion.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Beats {
    private const val WINDOW = 512
    private const val HOP_SECONDS = 0.023
    private const val K = 0.5
    private const val FLOOR = 0.2
    private const val LOW_HZ = 40.0
    private const val BANDS_PER_OCTAVE = 4
    private const val LAMBDA = 10.0
    private const val MEAN_SECONDS = 0.7
    private const val MIN_GAP_SECONDS = 0.07
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 180.0
    private const val CENTRE_BPM = 120.0

    private const val GRID_TOLERANCE = 0.12

    private const val LEAD = 0.34

    fun detect(pcm: FloatArray, rate: Int): List<Double> {
        val hop = (rate * HOP_SECONDS).roundToInt().coerceAtLeast(1)
        val flux = spectralFlux(pcm, hop, rate)
        if (flux.size < 16) return emptyList()
        val hopMs = hop * 1000.0 / rate
        val onsets = pickOnsets(flux, (MIN_GAP_SECONDS * 1000 / hopMs).roundToInt().coerceAtLeast(1), (MEAN_SECONDS * 1000 / hopMs).roundToInt())
        if (onsets.size < 4) return emptyList()
        val (period, phase) = tempo(flux, hopMs)
        val tolerance = GRID_TOLERANCE * period
        val beats = sortedMapOf<Int, Double>()
        for (onset in onsets) {
            val n = ((onset - phase) / period).roundToInt()
            val off = abs(onset - (phase + n * period))
            if (off <= tolerance && (n !in beats || off < abs(beats.getValue(n) - (phase + n * period)))) beats[n] = onset
        }
        val lead = LEAD * WINDOW / hop
        return beats.values.map { (it + WINDOW / 2.0 / hop - lead) * hopMs }.filter { it >= 0 }
    }

    private fun tempo(flux: DoubleArray, hopMs: Double): Pair<Double, Double> {
        val mean = flux.average()
        val x = DoubleArray(flux.size) { max(0.0, flux[it] - mean) }
        val minLag = 60_000 / MAX_BPM / hopMs
        val maxLag = 60_000 / MIN_BPM / hopMs
        fun auto(lag: Int): Double {
            var sum = 0.0
            for (i in lag until x.size) sum += x[i] * x[i - lag]
            return sum
        }
        var lag = minLag.roundToInt()
        var best = Double.NEGATIVE_INFINITY
        var coarse = lag
        while (lag <= maxLag) {
            val octaves = ln(60_000 / (lag * hopMs) / CENTRE_BPM) / ln(2.0)
            val score = auto(lag) * kotlin.math.exp(-0.5 * octaves * octaves / 0.64)
            if (score > best) { best = score; coarse = lag }
            lag++
        }
        fun at(t: Double): Double {
            val i = t.toInt()
            if (i < 0 || i + 1 >= x.size) return 0.0
            return x[i] + (x[i + 1] - x[i]) * (t - i)
        }
        var period = coarse.toDouble()
        var phase = 0.0
        var top = Double.NEGATIVE_INFINITY
        var p = coarse * 0.94
        while (p <= coarse * 1.06) {
            var f = 0.0
            while (f < p) {
                var sum = 0.0
                var points = 0
                var t = f
                while (t < x.size) { sum += at(t); points++; t += p }
                if (sum / points > top) { top = sum / points; period = p; phase = f }
                f += 0.25
            }
            p += 0.05
        }
        return period to phase
    }

    private fun pickOnsets(flux: DoubleArray, gap: Int, radius: Int): List<Double> {
        val sums = DoubleArray(flux.size + 1)
        val squares = DoubleArray(flux.size + 1)
        for (i in flux.indices) { sums[i + 1] = sums[i] + flux[i]; squares[i + 1] = squares[i] + flux[i] * flux[i] }
        val peaks = ArrayList<Int>()
        for (i in 1 until flux.size - 1) {
            if (flux[i] < flux[i - 1] || flux[i] <= flux[i + 1]) continue
            val from = max(0, i - radius)
            val to = min(flux.size, i + radius + 1)
            val n = (to - from).toDouble()
            val mean = (sums[to] - sums[from]) / n
            val std = sqrt(max(0.0, (squares[to] - squares[from]) / n - mean * mean))
            if (flux[i] <= mean + K * std || flux[i] < 1e-6) continue
            val last = peaks.lastOrNull()
            if (last == null || i - last >= gap) peaks += i else if (flux[i] > flux[last]) peaks[peaks.lastIndex] = i
        }
        val floor = FLOOR * peaks.sumOf { flux[it] } / max(1, peaks.size)
        return peaks.filter { flux[it] >= floor }.map { i ->
            val a = flux[i - 1]
            val b = flux[i]
            val c = flux[i + 1]
            i + 0.5 * (a - c) / (a - 2 * b + c)
        }
    }

    private fun spectralFlux(pcm: FloatArray, hop: Int, rate: Int): DoubleArray {
        val frames = (pcm.size - WINDOW) / hop
        if (frames < 2) return DoubleArray(0)
        val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
        val norm = 2.0 / window.sum()
        val cosines = DoubleArray(WINDOW / 2) { cos(2 * PI * it / WINDOW) }
        val sines = DoubleArray(WINDOW / 2) { -sin(2 * PI * it / WINDOW) }
        val band = IntArray(WINDOW / 2) { k -> if (k * rate.toDouble() / WINDOW < LOW_HZ) -1 else (BANDS_PER_OCTAVE * ln(k * rate.toDouble() / WINDOW / LOW_HZ) / ln(2.0)).toInt() }
        val bands = band.max() + 1
        val width = IntArray(bands).also { w -> band.forEach { if (it >= 0) w[it]++ } }
        val re = DoubleArray(WINDOW)
        val im = DoubleArray(WINDOW)
        var before = DoubleArray(bands)
        var now = DoubleArray(bands)
        val flux = DoubleArray(frames)
        for (f in 0 until frames) {
            for (i in 0 until WINDOW) { re[i] = pcm[f * hop + i] * window[i]; im[i] = 0.0 }
            fft(re, im, cosines, sines)
            java.util.Arrays.fill(now, 0.0)
            for (k in 1 until WINDOW / 2) if (band[k] >= 0) now[band[k]] += re[k] * re[k] + im[k] * im[k]
            var sum = 0.0
            for (b in 0 until bands) {
                now[b] = if (width[b] == 0) 0.0 else ln1p(LAMBDA * norm * sqrt(now[b] / width[b]))
                sum += max(0.0, now[b] - before[b])
            }
            flux[f] = if (f == 0) 0.0 else sum
            val swap = before; before = now; now = swap
        }
        return flux
    }

    private fun fft(re: DoubleArray, im: DoubleArray, cosines: DoubleArray, sines: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val m = im[i]; im[i] = im[j]; im[j] = m
            }
        }
        var size = 2
        while (size <= n) {
            val step = n / size
            for (start in 0 until n step size) {
                for (k in 0 until size / 2) {
                    val c = cosines[k * step]
                    val s = sines[k * step]
                    val a = start + k
                    val b = a + size / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            size = size shl 1
        }
    }
}
