package app.absentmotion.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

internal class DeskOutput {
    private var line: SourceDataLine? = null
    private var bytes = ByteArray(0)
    var written = 0L
        private set

    @Synchronized fun open(): SourceDataLine = line ?: run {
        val r = nativeRate
        val format = AudioFormat(r.toFloat(), 16, 2, true, false)
        AudioSystem.getSourceDataLine(format).also {
            it.open(format, r * BUFFER_MS / 1000 * 4)
            written = 0
            line = it
        }
    }

    fun write(block: FloatArray, frames: Int) {
        val l = line ?: return
        if (bytes.size < frames * 4) bytes = ByteArray(frames * 4)
        val sink = capture
        sink?.invoke(block, frames)
        val quiet = sink != null
        for (i in 0 until 2 * frames) {
            val v = if (quiet) 0 else (block[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        var off = 0
        while (off < frames * 4) {
            val n = l.write(bytes, off, frames * 4 - off)
            check(n > 0) { "Audio output closed" }
            off += n
        }
        written += frames
        if (!l.isRunning) l.start()
    }

    fun position(): Long = line?.longFramePosition ?: 0L

    @Synchronized fun close() {
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
    }

    companion object {
        const val BUFFER_MS = 39

        @Volatile var capture: ((FloatArray, Int) -> Unit)? = null

        @JvmStatic external fun deviceLatencyNs(): Long

        fun latencyNs(): Long = runCatching { deviceLatencyNs() }.getOrDefault(-1L)

        val nativeRate: Int by lazy {
            runCatching {
                AudioSystem.getMixerInfo().asSequence().map { AudioSystem.getMixer(it) }.flatMap { it.sourceLineInfo.asSequence() }
                    .filterIsInstance<DataLine.Info>().flatMap { it.formats.asSequence() }
                    .map { it.sampleRate }.firstOrNull { it > 0 && it != AudioSystem.NOT_SPECIFIED.toFloat() }?.toInt()
            }.getOrNull() ?: 48_000
        }

        fun warm() { Thread({ nativeRate }, "am-audio-warm").apply { isDaemon = true; start() } }
    }
}

internal class DeskClock(private val startMs: Double, private val rate: Int) {
    private val leads = DoubleArray(WINDOW)
    private var seen = 0
    private var skipped = 0
    private var lastFrames = -1L
    @Volatile private var lead = Double.NaN
    @Volatile private var step = 0L
    @Volatile private var stoppedAt = 0L
    @Volatile private var reached = startMs
    @Volatile var base = 0L
    @Volatile var latencyNs = -1L
    @Volatile var wallStart = 0L
    @Volatile var failed = false

    fun observe(frames: Long, nanos: Long) {
        if (lastFrames >= 0 && frames > lastFrames && (step == 0L || frames - lastFrames < step)) step = frames - lastFrames
        lastFrames = frames
        if (++skipped <= SKIP) return
        leads[seen++ % WINDOW] = frames * 1e9 / rate - nanos
        var best = Double.NEGATIVE_INFINITY
        for (i in 0 until minOf(seen, WINDOW)) if (leads[i] > best) best = leads[i]
        lead = best
    }

    fun stop(now: Long) { if (stoppedAt == 0L) stoppedAt = now }

    fun delayNs(): Double {
        val chunk = step * 1e9 / rate
        return chunk + if (latencyNs >= 0) latencyNs.toDouble() else chunk
    }

    fun positionMs(now: Long = System.nanoTime()): Double? {
        val at = stoppedAt.takeIf { it != 0L } ?: now
        val l = lead
        val began = wallStart
        val ms = when {
            failed -> if (began > 0) startMs + (at - began) / 1e6 else return null
            !l.isNaN() && step > 0 -> startMs + ((at + l - delayNs()) * rate / 1e9 - base).coerceAtLeast(0.0) * 1000.0 / rate
            began > 0 -> startMs
            else -> return null
        }
        return maxOf(ms, reached).also { reached = it }
    }

    private companion object {
        const val WINDOW = 128
        const val SKIP = 3
    }
}
