package app.absentmotion.media

import android.content.Context
import android.util.Log
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.model.FramePair
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.exportFrameMs
import app.absentmotion.model.framePair
import app.absentmotion.model.needsFrameInterpolation
import app.absentmotion.model.videoTimeUs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object DebugFrameInterpolation {
    @Volatile var divisor = 4
    @Volatile var levels = 5
    @Volatile var iterations = 6
    @Volatile var warps = 1
    @Volatile var alpha = 0.15f
    @Volatile var fuse = 4

    internal fun params() = floatArrayOf(divisor.toFloat(), levels.toFloat(), iterations.toFloat(), warps.toFloat(), alpha, fuse.toFloat())
}

class VideoLibrary(context: Context, private val engine: EngineThread) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val lock = Mutex()
    private val sessions = mutableMapOf<Long, Session>()
    @Volatile private var latest: Request? = null
    @Volatile private var paused = false
    @Volatile private var closed = false
    @Volatile private var leadMs = 0.0
    private data class Request(
        val scene: Scene, val timeMs: Int, val playing: Boolean,
        val interpolation: FrameInterpolation = FrameInterpolation.Off, val fps: Int = 0,
    ) { val born = System.nanoTime() }

    init {
        scope.launch {
            for (request in requests) lock.withLock {
                if (paused || request !== latest) return@withLock
                reconcile(request)
                for (layer in visible(request)) sessions[layer.id]?.request(request, layer)
            }
        }
    }

    fun update(scene: Scene, timeMs: Int, playing: Boolean = false) {
        if (closed) return
        val next = Request(scene, timeMs, playing)
        if (latest == next) return
        latest = next
        if (!paused) requests.trySend(next)
    }

    private fun visible(request: Request) = request.scene.layers.filter { it.activeAt(request.timeMs) && it.mediaFill?.video == true }

    private suspend fun reconcile(request: Request) {
        val wanted = visible(request).associateBy { it.id }
        for ((id, session) in sessions.toMap()) {
            if (wanted[id]?.mediaFill?.uri != session.uri) {
                sessions.remove(id)
                session.close()
            }
        }
        for ((id, layer) in wanted) sessions.getOrPut(id) { Session(id, layer.mediaFill!!.uri) }
    }

    suspend fun <T> withExact(
        scene: Scene, timeMs: Int, interpolation: FrameInterpolation = FrameInterpolation.Off, fps: Int = 0, draw: suspend () -> T,
    ): T = lock.withLock {
        paused = true
        try {
            val request = Request(scene, timeMs, false, interpolation, fps)
            reconcile(request)
            withContext(Dispatchers.IO) {
                visible(request).map { layer -> async { sessions.getValue(layer.id).exact(request, layer) } }.awaitAll()
            }
            draw()
        } finally {
            paused = false
            latest?.let { requests.trySend(it) }
        }
    }

    fun resumePreview() { latest?.let { requests.trySend(it) } }

    fun suspendPreview() {
        latest = null
        scope.launch {
            lock.withLock {
                if (latest == null) { sessions.values.forEach { it.close() }; sessions.clear() }
            }
        }
    }

    suspend fun retry() = lock.withLock {
        sessions.values.forEach { it.close() }
        sessions.clear()
        latest?.let { requests.trySend(it) }
    }

    suspend fun close() {
        closed = true; latest = null
        requests.close()
        lock.withLock { sessions.values.forEach { it.close() }; sessions.clear() }
        scope.cancel()
    }

    private companion object {
        const val MAX_LEAD_MS = 100.0
        const val RETRY_NS = 1_000_000_000L
    }

    private inner class Session(val id: Long, val uri: String) {
        private val decoderLock = Mutex()
        private val queue = Channel<Pair<Request, Layer>>(Channel.CONFLATED)
        private var decoder: VideoDecoder? = null
        private var retryAt = 0L
        @Volatile private var stopped = false
        private var publishedPts: Long? = null
        private var publishedMix: Pair<FramePair, FrameInterpolation>? = null
        private val held = mutableSetOf<Long>()
        private val worker = scope.launch {
            try {
                for ((request, layer) in queue) {
                    if (paused || stopped) continue
                    try { decode(request, layer, exact = false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Log.w("AMVideo", "Cannot decode $uri layer $id", e)
                        decoderLock.withLock { decoder?.close(); decoder = null; retryAt = System.nanoTime() + RETRY_NS }
                    }
                }
            } finally {
                withContext(NonCancellable) { decoderLock.withLock { decoder?.close(); decoder = null } }
            }
        }

        fun request(request: Request, layer: Layer) { queue.trySend(request to layer) }
        suspend fun exact(request: Request, layer: Layer) {
            try { decode(request, layer, exact = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AMVideo", "Cannot decode $uri layer $id at ${request.timeMs} ms", e)
                decoderLock.withLock { decoder?.close(); decoder = null }
            }
        }

        private suspend fun decode(request: Request, layer: Layer, exact: Boolean) = decoderLock.withLock {
            if (stopped) {
                check(!exact) { "Video is unavailable: $uri" }
                return@withLock
            }
            if (!exact && System.nanoTime() < retryAt) return@withLock
            fun obsolete(): Boolean = !exact && latest.let { now ->
                paused || stopped || ((!request.playing || now?.playing != true) && now !== request) ||
                    now.scene != request.scene || now.scene.layer(id)?.activeAt(now.timeMs) != true
            }
            if (obsolete()) return@withLock
            val source = decoder ?: VideoDecoder(context, uri).also { decoder = it }
            if (exact && request.interpolation != FrameInterpolation.Off && mixed(source, request, layer)) return@withLock
            val time = layer.videoTimeUs(request.timeMs + if (request.playing) leadMs.toInt() else 0, request.scene)
            val pts = source.wantedPts(time)
            if (publishedPts == pts) return@withLock
            val image = source.frame(time, ::obsolete) ?: return@withLock
            if (obsolete()) return@withLock
            image.hardwareBuffer!!.use { buffer ->
                val installed = engine.setVideoPicture(uri, buffer, id, exact) { !obsolete() }
                if (exact) check(installed) { "Cannot import video frame $pts for $uri" }
                if (installed) {
                    publishedPts = pts
                    publishedMix = null
                    held.clear()
                    if (!exact && request.playing) leadMs = (leadMs * 0.8 + (System.nanoTime() - request.born) / 1e6 * 0.2).coerceIn(0.0, MAX_LEAD_MS)
                }
            }
        }

        private suspend fun mixed(source: VideoDecoder, request: Request, layer: Layer): Boolean {
            if (request.fps <= 0) return false
            val stamps = source.timestamps
            val timeMs = exportFrameMs(request.timeMs, request.fps)
            if (!layer.needsFrameInterpolation(timeMs, request.scene, stamps, request.fps)) return false
            val pair = layer.framePair(timeMs, request.scene, stamps)
            val key = pair to request.interpolation
            if (publishedMix == key) return true
            for (pts in setOf(pair.a, pair.b)) {
                if (pts in held) continue
                val image = source.frame(pts) { false } ?: return false
                if (image.timestamp / 1000 != pts) return false
                if (!image.hardwareBuffer!!.use { engine.setVideoFrame(uri, it, id, pts) }) return false
                held += pts
            }
            if (!engine.interpolateVideo(uri, id, pair.a, pair.b, pair.t, request.interpolation == FrameInterpolation.Flow,
                    DebugFrameInterpolation.params())) {
                held.clear()
                return false
            }
            held.retainAll(setOf(pair.a, pair.b))
            publishedMix = key
            publishedPts = null
            return true
        }

        suspend fun close() {
            stopped = true
            queue.close()
            worker.cancel(); worker.join()
            engine.setPicture(uri, null, id)
        }
    }
}
