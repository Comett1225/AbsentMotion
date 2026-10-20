package app.absentmotion.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.ExportError
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.model.FramePair
import app.absentmotion.model.Layer
import app.absentmotion.model.LayerTrack
import app.absentmotion.model.MoshPlan
import app.absentmotion.model.layerTracks
import app.absentmotion.model.moshPlan
import app.absentmotion.model.Scene
import app.absentmotion.model.exportFrame
import app.absentmotion.model.exportFrameMs
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
        val interpolation: FrameInterpolation = FrameInterpolation.Off, val fps: Int = 0, val warm: Boolean = false,
    ) { val born = System.nanoTime() }

    init {
        scope.launch {
            for (request in requests) lock.withLock {
                if (paused || request !== latest) return@withLock
                if (proxiesFor !== request.scene.node) {
                    proxiesFor = request.scene.node
                    request.scene.videoUris().forEach { VideoProxies.request(context, it) }
                }
                reconcile(request)
                for (layer in visible(request)) sessions[layer.layer.id]?.request(request, layer)
            }
        }
    }

    private var proxiesFor: Any? = null

    fun update(scene: Scene, timeMs: Int, playing: Boolean = false) {
        if (closed) return
        val next = Request(scene, timeMs, playing, warm = true)
        if (latest == next) return
        latest = next
        if (!paused) requests.trySend(next)
    }

    @Volatile private var shown: Pair<Request, List<VideoSample>>? = null

    private fun visible(request: Request) = shown?.takeIf { it.first === request }?.second ?: request.scene.videoSamples(request.timeMs, request.playing, request.warm).also { shown = request to it }

    private suspend fun reconcile(request: Request) {
        val wanted = visible(request).associateBy { it.layer.id }
        for ((id, session) in sessions.toMap()) {
            if (wanted[id]?.layer?.mediaFill?.uri != session.uri) {
                sessions.remove(id)
                session.close()
            }
        }
        for ((id, layer) in wanted) sessions.getOrPut(id) { Session(id, layer.layer.mediaFill!!.uri) }
    }

    suspend fun <T> withExact(
        scene: Scene, timeMs: Int, interpolation: FrameInterpolation = FrameInterpolation.Off, fps: Int = 0, draw: suspend () -> T,
    ): T = lock.withLock {
        paused = true
        try {
            val request = Request(scene, timeMs, false, interpolation, fps)
            reconcile(request)
            withContext(Dispatchers.IO) {
                visible(request).map { layer -> async { sessions.getValue(layer.layer.id).exact(request, layer) } }.awaitAll()
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
        VideoProxies.forgetChecks()
        proxiesFor = null
        for ((id, session) in sessions.toMap()) {
            if (session.failed || session.generation != engine.generation || session.version != fileVersion(context.contentResolver, Uri.parse(session.uri))) {
                sessions.remove(id)
                session.close()
            }
        }
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
        private val queue = Channel<Pair<Request, VideoSample>>(Channel.CONFLATED)
        private var decoder: VideoDecoder? = null
        private var source: String? = null
        private var proxy: String? = null
        private var proxyRevision = -1
        val generation = engine.generation
        val version = fileVersion(context.contentResolver, Uri.parse(uri))
        @Volatile private var retryAt = 0L
        val failed get() = retryAt != 0L
        @Volatile private var stopped = false
        private var publishedPts: Long? = null
        private var publishedMix: Pair<FramePair, FrameInterpolation>? = null
        private var publishedMosh: Pair<List<Long>, List<Float>>? = null
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

        fun request(request: Request, sample: VideoSample) { queue.trySend(request to sample) }
        suspend fun exact(request: Request, sample: VideoSample) {
            try { decode(request, sample, exact = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AMVideo", "Cannot decode $uri layer $id at ${request.timeMs} ms", e)
                decoderLock.withLock { decoder?.close(); decoder = null }
                throw e
            }
        }

        private suspend fun decode(request: Request, sample: VideoSample, exact: Boolean) = decoderLock.withLock {
            val layer = sample.layer
            if (stopped) {
                exportCheck(!exact) { txt(R.string.media_error_video_unavailable, uri) }
                return@withLock
            }
            if (!exact && System.nanoTime() < retryAt) return@withLock
            fun obsolete(): Boolean = !exact && latest.let { now ->
                paused || stopped || ((!request.playing || now?.playing != true) && now !== request) ||
                    now?.scene != request.scene || visible(now).none { it.layer.id == id }
            }
            fun abandoned(): Boolean = !exact && latest.let { now -> now == null || paused || stopped || visible(now).none { it.layer.id == id } }
            if (obsolete()) return@withLock
            if (!exact && proxyRevision != VideoProxies.revision.value) {
                proxyRevision = VideoProxies.revision.value
                proxy = VideoProxies.ready(context, uri, version)
            }
            val wanted = if (exact) uri else proxy ?: uri
            if (this.source != wanted) { decoder?.close(); decoder = null }
            val source = decoder ?: open(wanted).also { decoder = it }
            val plan = layer.moshPlan(sample.timeMs, sample.scene)
            if (plan != null && moshed(source, sample, plan, exact, ::abandoned)) return@withLock
            if (exact && request.interpolation != FrameInterpolation.Off && mixed(source, request, sample)) return@withLock
            val ahead = if (request.playing) leadMs * (sample.track.timeAt(request.timeMs.toDouble(), false)?.rate ?: 1.0) else 0.0
            val time = layer.videoTimeUs((sample.timeMs + ahead).toInt(), sample.scene)
            val pts = source.wantedPts(time)
            if (publishedPts == pts) return@withLock
            val image = source.frame(time, ::abandoned) ?: run {
                exportCheck(!exact) { txt(R.string.media_error_decode_frame, pts.toString()) }
                return@withLock
            }
            if (abandoned()) return@withLock
            image.hardwareBuffer!!.use { buffer ->
                val installed = engine.setVideoPicture(uri, buffer, id, exact, source.colorStandard, source.colorRange, image.cropRect) { !abandoned() }
                if (exact) exportCheck(installed) { txt(R.string.media_error_import_frame, pts.toString(), uri) }
                if (installed) {
                    publishedPts = pts
                    publishedMix = null
                    publishedMosh = null
                    held.clear()
                    retryAt = 0L
                    if (!exact && request.playing) leadMs = (leadMs * 0.8 + (System.nanoTime() - request.born) / 1e6 * 0.2).coerceIn(0.0, MAX_LEAD_MS)
                }
            }
        }

        private fun open(wanted: String): VideoDecoder = try {
            VideoDecoder(context, wanted).also { source = wanted }
        } catch (e: Exception) {
            if (wanted == uri) throw e
            Log.w("AMVideo", "Cannot open the preview copy of $uri, using the original", e)
            proxy = null
            VideoDecoder(context, uri).also { source = uri }
        }

        private suspend fun mixed(source: VideoDecoder, request: Request, sample: VideoSample): Boolean {
            val layer = sample.layer
            if (request.fps <= 0) {
                Log.w("AMInterp", "Interpolation skipped: export frame rate is unavailable")
                return false
            }
            val stamps = source.timestamps
            if (stamps.isEmpty()) {
                Log.w("AMInterp", "Interpolation skipped: source frame timestamps are unavailable for layer $id")
                return false
            }
            val mapped = sample.track.timeAt(exportFrameMs(request.timeMs, request.fps), false) ?: return false
            val pair = layer.exportFrame(mapped.ms, sample.scene, stamps, request.fps, mapped.rate) ?: return false
            val key = pair to request.interpolation
            if (publishedMix == key) return true
            for (pts in setOf(pair.a, pair.b)) {
                if (pts in held) continue
                val image = source.frame(pts) { false } ?: throw ExportError(txt(R.string.media_error_interp_source, pts.toString()))
                exportCheck(image.timestamp / 1000 == pts) { txt(R.string.media_error_interp_wrong_frame, pts.toString()) }
                exportCheck(image.hardwareBuffer!!.use { engine.setVideoFrame(uri, it, id, pts, source.colorStandard, source.colorRange, image.cropRect) }) { txt(R.string.media_error_interp_import, pts.toString()) }
                held += pts
            }
            if (!engine.interpolateVideo(uri, id, pair.a, pair.b, pair.t, request.interpolation == FrameInterpolation.Flow,
                    DebugFrameInterpolation.params())) {
                held.clear()
                throw ExportError(txt(R.string.media_error_interp_render, request.interpolation.label))
            }
            held.retainAll(setOf(pair.a, pair.b))
            publishedMix = key
            publishedPts = null
            publishedMosh = null
            return true
        }

        private suspend fun moshed(source: VideoDecoder, sample: VideoSample, plan: MoshPlan, exact: Boolean, abandoned: () -> Boolean): Boolean {
            val times = plan.frames.map { sample.layer.videoTimeUs(it, sample.scene) }
            val pts = times.map { source.wantedPts(it) }.toLongArray()
            val params = plan.params + if (this.source == uri) 0f else 1f
            val key = pts.toList() to params.toList()
            if (publishedMosh == key) return true
            val from = engine.moshStart(uri, id, pts, params, DebugFrameInterpolation.params(), exact)
            if (from < 0) return false
            for (step in from until pts.size) {
                if (abandoned()) return true
                val image = source.frame(times[step], abandoned) ?: run {
                    check(!exact) { "Cannot decode datamosh frame ${pts[step]}" }
                    return true
                }
                val done = image.hardwareBuffer!!.use { buffer ->
                    engine.moshFrame(uri, buffer, id, step, step == pts.size - 1, exact, source.colorStandard, source.colorRange, image.cropRect) { !abandoned() }
                }
                if (!done) {
                    check(!exact) { "Cannot datamosh video frame ${pts[step]} for $uri" }
                    return true
                }
            }
            publishedMosh = key
            publishedPts = null
            publishedMix = null
            held.clear()
            retryAt = 0L
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

internal fun Scene.videoUris(): Set<String> =
    layerTracks().mapNotNullTo(LinkedHashSet()) { track -> track.layer.mediaFill?.takeIf { it.video && !isMissing(it.uri) }?.uri }

internal data class VideoSample(val track: LayerTrack, val timeMs: Double) {
    val layer get() = track.layer
    val scene get() = track.scene
}

private const val WARM_MS = 1000

internal fun Scene.videoSamples(timeMs: Int, playing: Boolean, warm: Boolean): List<VideoSample> {
    val tracks = layerTracks().filter { track -> track.layer.mediaFill?.let { it.video && !isMissing(it.uri) } == true }
    fun at(ms: Int, among: List<LayerTrack>) = among.mapNotNull { track -> track.timeAt(ms.toDouble(), true)?.let { VideoSample(track, it.ms) } }
    val now = at(timeMs, tracks)
    if (!warm) return now
    val rest = tracks.filter { track -> now.none { it.track === track } && track.endMs > timeMs - WARM_MS && track.startMs <= timeMs + WARM_MS }
    if (rest.isEmpty()) return now
    val frame = frameAt(timeMs)
    val steps = if (playing) listOf(1) else listOf(1, -1)
    val near = steps.flatMap { step ->
        (1..fps * WARM_MS / 1000).asSequence().map { frame + step * it }.takeWhile { it >= 0 }
            .map { at(frameStart(it), rest) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }
    return now + near.distinctBy { it.layer.id }.filter { sample -> now.none { it.layer.id == sample.layer.id } }
}
