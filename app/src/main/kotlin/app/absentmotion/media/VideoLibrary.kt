package app.absentmotion.media

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.ai.AiSmooth
import app.absentmotion.ai.argb
import android.net.Uri
import android.util.Log
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.ExportError
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.model.FramePair
import app.absentmotion.model.Layer
import app.absentmotion.model.LayerTrack
import app.absentmotion.model.MoshPlan
import app.absentmotion.model.layerTracks
import app.absentmotion.model.moshPlan
import app.absentmotion.model.Scene
import app.absentmotion.model.exportFrameMs
import app.absentmotion.model.localMs
import app.absentmotion.model.needs
import app.absentmotion.model.pairAround
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.videoTimeUs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        val smoothing: Smoothing = Smoothing(), val fps: Int = 0, val warm: Boolean = false,
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
        scene: Scene, timeMs: Int, smoothing: Smoothing = Smoothing(), fps: Int = 0, draw: suspend () -> T,
    ): T = lock.withLock {
        paused = true
        try {
            val request = Request(scene, timeMs, false, smoothing, fps)
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
        const val MAX_RETRIES = 3
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
        @Volatile private var failures = 0
        val failed get() = retryAt != 0L
        @Volatile private var stopped = false
        private var publishedPts: Long? = null
        private var publishedMix: Pair<SmoothFrame, Smoothing>? = null
        private var publishedMosh: Pair<List<Long>, List<Float>>? = null
        private val held = mutableSetOf<Long>()
        private var aiSmooth: AiSmooth? = null
        private var aiReader: Pair<String, FrameReader>? = null
        private val aiFrames = HashMap<Long, Pair<IntArray, Pair<Int, Int>>>()
        private val worker = scope.launch {
            try {
                for ((request, layer) in queue) {
                    if (paused || stopped) continue
                    try { decode(request, layer, exact = false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Log.w("AMVideo", "Cannot decode $uri layer $id", e)
                        decoderLock.withLock { decoder?.close(); decoder = null; retryAt = System.nanoTime() + RETRY_NS }
                        if (++failures <= MAX_RETRIES) scope.launch {
                            delay(RETRY_NS / 1_000_000 + 10)
                            if (!stopped) latest?.let { requests.trySend(it) }
                        }
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
                    now.scene != request.scene || visible(now).none { it.layer.id == id }
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
            if (exact && request.smoothing.active && mixed(source, request, sample)) return@withLock
            val ahead = if (request.playing) leadMs * (sample.track.timeAt(request.timeMs.toDouble(), false)?.rate ?: 1.0) else 0.0
            val time = layer.videoTimeUs((sample.timeMs + ahead).toInt(), sample.scene)
            val pts = source.wantedPts(time)
            if (publishedPts == pts) return@withLock
            val seeks = source.seeks
            val image = source.frame(time, exact, ::abandoned) ?: (if (exact) source.frame(time, true, ::abandoned) else null) ?: run {
                exportCheck(!exact) { txt(R.string.media_error_decode_frame, source.timestamps.frameNumber(pts)) }
                return@withLock
            }
            if (abandoned()) return@withLock
            image.hardwareBuffer!!.use { buffer ->
                val installed = engine.setVideoPicture(uri, buffer, id, exact, source.colorStandard, source.colorRange, image.cropRect) { !abandoned() }
                if (exact) exportCheck(installed) { txt(R.string.media_error_import_frame, source.timestamps.frameNumber(pts), uri) }
                if (installed) {
                    publishedPts = pts
                    publishedMix = null
                    publishedMosh = null
                    held.clear()
                    retryAt = 0L
                    failures = 0
                    if (!exact && request.playing && source.seeks == seeks) leadMs = (leadMs * 0.8 + (System.nanoTime() - request.born) / 1e6 * 0.2).coerceIn(0.0, MAX_LEAD_MS)
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
            val smoothing = request.smoothing
            val frame = layer.smoothFrame(mapped.ms, sample.scene, stamps, request.fps, mapped.rate, smoothing) {
                source.wantedPts(layer.videoTimeUs(sample.timeMs.toInt(), sample.scene))
            } ?: return false
            val key = frame to smoothing
            if (publishedMix == key) return true
            val wanted = setOf(frame.before, frame.pair.a, frame.pair.b, frame.after)
            for (pts in wanted) {
                if (pts in held) continue
                val image = source.frame(pts, true) { false } ?: throw ExportError(txt(R.string.media_error_interp_source, stamps.frameNumber(pts)))
                exportCheck(image.timestamp / 1000 == pts) { txt(R.string.media_error_interp_wrong_frame, stamps.frameNumber(pts)) }
                exportCheck(image.hardwareBuffer!!.use { engine.setVideoFrame(uri, it, id, pts, source.colorStandard, source.colorRange, image.cropRect) }) { txt(R.string.media_error_interp_import, stamps.frameNumber(pts)) }
                held += pts
            }
            val made = if (smoothing.method == FrameInterpolation.Ai && frame.pair.a != frame.pair.b && frame.pair.t > 0f)
                aiFrame(this.source ?: uri, frame.pair.a, frame.pair.b, frame.pair.t, smoothing.level, stamps) else null
            try {
                if (!engine.interpolateVideo(uri, id, longArrayOf(frame.before, frame.pair.a, frame.pair.b, frame.after), frame.pair.t,
                        smoothing.method.ordinal, frame.soft, frame.blur, smoothing.flowParams(DebugFrameInterpolation.params()), made)) {
                    held.clear()
                    throw ExportError(txt(R.string.media_error_interp_render, txt(request.smoothing.label)))
                }
            } finally {
                made?.recycle()
            }
            held.retainAll(wanted)
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
                val image = source.frame(times[step], exact, abandoned) ?: run {
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

        private suspend fun aiFrame(file: String, a: Long, b: Long, t: Float, level: SmoothLevel, stamps: LongArray): Bitmap {
            val smooth = aiSmooth?.takeIf { it.level == level } ?: withContext(Dispatchers.IO) { AiSmooth.open(context, level) }.also { aiSmooth?.close(); aiSmooth = it }
            val reader = aiReader?.takeIf { it.first == file }?.second ?: FrameReader(context, file).also {
                aiReader?.second?.close()
                aiReader = file to it
                aiFrames.clear()
            }
            aiFrames.keys.retainAll(setOf(a, b))
            val missing = listOf(a, b).filter { it !in aiFrames }.distinct().sorted()
            if (missing.isNotEmpty()) reader.read(missing) { pts, yuv -> aiFrames[pts] = yuv.argb(yuv.width, yuv.height, reader.colorStandard, reader.fullRange) to (yuv.width to yuv.height) }
            val (first, size) = aiFrames[a] ?: throw ExportError(txt(R.string.media_error_interp_source, stamps.frameNumber(a)))
            val second = aiFrames[b]?.first ?: throw ExportError(txt(R.string.media_error_interp_source, stamps.frameNumber(b)))
            val out = smooth.between(first, second, size.first, size.second, t)
            return Bitmap.createBitmap(out, size.first, size.second, Bitmap.Config.ARGB_8888)
        }

        suspend fun close() {
            stopped = true
            queue.close()
            worker.cancel(); worker.join()
            engine.setPicture(uri, null, id)
            aiSmooth?.close()
            aiSmooth = null
            aiReader?.second?.close()
            aiReader = null
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

internal data class SmoothFrame(val pair: FramePair, val before: Long, val after: Long, val blur: Float, val soft: Boolean)

internal fun Layer.smoothFrame(timeMs: Double, scene: Scene, stamps: LongArray, fps: Int, rate: Double, smoothing: Smoothing, shown: () -> Long): SmoothFrame? {
    val fill = mediaFill ?: return null
    val around = pairAround(timeMs, scene, stamps) ?: return null
    val method = smoothing.method
    val interpolating = method != FrameInterpolation.Off && needs(around, timeMs, scene, fps, rate)
    val chosen = when {
        interpolating -> around.pair
        method != FrameInterpolation.Off && !(fps == scene.fps && rate == 1.0) -> FramePair(around.pair.a, around.pair.a, 0f)
        else -> null
    }
    if (chosen == null && smoothing.blur == MotionBlur.Off) return null
    var pair = chosen ?: shown().let { FramePair(it, it, 0f) }
    if (method == FrameInterpolation.Blend && smoothing.level == SmoothLevel.Low && pair.t > 0f) pair = pair.copy(t = ((pair.t - 0.25f) * 2).coerceIn(0f, 1f))
    val first = fill.inTime * 1000L - 500
    val last = fill.outTime * 1000L - 500
    fun step(pts: Long, by: Int): Long? {
        val at = stamps.binarySearch(pts).let { if (it >= 0) it else -it - 2 }
        return stamps.getOrNull(at + by)?.takeIf { it in first..last }
    }
    val soft = interpolating && method == FrameInterpolation.Blend && smoothing.level == SmoothLevel.High
    if ((soft || smoothing.blur != MotionBlur.Off) && pair.a == pair.b) step(pair.a, 1)?.let { pair = FramePair(pair.a, it, 0f) }
    val blur = if (smoothing.blur == MotionBlur.Off || pair.a == pair.b) 0f else {
        val mediaUs = VideoTiming(this).speed(localMs(timeMs, scene)) * rate * 1e6 / fps
        (smoothing.blur.shutter / 360 * mediaUs / (pair.b - pair.a) * smoothing.blurScale).toFloat()
    }
    return SmoothFrame(pair, if (soft) step(pair.a, -1) ?: pair.a else pair.a, if (soft) step(pair.b, 1) ?: pair.b else pair.b, blur, soft && pair.a != pair.b)
}
