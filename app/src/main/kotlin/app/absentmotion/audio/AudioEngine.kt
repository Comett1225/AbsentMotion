package app.absentmotion.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.absentmotion.export.ExportAudio
import app.absentmotion.model.Scene
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class AudioEngine(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var clips: List<AudioClip> = emptyList()
    private val streams = HashMap<Long, LayerStream>()
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var releasing = false
    private val releaseIdle = Runnable { if (session?.stopRequested != false) closeStreams() }

    @Volatile var quality = Resampler.ULTRA

    @Volatile var log = false

    @Volatile var level = 0f
        private set

    @Volatile private var shownFrame = -1
    @Volatile private var shownMs = 0

    @Volatile var interrupted = false
        private set
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupt()
        }, main).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt() }
    }
    private var listening = false

    private fun interrupt() {
        interrupted = true
        session?.halt()
        level = 0f
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        runCatching {
            if (on) context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            else { context.unregisterReceiver(noisy); manager?.abandonAudioFocusRequest(focus) }
        }
    }

    init {
        ExportAudio.mixFor = { scene, rate -> OfflineMix.of(this.context, scene, Resampler.ULTRA, rate) }
    }

    fun setScene(scene: Scene) {
        if (!closed) clips = AudioClip.of(scene)
    }

    fun play(startMs: Double): Boolean {
        val previous = session
        previous?.halt()
        if (closed || clips.none { it.endMs > startMs }) { session = null; return false }
        if (manager?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) { session = null; return false }
        interrupted = false
        listen(true)
        main.removeCallbacks(releaseIdle)
        releasing = false
        synchronized(streams) { streams.dropFailed() }
        session = Session(startMs, previous).also { it.start() }
        return true
    }

    fun stop() {
        session?.halt()
        listen(false)
        level = 0f
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_MS)
    }

    fun frameShown(frame: Int, ms: Int) { shownFrame = frame; shownMs = ms }

    fun positionMs(): Double? = session?.clock?.positionMs()

    fun release() {
        releasing = true
        session?.halt()
        listen(false)
        level = 0f
        main.removeCallbacks(releaseIdle)
        closeStreams()
    }

    fun close() {
        closed = true
        clips = emptyList()
        release()
    }

    private fun closeStreams() = synchronized(streams) {
        streams.values.forEach { it.close() }
        streams.clear()
    }

    private inner class Session(val startMs: Double, @Volatile private var previous: Session?) : Thread("am-audio-mixer") {
        @Volatile var stopRequested = false
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
        val clock = AudioClock(startMs, rate)
        private val rack = FxRack(rate)
        private val limiter = SafeLimiter(rate)

        fun halt() {
            clock.stop(System.nanoTime())
            stopRequested = true
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            previous?.join()
            previous = null
            var track: AudioTrack? = null
            try {
                val bytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                val bufferFrames = max(bytes / 8, BLOCK * 6)
                track = AudioTrack.Builder()
                    .setAudioAttributes(ATTRIBUTES)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferFrames * 8)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                preroll()
                val out = FloatArray(BLOCK * 2)
                var written = 0L
                repeat(3) {
                    mixBlock(out, startMs + written * 1000.0 / rate)
                    writeAll(track, out)
                    written += BLOCK
                }
                clock.route = track.routedDevice?.id ?: 0
                clock.latencyNs = AudioClock.learned[clock.route] ?: AudioClock.outputLatency(track, rate)
                track.play()
                clock.wallStart = System.nanoTime()
                var measured = false
                val pending = ArrayDeque<Pair<Long, Double>>()
                val stamp = AudioTimestamp()
                while (!stopRequested) {
                    val t0 = startMs + written * 1000.0 / rate
                    keepStreams(t0)
                    mixBlock(out, t0)
                    writeAll(track, out)
                    if (log) pending.addLast(written to t0)
                    written += BLOCK
                    if (track.getTimestamp(stamp) && stamp.framePosition >= 0) {
                        clock.stamp(stamp.framePosition, stamp.nanoTime)
                        if (log && !measured && stamp.framePosition > 0) {
                            measured = true
                            Log.i("AMAudio", "output latency estimate=%.1fms measured=%.1fms".format(clock.latencyNs / 1e6, (AudioClock.learned[clock.route] ?: -1L) / 1e6))
                        }
                        while (pending.isNotEmpty() && pending.first().first <= stamp.framePosition) {
                            val (frame, ms) = pending.removeFirst()
                            val plays = stamp.nanoTime + (frame - stamp.framePosition) * 1_000_000_000L / rate
                            Log.i("AMAudio", "block projectMs=%.3f playheadFrame=%d playheadMs=%d trackFrame=%d timestamp=%d@%d late=%.1fms".format(
                                ms, shownFrame, shownMs, frame, stamp.framePosition, stamp.nanoTime, (System.nanoTime() - plays) / 1e6))
                        }
                    }
                }
                for (i in 1..4) { track.setVolume(1f - i / 4f); Thread.sleep(3) }
                track.pause()
                track.flush()
            } catch (e: Exception) {
                Log.w("AMAudio", "Audio stream failed", e)
                clock.failed = true
                if (clock.wallStart == 0L) clock.wallStart = System.nanoTime()
                while (!stopRequested) Thread.sleep(20)
            } finally {
                runCatching { track?.release() }
                rack.close()
                level = 0f
                if (releasing) closeStreams()
            }
        }

        private fun preroll() {
            val deadline = System.nanoTime() + 1_500_000_000L
            while (!stopRequested && System.nanoTime() < deadline) {
                keepStreams(startMs)
                val ready = clips.all { clip ->
                    if (clip.endMs <= startMs || clip.startMs >= startMs + 300) return@all true
                    stream(clip)?.let { clip.resolveDuration(it.durationUs) }
                    val pos = clip.wrap(clip.mediaAt(max(startMs, clip.startMs.toDouble())))
                    if (pos < clip.inMs || pos >= clip.outMs) return@all true
                    stream(clip)?.prepare(pos) ?: true
                }
                if (ready) break
                Thread.sleep(5)
            }
            synchronized(streams) { streams.values.forEach { it.restart() } }
        }

        private fun writeAll(track: AudioTrack, block: FloatArray) {
            var offset = 0
            while (offset < block.size && !stopRequested) {
                val n = track.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                check(n >= 0) { "AudioTrack.write returned $n" }
                offset += n
            }
        }

        private fun mixBlock(out: FloatArray, t0: Double) {
            mixStreams(clips, out, BLOCK, t0, rate, quality, rack, ::stream)
            limiter.process(out, BLOCK)
            level = limiter.peak
        }
    }

    private fun stream(clip: AudioClip): LayerStream? = synchronized(streams) { streams[clip.id]?.takeIf { it.uri == clip.uri } }

    private fun keepStreams(t0: Double) {
        val list = clips
        synchronized(streams) {
            val wanted = HashSet<Long>()
            for (clip in list) {
                if (t0 + AHEAD_MS < clip.startMs || t0 > clip.endMs + BEHIND_MS) continue
                wanted += clip.id
                val pos = clip.wrap(clip.mediaAt(max(t0, clip.startMs.toDouble())))
                val loopMs = clip.inMs.takeIf { clip.loop }
                var s = streams[clip.id]
                if (s != null && (s.uri != clip.uri || s.loopMs != loopMs)) { s.close(); s = null }
                if (s == null) streams[clip.id] = LayerStream(clip.uri, pos - LayerStream.LEAD_MS, loopMs) { PcmDecoder(context, clip.uri) }
                else if (t0 < clip.startMs) s.prepare(pos)
            }
            streams.keys.filter { it !in wanted }.forEach { streams.remove(it)?.close() }
        }
    }

    private companion object {
        const val BLOCK = 1024
        const val AHEAD_MS = 1500.0
        const val BEHIND_MS = 300.0
        const val IDLE_MS = 8000L
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    }
}

internal fun mixStreams(
    clips: List<AudioClip>, out: FloatArray, frames: Int, t0: Double, rate: Int, quality: Int, rack: FxRack,
    stream: (AudioClip) -> LayerStream?,
) {
    java.util.Arrays.fill(out, 0, 2 * frames, 0f)
    val dt = 1000.0 / rate
    for (clip in clips) {
        stream(clip)?.let { clip.resolveDuration(it.durationUs) }
        val dst = rack.target(clip, out, frames)
        var rendered = false
        var next = 0
        while (true) {
            val span = clip.span(t0, frames, dt, next) ?: break
            next = span.to
            val source = stream(clip) ?: break
            if (!span.inMedia) { source.restart(); continue }
            if (source.mix(dst, span.from, span.to, span.pos, span.step, span.g0, span.g1, quality, span.clipMs0, dt, span.lengthMs)) rendered = true
        }
        rack.finish(clip, out, frames, rendered)
    }
    rack.sweep()
}

internal class AudioClock(private val startMs: Double, private val rate: Int) {
    private class Stamp(val frames: Long, val nanos: Long)
    @Volatile private var stamp: Stamp? = null
    @Volatile private var stoppedAt = 0L
    @Volatile private var reached = startMs
    @Volatile var wallStart = 0L
    @Volatile var failed = false
    @Volatile var latencyNs = -1L
    @Volatile var route = 0
    @Volatile private var measured = false

    fun stamp(frames: Long, nanos: Long) {
        if (!measured && frames > 0 && wallStart > 0 && !failed) { learned[route] = startLatency(frames, nanos, wallStart, rate); measured = true }
        stamp = Stamp(frames, nanos)
    }

    fun stop(now: Long) { if (stoppedAt == 0L) stoppedAt = now }

    fun positionMs(now: Long = System.nanoTime()): Double? {
        val at = stoppedAt.takeIf { it != 0L } ?: now
        val s = stamp
        val began = wallStart
        val lag = if (failed) 0L else latencyNs
        val ms = when {
            s != null -> startMs + (s.frames + (at - s.nanos) * rate / 1e9).coerceAtLeast(0.0) * 1000.0 / rate
            began > 0 && (lag >= 0 || at - began > 1_500_000_000L) -> startMs + (at - began - lag.coerceAtLeast(0)).coerceAtLeast(0) / 1e6
            else -> return null
        }
        return maxOf(ms, reached).also { reached = it }
    }

    companion object {
        const val MAX_LATENCY_NS = 1_000_000_000L
        val learned = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        fun startLatency(frames: Long, nanos: Long, wallStart: Long, rate: Int): Long =
            (nanos - frames * 1_000_000_000L / rate - wallStart).coerceIn(0L, MAX_LATENCY_NS)

        fun outputLatency(track: AudioTrack, rate: Int): Long = runCatching {
            val ms = AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int
            (ms * 1_000_000L - track.bufferSizeInFrames * 1_000_000_000L / rate).coerceIn(0L, MAX_LATENCY_NS)
        }.getOrDefault(-1L)
    }
}
