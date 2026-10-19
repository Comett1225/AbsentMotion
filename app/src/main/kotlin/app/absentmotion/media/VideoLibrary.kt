package app.absentmotion.media

import android.content.Context
import android.util.Log
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
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
    private data class Request(val scene: Scene, val timeMs: Int, val playing: Boolean) { val born = System.nanoTime() }

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

    suspend fun <T> withExact(scene: Scene, timeMs: Int, draw: suspend () -> T): T = lock.withLock {
        paused = true
        try {
            val request = Request(scene, timeMs, false)
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

    private companion object { const val MAX_LEAD_MS = 100.0 }

    private inner class Session(val id: Long, val uri: String) {
        private val decoderLock = Mutex()
        private val queue = Channel<Pair<Request, Layer>>(Channel.CONFLATED)
        private var decoder: VideoDecoder? = null
        private var failed = false
        @Volatile private var stopped = false
        private var publishedPts: Long? = null
        private val worker = scope.launch {
            try {
                for ((request, layer) in queue) {
                    if (paused || stopped) continue
                    try { decode(request, layer, exact = false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Log.w("AMVideo", "Cannot decode $uri layer $id", e)
                        decoderLock.withLock { decoder?.close(); decoder = null; failed = true }
                        engine.setPicture(uri, null, id)
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
            if (stopped || failed) {
                check(!exact) { "Video is unavailable: $uri" }
                return@withLock
            }
            fun obsolete(): Boolean = !exact && latest.let { now ->
                paused || stopped || ((!request.playing || now?.playing != true) && now !== request) ||
                    now.scene != request.scene || now.scene.layer(id)?.activeAt(now.timeMs) != true
            }
            if (obsolete()) return@withLock
            val source = decoder ?: VideoDecoder(context, uri).also { decoder = it }
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
                    if (!exact && request.playing) leadMs = (leadMs * 0.8 + (System.nanoTime() - request.born) / 1e6 * 0.2).coerceIn(0.0, MAX_LEAD_MS)
                }
            }
        }

        suspend fun close() {
            stopped = true
            queue.close()
            worker.cancel(); worker.join()
            engine.setPicture(uri, null, id)
        }
    }
}
