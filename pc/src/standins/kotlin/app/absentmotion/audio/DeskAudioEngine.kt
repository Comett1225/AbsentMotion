package app.absentmotion.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absentmotion.export.ExportAudio
import app.absentmotion.model.Scene
import kotlin.math.max

class AudioEngine(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var clips: List<AudioClip> = emptyList()
    private val streams = HashMap<Long, LayerStream>()
    private val sounds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val checking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val output = DeskOutput()
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var releasing = false
    private val releaseIdle = Runnable { if (session?.stopRequested != false) { closeStreams(); closeOutput() } }

    @Volatile var quality = Resampler.ULTRA

    @Volatile var log = false

    val mix = LiveMix()

    val level: Float get() = mix.master

    @Volatile private var shownFrame = -1
    @Volatile private var shownMs = 0

    @Volatile var interrupted = false
        private set

    @Volatile private var lastScene: Scene? = null
    private val blipsReady: () -> Unit = { main.post { lastScene?.let(::setScene) } }

    init {
        TextBlips.dir = java.io.File(this.context.cacheDir, "text-blips")
        TextBlips.listeners += blipsReady
        ExportAudio.mixFor = { scene, rate -> OfflineMix.of(this.context, scene, Resampler.ULTRA, rate) }
        DeskOutput.warm()
    }

    fun setScene(scene: Scene) {
        if (closed) return
        lastScene = scene
        val next = AudioClip.of(scene, wait = false)
        clips = next
        for (clip in next) {
            sounds.putIfAbsent(clip.uri, true)
            if (checking.add(clip.uri)) probe.execute {
                checking.remove(clip.uri)
                val version = runCatching { android.net.Uri.parse(clip.uri).toFile()?.let { it.lastModified() * 31 + it.length() } }.getOrNull() ?: 0L
                if (versions.put(clip.uri, version) != version) sounds[clip.uri] = OfflineMix.hasSoundTrack(context, clip.uri)
            }
        }
    }

    fun play(startMs: Double): Boolean {
        val previous = session
        previous?.halt()
        if (closed || !clips.audibleAfter(startMs, sounds::get)) { session = null; return false }
        interrupted = false
        main.removeCallbacks(releaseIdle)
        releasing = false
        synchronized(streams) { streams.dropFailed() }
        session = Session(startMs, previous).also { it.start() }
        return true
    }

    fun stop() {
        session?.halt()
        mix.master(0f, 0f)
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_MS)
    }

    fun frameShown(frame: Int, ms: Int) { shownFrame = frame; shownMs = ms }

    fun positionMs(): Double? = session?.clock?.positionMs()

    fun release() {
        releasing = true
        session?.halt()
        mix.master(0f, 0f)
        main.removeCallbacks(releaseIdle)
        closeStreams()
        closeOutput()
    }

    fun close() {
        closed = true
        TextBlips.listeners -= blipsReady
        lastScene = null
        clips = emptyList()
        release()
    }

    private fun closeStreams() = synchronized(streams) {
        streams.values.forEach { it.close() }
        streams.clear()
    }

    private fun closeOutput() = output.close()

    private inner class Session(val startMs: Double, @Volatile private var previous: Session?) : Thread("am-audio-mixer") {
        @Volatile var stopRequested = false
        val rate = DeskOutput.nativeRate
        val clock = DeskClock(startMs, rate)
        private val rack = FxRack(rate)
        private val limiter = SafeLimiter(rate)

        init { priority = MAX_PRIORITY; isDaemon = true }

        fun halt() {
            clock.stop(System.nanoTime())
            stopRequested = true
        }

        override fun run() {
            previous?.join()
            previous = null
            try {
                if (stopRequested) return
                keepStreams(startMs)
                output.open()
                if (stopRequested) return
                clock.latencyNs = DeskOutput.latencyNs()
                preroll()
                val out = FloatArray(BLOCK * 2)
                var written = 0L
                clock.base = output.written
                repeat(PRIME) {
                    mixBlock(out, startMs + written * 1000.0 / rate)
                    output.write(out, BLOCK)
                    written += BLOCK
                }
                clock.wallStart = System.nanoTime()
                clock.observe(output.position(), System.nanoTime())
                while (!stopRequested) {
                    val t0 = startMs + written * 1000.0 / rate
                    keepStreams(t0)
                    mixBlock(out, t0)
                    output.write(out, BLOCK)
                    clock.observe(output.position(), System.nanoTime())
                    if (log) Log.i("AMAudio", "block projectMs=%.3f playheadFrame=%d playheadMs=%d clockMs=%.3f".format(java.util.Locale.ROOT, t0, shownFrame, shownMs, clock.positionMs() ?: -1.0))
                    written += BLOCK
                }
                mixBlock(out, startMs + written * 1000.0 / rate)
                for (i in 0 until FADE) { val g = 1f - (i + 1f) / FADE; out[2 * i] *= g; out[2 * i + 1] *= g }
                output.write(out, FADE)
            } catch (e: Exception) {
                Log.w("AMAudio", "Audio stream failed", e)
                clock.failed = true
                if (clock.wallStart == 0L) clock.wallStart = System.nanoTime()
                output.close()
                while (!stopRequested) sleep(20)
            } finally {
                rack.close()
                mix.master(0f, 0f)
                if (releasing) { closeStreams(); closeOutput() }
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
                sleep(2)
            }
            synchronized(streams) { streams.values.forEach { it.restart() } }
        }

        private fun mixBlock(out: FloatArray, t0: Double) {
            mixStreams(clips, out, BLOCK, t0, rate, quality, rack, ::stream)
            limiter.process(out, BLOCK)
            mix.master(limiter.left, limiter.right)
        }
    }

    private fun stream(clip: AudioClip): LayerStream? = synchronized(streams) { streams[clip.id]?.takeIf { it.uri == clip.uri } }

    private fun keepStreams(t0: Double) {
        val list = clips
        synchronized(streams) {
            val wanted = HashSet<Long>()
            for (clip in list) {
                if (t0 + AHEAD_MS < clip.startMs || t0 > clip.endMs + BEHIND_MS || sounds[clip.uri] == false) continue
                wanted += clip.id
                val (pos, early) = clip.cue(t0)
                val loopMs = clip.inMs.takeIf { clip.loop }
                var s = streams[clip.id]
                if (s != null && (s.uri != clip.uri || s.loopMs != loopMs)) { s.close(); s = null }
                if (s == null) streams[clip.id] = LayerStream(clip.uri, pos - LayerStream.LEAD_MS, loopMs) { PcmDecoder(context, clip.uri) }
                else if (early) s.prepare(pos)
            }
            streams.keys.filter { it !in wanted }.forEach { streams.remove(it)?.close() }
        }
    }

    private companion object {
        const val BLOCK = 512
        val PRIME = if (app.absentmotion.desk.DeskOs.windows) 3 else 2
        const val FADE = 256
        const val AHEAD_MS = 1500.0
        const val BEHIND_MS = 300.0
        const val IDLE_MS = 8000L
        val probe: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "am-audio-probe").apply { isDaemon = true } }
    }
}

internal fun List<AudioClip>.audibleAfter(startMs: Double, sound: (String) -> Boolean?): Boolean = any { it.endMs > startMs && sound(it.uri) != false }

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
