package app.absentmotion.audio

import android.content.Context
import android.media.AudioAttributes
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

    init {
        ExportAudio.mixFor = { scene -> OfflineMix.of(this.context, scene, quality) }
    }

    fun setScene(scene: Scene) {
        if (!closed) clips = AudioClip.of(scene)
    }

    fun play(startMs: Double): Boolean {
        val previous = session
        previous?.halt()
        if (closed || clips.none { it.endMs > startMs }) { session = null; return false }
        main.removeCallbacks(releaseIdle)
        releasing = false
        session = Session(startMs, previous).also { it.start() }
        return true
    }

    fun stop() {
        session?.halt()
        level = 0f
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_MS)
    }

    fun frameShown(frame: Int, ms: Int) { shownFrame = frame; shownMs = ms }

    fun positionMs(): Double? {
        val s = session ?: return null
        val now = s.stoppedAt.takeIf { it != 0L } ?: System.nanoTime()
        val clock = s.clock
        if (clock != null) return s.startMs + (clock.frames + (now - clock.nanos) * s.rate / 1e9).coerceAtLeast(0.0) * 1000.0 / s.rate
        val began = s.wallStart
        if (began > 0 && (s.failed || now - began > 1_500_000_000L)) return s.startMs + (now - began) / 1e6
        return null
    }

    fun release() {
        releasing = true
        session?.halt()
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

    private inner class Session(val startMs: Double, private val previous: Session?) : Thread("am-audio-mixer") {
        @Volatile var stopRequested = false
        @Volatile var stoppedAt = 0L
        @Volatile var clock: Clock? = null
        @Volatile var wallStart = 0L
        @Volatile var failed = false
        @Volatile var rate = 48_000

        fun halt() {
            if (stoppedAt == 0L) stoppedAt = System.nanoTime()
            stopRequested = true
        }

        init {
            rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            previous?.join()
            var track: AudioTrack? = null
            try {
                val bytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                val bufferFrames = max(bytes / 8, BLOCK * 6)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
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
                track.play()
                wallStart = System.nanoTime()
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
                        clock = Clock(stamp.framePosition, stamp.nanoTime)
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
                failed = true
                if (wallStart == 0L) wallStart = System.nanoTime()
                while (!stopRequested) Thread.sleep(20)
            } finally {
                runCatching { track?.release() }
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
            java.util.Arrays.fill(out, 0f)
            val dt = 1000.0 / rate
            val q = quality
            for (clip in clips) {
                var next = 0
                while (true) {
                    val span = clip.span(t0, BLOCK, dt, next) ?: break
                    next = span.to
                    val stream = stream(clip) ?: break
                    if (!span.inMedia) { stream.restart(); continue }
                    stream.mix(out, span.from, span.to, span.pos, span.step, span.g0, span.g1, q, span.clipMs0, dt, span.lengthMs)
                }
            }
            val peak = Voice.finish(out, out.size)
            level = peak
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
                var s = streams[clip.id]
                if (s != null && s.uri != clip.uri) { s.close(); s = null }
                if (s == null) streams[clip.id] = LayerStream(context, clip.uri, pos - LayerStream.LEAD_MS)
                else if (t0 < clip.startMs) s.prepare(pos)
            }
            streams.keys.filter { it !in wanted }.forEach { streams.remove(it)?.close() }
        }
    }

    private class Clock(val frames: Long, val nanos: Long)

    private companion object {
        const val BLOCK = 1024
        const val AHEAD_MS = 1500.0
        const val BEHIND_MS = 300.0
        const val IDLE_MS = 8000L
    }
}
