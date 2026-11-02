package app.absentmotion.audio

import app.absentmotion.platform.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal fun MutableMap<Long, LayerStream>.dropFailed() {
    values.removeAll { stream -> stream.failed.also { if (it) stream.close() } }
}

internal class LayerStream(val uri: String, startMs: Double, val loopMs: Double? = null, private val open: () -> PcmSource) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var ring = ShortArray(0)
    private var cap = 0
    private var startAbs = 0L
    private var endAbs = 0L
    private var keepFrom = 0L
    private var valid = false
    private var eof = false
    private var seekGen = 1
    private var seekMs = startMs
    private var head = ShortArray(0)
    private var headStart = 0L
    private var headEnd = 0L
    private var learn = loopMs == null
    private var capture = -1L

    @Volatile var durationUs = -1L
        private set
    @Volatile var rate = 0
        private set
    @Volatile private var closed = false
    @Volatile var failed = false
        private set

    private val voice = Voice()

    private val thread = Thread({ run() }, "am-audio-stream").apply { isDaemon = true; start() }

    private fun run() {
        var decoder: PcmSource? = null
        try {
            decoder = open()
            durationUs = decoder.durationUs
            loopMs?.let { readHead(decoder, it) }
            var handled = 0
            var target = 0.0
            var placed = true
            while (!closed) {
                var fresh = false
                lock.withLock {
                    if (seekGen != handled) { handled = seekGen; target = seekMs; fresh = true }
                }
                if (fresh) { decoder.seek((target * 1000).toLong()); placed = false }
                val room = lock.withLock { if (!valid) Long.MAX_VALUE else if (eof) 0L else room() }
                if (room < 2048) {
                    lock.withLock { if (seekGen == handled && !closed) changed.await(10, TimeUnit.MILLISECONDS) }
                    continue
                }
                val chunk = decoder.next()
                if (chunk == null) {
                    if (rate == 0) { failed = true; break }
                    lock.withLock { if (seekGen == handled) { if (!placed) place(rate, target); placed = true; eof = true; keepHead(true) } }
                    continue
                }
                if (!placed) lock.withLock { if (seekGen == handled) { place(chunk.rate, target); placed = true } }
                if (placed && append(chunk, handled)) keepHead(false)
            }
        } catch (e: Exception) {
            Log.w("AMAudio", "Cannot decode $uri", e)
            failed = true
        } finally {
            decoder?.close()
        }
    }

    private fun readHead(decoder: PcmSource, loopMs: Double) {
        val fromMs = maxOf(0.0, loopMs - LEAD_MS)
        decoder.seek((fromMs * 1000).toLong())
        var buf = ShortArray(0)
        var start = -1L
        var end = 0L
        while (!closed) {
            val c = decoder.next() ?: break
            if (start < 0) { start = Math.round(fromMs * c.rate / 1000.0); end = start; buf = ShortArray(2 * c.rate * HEAD_MS / 1000) }
            val capacity = start + buf.size / 2
            val a = (c.ptsUs * c.rate + 500_000L) / 1_000_000L
            if (a > end) end = minOf(a, capacity)
            val skip = (end - a).coerceIn(0L, c.frames.toLong()).toInt()
            val n = minOf(c.frames - skip, (capacity - end).toInt())
            System.arraycopy(c.pcm, 2 * skip, buf, (2 * (end - start)).toInt(), 2 * n)
            end += n
            if (end >= capacity) break
        }
        if (start >= 0) lock.withLock { head = buf; headStart = start; headEnd = end }
    }

    private fun place(sourceRate: Int, targetMs: Double) {
        rate = sourceRate
        if (cap != 3 * sourceRate) { cap = 3 * sourceRate; ring = ShortArray(cap * 2) }
        startAbs = Math.round(targetMs * sourceRate / 1000.0).coerceAtLeast(0)
        endAbs = startAbs
        eof = false
        valid = true
        capture = if (learn) startAbs else -1L
    }

    private fun keepHead(ended: Boolean) = lock.withLock {
        val from = capture
        val frames = rate.toLong() * HEAD_MS / 1000
        if (from < 0 || !valid || (endAbs - from < frames && !ended)) return@withLock
        capture = -1L
        val n = minOf(frames, endAbs - from).toInt()
        if (from < startAbs || n <= 0) return@withLock
        val buf = ShortArray(2 * n)
        for (i in 0 until n) {
            val at = ((from + i) % cap).toInt() * 2
            buf[2 * i] = ring[at]
            buf[2 * i + 1] = ring[at + 1]
        }
        head = buf
        headStart = from
        headEnd = from + n
    }

    private fun append(c: PcmChunk, gen: Int): Boolean {
        val a = (c.ptsUs * c.rate + 500_000L) / 1_000_000L
        var off = 0
        var n = c.frames
        var gap = 0L
        lock.withLock {
            if (seekGen != gen) return false
            if (a + n <= endAbs) return true
            if (a < endAbs) { off = (endAbs - a).toInt(); n -= off }
            else if (a - endAbs > 64) { if (a - endAbs <= cap / 2) gap = a - endAbs else { startAbs = a; endAbs = a } }
        }
        if (gap > 0 && !write(null, 0, gap.toInt(), gen)) return false
        return write(c.pcm, off, n, gen)
    }

    private fun write(src: ShortArray?, off: Int, count: Int, gen: Int): Boolean {
        var done = 0
        while (done < count) {
            var n = 0
            lock.withLock {
                if (closed || seekGen != gen) return false
                n = min(room(), (count - done).toLong()).toInt()
                if (n > 0) {
                    var at = (endAbs % cap).toInt()
                    var left = n
                    var from = off + done
                    while (left > 0) {
                        val part = min(left, cap - at)
                        if (src == null) java.util.Arrays.fill(ring, 2 * at, 2 * (at + part), 0.toShort()) else System.arraycopy(src, 2 * from, ring, 2 * at, 2 * part)
                        left -= part; from += part; at = 0
                    }
                    endAbs += n
                    startAbs = maxOf(startAbs, endAbs - cap)
                } else changed.await(10, TimeUnit.MILLISECONDS)
            }
            done += n
        }
        return true
    }

    private fun room(): Long = cap - (endAbs - keepFrom.coerceIn(startAbs, endAbs))

    private fun request(ms: Double, learn: Boolean = loopMs == null) {
        seekMs = maxOf(0.0, ms)
        seekGen++
        valid = false
        eof = false
        this.learn = learn
        changed.signalAll()
    }

    private fun holds(lo: Long, hi: Long): Boolean = (lo >= startAbs && (hi <= endAbs || eof)) || (lo >= headStart && hi <= headEnd)

    private fun covers(lo: Long, hi: Long): Boolean {
        val r = rate
        if (r == 0) return false
        val from = maxOf(lo, 0L)
        val ringEnd = if (eof) Long.MAX_VALUE else endAbs
        if (valid && from >= startAbs && hi <= ringEnd) return true
        if (from >= headStart && from < headEnd) {
            val next = maxOf(headEnd - 256, 0L)
            if (valid && startAbs <= next && hi <= ringEnd) return true
            if (if (valid) startAbs > next || endAbs < next else seekMs != next * 1000.0 / r) request(next * 1000.0 / r, false)
            return hi <= headEnd
        }
        if (!valid) return false
        if (from < startAbs || from > endAbs + r) request((from - 256) * 1000.0 / r)
        return false
    }

    fun prepare(posMs: Double, aheadMs: Double = 150.0): Boolean = lock.withLock {
        if (failed) return true
        val r = rate
        if (r == 0) { if (abs(seekMs - (posMs - LEAD_MS)) > 1.0) request(posMs - LEAD_MS); return false }
        val lo = floor(posMs * r / 1000.0).toLong() - Resampler.TAPS
        keepFrom = lo - KEEP
        covers(lo, lo + (aheadMs * r / 1000).toLong() + 2 * Resampler.TAPS)
    }

    fun restart() { voice.ready = false }

    fun mix(
        dst: FloatArray, from: Int, to: Int, posMs: Double, stepMs: Double, g0: Float, g1: Float, quality: Int,
        clipMs0: Double, dtMs: Double, clipLenMs: Double,
    ): Boolean {
        val r = rate
        val count = to - from
        if (r == 0 || failed || count <= 0) { voice.ready = false; return false }
        val pos0 = posMs * r / 1000.0
        val step = stepMs * r / 1000.0
        voice.begin(pos0, step, count)
        val ok = lock.withLock {
            keepFrom = voice.base - KEEP
            changed.signalAll()
            if (!covers(voice.base, voice.last)) return@withLock false
            copyWindow(voice.base, voice.size, voice.win)
            if (voice.jump && holds(voice.ghostBase, voice.ghostBase + voice.ghostSize)) {
                copyWindow(voice.ghostBase, voice.ghostSize, voice.ghostWindow())
                voice.ghostOk = true
            }
            true
        }
        if (!ok) { voice.ready = false; return false }
        voice.render(dst, from, count, pos0, step, g0, g1, quality, clipMs0, dtMs, clipLenMs)
        return true
    }

    private fun copyWindow(base: Long, frames: Int, out: FloatArray) {
        for (i in 0 until frames) {
            val abs = base + i
            if (abs >= 0 && abs >= startAbs && abs < endAbs) {
                val at = (abs % cap).toInt() * 2
                out[2 * i] = ring[at] * INV
                out[2 * i + 1] = ring[at + 1] * INV
            } else if (abs >= headStart && abs < headEnd) {
                val at = (abs - headStart).toInt() * 2
                out[2 * i] = head[at] * INV
                out[2 * i + 1] = head[at + 1] * INV
            } else { out[2 * i] = 0f; out[2 * i + 1] = 0f }
        }
    }

    fun close() {
        closed = true
        lock.withLock { changed.signalAll() }
    }

    companion object {
        const val LEAD_MS = 20.0
        private const val HEAD_MS = 1000
        private const val KEEP = 64L
        private const val INV = 1f / 32768f
    }
}
