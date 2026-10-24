package app.absentmotion.engine

import android.graphics.Rect
import android.graphics.Bitmap
import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.absentmotion.R
import app.absentmotion.audio.AudioEngine
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.txt
import app.absentmotion.export.Smoothing
import app.absentmotion.media.VideoLibrary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import org.json.JSONArray
import org.json.JSONObject
import app.absentmotion.media.MediaLibrary
import app.absentmotion.media.Mesh
import app.absentmotion.model.modelUris
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.prepareText
import app.absentmotion.model.fontNames
import app.absentmotion.model.font
import app.absentmotion.model.Scene
import app.absentmotion.model.imageUris
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object EngineDebug {
    @Volatile var failStart: String? = null
}

class EngineThread(private val background: Int, context: Context? = null) {
    private val thread = HandlerThread("am-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val fonts = context?.let { FontLibrary(it) }
    private var fontJob: Job? = null
    private var fontNames = emptyList<String>()
    private val media = context?.let { MediaLibrary(it, this) }
    private val videos = context?.let { VideoLibrary(it, this) }
    val audio = context?.let { AudioEngine(it) }
    private val captureLock = Mutex()
    private var capturing = false
    @Volatile var lastStillMs = 0.0
        private set
    @Volatile private var requestedView: PreviewView? = null
    @Volatile private var playing = false
    private val effectCatalog = MutableStateFlow(EffectCatalog(message = AppText.get(R.string.engine_loading_effects)))
    val effects = effectCatalog.asStateFlow()

    suspend fun effectCheck(directory: File) {
        effectJob?.join()
        val catalog = effectCatalog.value
        val entries = JSONArray()
        for (def in catalog.definitions) {
            val entry = JSONObject().put("id", def.id).put("status", def.status).put("category", def.category)
            if (def.available) entry.put("render", if (renderCheck(def)) "ok" else "empty")
            entries.put(entry)
        }
        val report = JSONObject().put("count", entries.length()).put("effects", entries)
        catalog.message?.let { report.put("error", it) }
        withContext(Dispatchers.IO) { File(directory, "effectcheck.json").writeText(report.toString(2)) }
    }

    private suspend fun renderCheck(def: EffectDefinition): Boolean = captureLock.withLock {
        withContext(dispatcher) {
            if (closed) return@withContext false
            applyPending()
            val shown = scene
            val xml = "<scene title=\"effectcheck\" width=\"256\" height=\"256\" fps=\"30\" totalTime=\"1000\" bgcolor=\"#00000000\">" +
                "<shape id=\"1\" label=\"t\" startTime=\"0\" endTime=\"999\" fillType=\"color\" s=\".rect\"><transform><location value=\"128,128,0\"/></transform>" +
                "<fillColor value=\"#ffc8503c\"/><property name=\"size\" type=\"vec2\" value=\"160,160\"/>" +
                def.instance().toXml().substringAfter("?>") + "</shape></scene>"
            try {
                setEngineScene(xml)
                val pixels = engine().still(100.0, 128, 128)
                pixels != null && (3 until pixels.size step 4).any { pixels[it].toInt() != 0 }
            } finally { shown?.let { setEngineScene(it) } }
        }
    }

    @Volatile private var closed = false
    private var mediaJob: Job? = null
    @Volatile private var mediaScene: Scene? = null
    private val pictureRevision = MutableStateFlow(0L)
    val mediaRevision = pictureRevision.asStateFlow()

    private var engine: NativeEngine? = null
    private var target = 0L
    private var surface: Surface? = null
    private var lost = false
    @Volatile var generation = 0
        private set
    @Volatile internal var simulateGpuLoss = false
    private var view: PreviewView? = null
    private var scene: String? = null

    private val pendingScene = PendingScene()
    private val pendingView = AtomicReference<Pair<PreviewView, Long>?>(null)
    private val renderPosted = AtomicBoolean(false)
    private val viewSerial = java.util.concurrent.atomic.AtomicLong()
    private var shownSerial = 0L
    private val renderedView = MutableStateFlow(RenderedView(0L, -1))
    val rendered = renderedView.asStateFlow()
    @Volatile var renders = 0L
        private set

    init {
        fonts?.let { library -> mediaScope.launch {
            library.revision.collect {
                pendingScene.fontsChanged(); pictureRevision.value++; requestRender()
            }
        } }
    }
    private val effectJob = context?.let { app -> mediaScope.launch {
        val catalog = EffectCatalog.read(app)
        effectCatalog.value = withContext(dispatcher) {
            if (closed) catalog else catalog.loadInto { runCatching { engine().loadEffect(it) }.getOrElse { e -> "shader error: ${e.message}" } }
        }
        withContext(dispatcher) { renderNow() }
    } }

    private fun engine(): NativeEngine {
        check(!closed) { "engine closed" }
        if (engine?.failed == true || simulateGpuLoss && !capturing) {
            exportCheck(!capturing) { txt(R.string.engine_error_gpu_capture) }
            simulateGpuLoss = false
            target = 0L
            engine?.close()
            engine = null
            lost = true
        }
        return engine ?: startEngine().also { next ->
            engine = next
            scene?.let { next.setScene(fonts?.prepareText(it, textTime) ?: it) }
            effects.value.loadInto(next::loadEffect)
            if (lost) {
                lost = false
                generation++
                surface?.takeIf { it.isValid }?.let { target = next.attach(it, forEncoder = false) }
                Handler(android.os.Looper.getMainLooper()).post(::refreshMedia)
            }
        }
    }

    @Volatile private var startFailure: String? = null

    private fun startEngine(): NativeEngine {
        startFailure?.let { throw IllegalStateException(it) }
        return try {
            EngineDebug.failStart?.let { throw IllegalStateException(it) }
            NativeEngine(NativeEngine.Backend.GRAPHITE)
        } catch (t: Throwable) {
            val reason = t.message ?: t.toString()
            Log.e("AMEngine", "the engine cannot start", t)
            if (generation == 0 && !lost) startFailure = reason
            throw t as? Exception ?: IllegalStateException(reason, t)
        }
    }

    val startProblem: Deferred<String?> by lazy {
        mediaScope.async { withContext(dispatcher) { if (!closed) runCatching { engine() }; startFailure } }
    }

    fun attach(surface: Surface, retry: Boolean = true): Boolean = handler.post {
        if (closed) return@post
        try {
            val previous = target
            target = 0L
            this.surface = null
            if (previous != 0L) engine().detach(previous)
            target = engine().attach(surface, forEncoder = false)
            this.surface = surface
            mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, playing) }
            renderNow()
        } catch (e: Exception) {
            Log.e("AMEngine", "preview attachment failed", e)
            if (retry) handler.postDelayed({ if (!closed && target == 0L && surface.isValid) attach(surface, retry = false) }, 250)
        }
    }

    fun detach() {
        val done = CountDownLatch(1)
        val release = Runnable {
            try {
                val previous = target
                target = 0L
                surface = null
                if (previous != 0L) engine?.detach(previous)
            } finally { done.countDown() }
        }
        if (android.os.Looper.myLooper() == handler.looper) release.run()
        else if (handler.post(release)) done.await()
        videos?.suspendPreview()
    }

    fun setScene(next: Scene, xml: String = next.toXml()) {
        pendingScene.set(xml)
        fonts?.let { library ->
            val names = next.fontNames()
            if (names != fontNames) {
                fontNames = names
                fontJob = mediaScope.launch { names.forEach { library.resolve(it) } }
            }
        }
        if (media != null) {
            val previous = mediaScene
            mediaScene = next
            audio?.setScene(next)
            videos?.update(next, requestedView?.timeMs ?: 0, playing)
            if (previous?.node?.imageUris() != next.node.imageUris() || previous.node.modelUris() != next.node.modelUris() || previous.media != next.media) {
                mediaJob?.cancel()
                mediaJob = mediaScope.launch { media.update(next) }
            }
        }
        requestRender()
    }

    fun refreshMedia() {
        val next = mediaScene ?: return
        mediaJob?.cancel()
        mediaJob = mediaScope.launch { media?.update(next); videos?.retry() }
    }

    suspend fun awaitMedia() {
        do { val loading = mediaJob; loading?.join() } while (loading !== mediaJob)
    }

    suspend fun awaitVideoFrames(scene: Scene, timeMs: Int) {
        captureLock.withLock {
            try {
                withContext(dispatcher) { check(!closed) { "engine closed" }; capturing = true }
                videos?.withExact(scene, timeMs) {}
            }
            finally {
                withContext(NonCancellable + dispatcher) { capturing = false; renderNow() }
                videos?.resumePreview()
            }
        }
    }

    fun holdVideo(): Job? = videos?.hold()

    fun releaseVideo(): Job? = videos?.release()

    fun setPlaying(value: Boolean) {
        playing = value
        mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, value) }
    }

    internal suspend fun setVideoPicture(uri: String, buffer: HardwareBuffer, layerId: Long,
                                         exact: Boolean, standard: Int, range: Int, crop: Rect, accept: () -> Boolean): Boolean = withContext(dispatcher) {
        if (closed || (!exact && capturing) || !accept()) return@withContext false
        engine().setPicture(uri, buffer, layerId, standard = standard, range = range, crop = crop).also {
            if (!exact) requestRender()
        }
    }

    internal suspend fun setVideoFrame(uri: String, buffer: HardwareBuffer, layerId: Long, pts: Long, standard: Int = 0, range: Int = 0, crop: Rect? = null): Boolean = withContext(dispatcher) {
        !closed && engine().setVideoFrame(uri, layerId, pts, buffer, standard, range, crop)
    }

    internal suspend fun interpolateVideo(uri: String, layerId: Long, frames: LongArray, t: Float, mode: Int, soft: Boolean, blur: Float,
                                          params: FloatArray, made: android.graphics.Bitmap? = null): Boolean = withContext(dispatcher) {
        !closed && engine().interpolateVideo(uri, layerId, frames, t, mode, soft, blur, params, made)
    }

    internal suspend fun moshStart(uri: String, layerId: Long, pts: LongArray, mosh: FloatArray, flow: FloatArray, exact: Boolean): Int = withContext(dispatcher) {
        if (closed || (!exact && capturing)) return@withContext -1
        engine().moshStart(uri, layerId, pts, mosh, flow).also { if (it == pts.size && !exact) renderNow() }
    }

    internal suspend fun moshFrame(uri: String, buffer: HardwareBuffer, layerId: Long, step: Int, last: Boolean, exact: Boolean,
                                   standard: Int, range: Int, crop: Rect, accept: () -> Boolean): Boolean = withContext(dispatcher) {
        if (closed || (!exact && capturing) || !accept()) return@withContext false
        engine().moshFrame(uri, layerId, step, buffer, standard, range, crop).also { if (it && last && !exact) renderNow() }
    }

    suspend fun maxPictureSize(): Int = withContext(dispatcher) { if (closed) 1 else engine().maxPictureSize }

    suspend fun setPicture(uri: String, bitmap: Bitmap?, layerId: Long = 0): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        engine().setPicture(uri, bitmap, layerId).also { pictureRevision.value++; renderNow() }
    }

    suspend fun setModel(uri: String, mesh: Mesh?, textures: List<Bitmap?>): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        val engine = engine()
        textures.forEachIndexed { k, bitmap -> engine.setPicture("$uri#$k", bitmap) }
        engine.setModel(uri, mesh).also { pictureRevision.value++; renderNow() }
    }

    suspend fun setPicture(uri: String, buffer: HardwareBuffer, layerId: Long = 0, opaque: Boolean = true): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        engine().setPicture(uri, buffer, layerId, opaque).also { pictureRevision.value++; renderNow() }
    }

    fun setView(view: PreviewView): Long {
        requestedView = view
        mediaScene?.let { videos?.update(it, view.timeMs, playing) }
        val serial = viewSerial.incrementAndGet()
        pendingView.set(view to serial)
        requestRender()
        return serial
    }

    private fun requestRender() {
        if (closed || !renderPosted.compareAndSet(false, true)) return
        if (!handler.post {
            renderPosted.set(false)
            renderNow()
        }) renderPosted.set(false)
    }

    private fun applyPending() {
        if (closed || capturing) return
        pendingView.getAndSet(null)?.let { (next, serial) -> view = next; shownSerial = serial }
        try {
            view?.let { engine().setCameraView(it.camera); engine().setAccurateBlur(it.accurateBlur) }
            pendingScene.take(scene)?.let { setEngineScene(it) }
            prepareTextTime(view?.timeMs ?: 0)
        } catch (e: IllegalArgumentException) {
            Log.e("AMEngine", "scene rejected", e)
        }
    }

    private var animatedText = false
    private var textTime = Int.MIN_VALUE

    private fun setEngineScene(xml: String, timeMs: Int = view?.timeMs ?: 0) {
        engine().setScene(fonts?.prepareText(xml, timeMs) ?: xml)
        rememberScene(xml, timeMs)
    }

    private fun rememberScene(xml: String?, timeMs: Int) {
        scene = xml
        animatedText = xml?.let(::animatesTextSpacing) == true
        textTime = timeMs
    }

    private fun restoreScene(xml: String?) {
        if (closed) return
        if (engine?.failed == true) rememberScene(xml, view?.timeMs ?: 0)
        else xml?.let { setEngineScene(it) }
    }

    private fun prepareTextTime(timeMs: Int) {
        if (animatedText && textTime != timeMs) scene?.let { setEngineScene(it, timeMs) }
    }

    private fun renderNow() {
        if (closed || capturing) return
        try {
            applyPending()
            val v = view ?: return
            if (target != 0L) {
                val native = engine()
                val started = System.nanoTime()
                if (target != 0L && native.renderScene(target, v.timeMs.toDouble(), v.scale, v.dx, v.dy, background, v.selectedId, v.handles, v) != 0) {
                    Log.e("AMEngine", "preview rendering failed")
                }
                if (RenderStats.recording) RenderStats.add((System.nanoTime() - started) / 1e6, v.timeMs)
                renders++
                renderedView.value = RenderedView(shownSerial, v.timeMs)
            }
        } catch (e: Exception) {
            if (!closed) Log.e("AMEngine", "preview rendering failed", e)
        }
    }

    suspend fun layerQuad(layerId: Long, timeMs: Int): FloatArray? = captureLock.withLock {
        withContext(dispatcher) {
            applyPending()
            prepareTextTime(timeMs)
            engine().layerQuad(layerId, timeMs.toDouble())
        }
    }

    suspend fun layerMatrix(layerId: Long, timeMs: Int): FloatArray? = captureLock.withLock {
        withContext(dispatcher) {
            applyPending()
            prepareTextTime(timeMs)
            engine().layerMatrix(layerId, timeMs.toDouble())
        }
    }

    suspend fun layerBounds(layerId: Long, timeMs: Int): FloatArray? = captureLock.withLock {
        withContext(dispatcher) {
            applyPending()
            prepareTextTime(timeMs)
            engine().layerBounds(layerId, timeMs.toDouble())
        }
    }

    suspend fun layerOutline(layerId: Long, timeMs: Int): String? = captureLock.withLock {
        withContext(dispatcher) {
            applyPending()
            prepareTextTime(timeMs)
            engine().layerOutline(layerId, timeMs.toDouble())
        }
    }

    suspend fun hitTest(x: Float, y: Float, timeMs: Int): Long = captureLock.withLock {
        withContext(dispatcher) {
            applyPending()
            prepareTextTime(timeMs)
            engine().hitTest(x, y, timeMs.toDouble())
        }
    }

    internal suspend fun previewForTest(width: Int, height: Int): ByteArray? = withContext(dispatcher) {
        applyPending()
        engine().still(0.0, width, height)
    }

    suspend fun stillPng(xml: String, timeMs: Int, width: Int, height: Int, file: File): Boolean = captureLock.withLock {
        awaitMedia()
        fontJob?.join()
        effectJob?.join()
        val document = withContext(Dispatchers.Default) { Scene.parse(xml) }
        fonts?.let { library -> document.fontNames().forEach { library.resolve(it) } }
        var shown: String? = null
        try {
            media?.pinned = document
            media?.prepare(document)
            withContext(dispatcher) {
                check(!closed) { "engine closed" }
                applyPending()
                shown = scene
                capturing = true
                setEngineScene(xml, timeMs)
            }
            suspend fun draw(): Boolean = withContext(dispatcher) {
                val start = System.nanoTime()
                val pixels = engine().still(timeMs.toDouble(), width, height)
                lastStillMs = (System.nanoTime() - start) / 1e6
                pixels != null && writePng(pixels, width, height, file)
            }
            videos?.withExact(document, timeMs) { draw() } ?: draw()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AMVideo", "Cannot capture exact video still", e)
            false
        } finally {
            media?.pinned = null
            try {
                withContext(NonCancellable + dispatcher) {
                    try { restoreScene(shown) }
                    finally { capturing = false }
                }
            } finally {
                videos?.resumePreview()
                requestRender()
            }
        }
    }

    inner class ExportSession internal constructor(private val document: Scene, private val smoothing: Smoothing, private val fps: Int) {
        suspend fun still(timeMs: Int, width: Int, height: Int): ByteArray? =
            atFrame(document, timeMs, smoothing, fps) { engine().still(timeMs.toDouble(), width, height) }

        suspend fun attach(surface: Surface): Long = withContext(dispatcher) { engine().attach(surface, forEncoder = true) }

        suspend fun detach(target: Long) = withContext(NonCancellable + dispatcher) { engine?.detach(target) }

        suspend fun render(target: Long, timeMs: Int, presentTimeNs: Long): Boolean =
            atFrame(document, timeMs, smoothing, fps) { engine().renderFrame(target, timeMs.toDouble(), 0xFF000000.toInt(), presentTimeNs) == 0 }

        suspend fun stampsPresentTime(): Boolean = withContext(dispatcher) { engine().displayTiming }

        suspend fun renderInto(buffer: HardwareBuffer, timeMs: Int): Boolean =
            atFrame(document, timeMs, smoothing, fps) { engine().renderBuffer(buffer, timeMs.toDouble(), 0xFF000000.toInt()) == 0 }

        suspend fun releaseBuffers() = withContext(NonCancellable + dispatcher) { engine?.releaseBuffers() }
    }

    private suspend fun <T> atFrame(document: Scene, timeMs: Int, smoothing: Smoothing, fps: Int, draw: () -> T): T {
        suspend fun run() = withContext(dispatcher) { prepareTextTime(timeMs); draw() }
        return if (videos != null) videos.withExact(document, timeMs, smoothing, fps) { run() } else run()
    }

    suspend fun <T> exportSession(xml: String, smoothing: Smoothing = Smoothing(), fps: Int = 0,
                                  block: suspend ExportSession.() -> T): T = captureLock.withLock {
        awaitMedia()
        fontJob?.join()
        effectJob?.join()
        val document = Scene.parse(xml)
        fonts?.let { library -> document.fontNames().forEach { library.resolve(it) } }
        var shown: String? = null
        val held = videos?.hold()
        try {
            held?.join()
            media?.pinned = document
            media?.prepare(document)
            videos?.suspendPreview()
            withContext(dispatcher) {
                check(!closed) { "engine closed" }
                applyPending()
                shown = scene
                capturing = true
                setEngineScene(xml, 0)
            }
            ExportSession(document, smoothing, fps).block()
        } finally {
            if (held != null) videos.release()
            media?.pinned = null
            var noPreview = true
            try {
                withContext(NonCancellable + dispatcher) {
                    try { restoreScene(shown) }
                    finally { capturing = false; noPreview = target == 0L }
                }
            } finally {
                if (noPreview) videos?.suspendPreview()
                else mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, playing) }
                withContext(NonCancellable) { videos?.settle()?.join() }
                requestRender()
            }
        }
    }

    suspend fun shapeIcon(xml: String, size: Int, color: Int): Bitmap? {
        awaitMedia()
        fontJob?.join()
        media?.prepare(Scene.parse(xml))
        return withContext(dispatcher) {
            applyPending()
            engine().shapeIcon(fonts?.prepareText(xml) ?: xml, size, color)?.let { pixels ->
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
            }
        }
    }

    private fun writePng(pixels: ByteArray, width: Int, height: Int, file: File): Boolean {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.isPremultiplied = false
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    suspend fun timing(): String = withContext(dispatcher) { if (!closed && target != 0L && engine?.failed != true) engine().timing(target) else "{}" }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        audio?.close()
        mediaJob?.cancel()
        mediaScope.launch {
            try { videos?.close() }
            finally {
                handler.post {
                    try {
                        val previous = target
                        target = 0L
                        if (previous != 0L) engine?.detach(previous)
                    } finally {
                        try { engine?.close() }
                        finally { engine = null; thread.quitSafely() }
                    }
                }
                mediaScope.cancel()
            }
        }
    }
}

private val spacingEffects = Regex("<effect\\b[^>]*\"${Regex.escape(app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING)}\"[^>]*?(/>|>.*?</effect>)", RegexOption.DOT_MATCHES_ALL)

internal fun animatesTextSpacing(xml: String): Boolean =
    xml.contains(app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING) && spacingEffects.findAll(xml).any { it.value.contains("<kf") }

internal class PendingScene {
    private val next = AtomicReference<String?>(null)
    private val fonts = AtomicBoolean(false)

    fun set(xml: String) = next.set(xml)

    fun fontsChanged() = fonts.set(true)

    fun take(shown: String?): String? {
        val again = fonts.getAndSet(false)
        return next.getAndSet(null) ?: shown.takeIf { again }
    }
}
