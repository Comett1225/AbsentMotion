package app.absentmotion.engine

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.absentmotion.audio.AudioEngine
import app.absentmotion.media.VideoLibrary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import org.json.JSONArray
import org.json.JSONObject
import app.absentmotion.media.MediaLibrary
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.prepareText
import app.absentmotion.model.font
import app.absentmotion.model.Scene
import app.absentmotion.model.imageUris
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EngineThread(private val background: Int, context: Context? = null) {
    private val thread = HandlerThread("am-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val fonts = context?.let { FontLibrary(it) }
    private var fontJob: Job? = null
    private var fontNames = emptyList<String>()
    @Volatile private var fontDocument: String? = null
    private val media = context?.let { MediaLibrary(it, this) }
    private val videos = context?.let { VideoLibrary(it, this) }
    val audio = context?.let { AudioEngine(it) }
    private val captureLock = Mutex()
    private var capturing = false
    @Volatile private var requestedView: PreviewView? = null
    @Volatile private var playing = false
    private val effectCatalog = MutableStateFlow(EffectCatalog(message = "Loading effects…"))
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
    private var view: PreviewView? = null
    private var scene: String? = null

    private val pendingScene = AtomicReference<String?>(null)
    private val pendingView = AtomicReference<PreviewView?>(null)
    private val renderPosted = AtomicBoolean(false)

    init {
        fonts?.let { library -> mediaScope.launch {
            library.revision.collect {
                fontDocument?.let { xml -> pendingScene.set(xml); pictureRevision.value++; requestRender() }
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
        if (engine?.failed == true) {
            check(!capturing) { "GPU failed during capture" }
            target = 0L
            engine?.close()
            engine = null
        }
        return engine ?: NativeEngine(NativeEngine.Backend.GRAPHITE).also { next ->
            engine = next
            scene?.let { next.setScene(fonts?.prepareText(it, textTime) ?: it) }
            effects.value.loadInto(next::loadEffect)
        }
    }

    fun attach(surface: Surface) = handler.post {
        if (closed) return@post
        try {
            val previous = target
            target = 0L
            if (previous != 0L) engine().detach(previous)
            target = engine().attach(surface, forEncoder = false)
            mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, playing) }
            renderNow()
        } catch (e: Exception) {
            Log.e("AMEngine", "preview attachment failed", e)
        }
    }

    fun detach() {
        val done = CountDownLatch(1)
        val release = Runnable {
            try {
                val previous = target
                target = 0L
                if (previous != 0L) engine?.detach(previous)
            } finally { done.countDown() }
        }
        if (android.os.Looper.myLooper() == handler.looper) release.run()
        else if (handler.post(release)) done.await()
        videos?.suspendPreview()
    }

    fun setScene(xml: String) {
        pendingScene.set(xml)
        fontDocument = xml
        fonts?.let { library ->
            val names = runCatching { Scene.parse(xml).layers.filter { it.kind == "text" }.map { it.font }.distinct() }.getOrDefault(emptyList())
            if (names != fontNames) {
                fontNames = names
                fontJob = mediaScope.launch { names.forEach { library.resolve(it) } }
            }
        }
        if (media != null) runCatching { Scene.parse(xml) }.getOrNull()?.let { next ->
            val previous = mediaScene
            mediaScene = next
            audio?.setScene(next)
            videos?.update(next, requestedView?.timeMs ?: 0, playing)
            if (previous?.node?.imageUris() != next.node.imageUris() || previous.media != next.media) {
                mediaJob?.cancel()
                mediaJob = mediaScope.launch { media.update(next) }
            }
        }
        requestRender()
    }

    fun refreshMedia() {
        val next = mediaScene ?: return
        mediaJob?.cancel()
        mediaJob = mediaScope.launch { media?.update(next, retry = true); videos?.retry() }
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

    fun setPlaying(value: Boolean) {
        playing = value
        mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, value) }
    }

    internal suspend fun setVideoPicture(uri: String, buffer: HardwareBuffer, layerId: Long,
                                         exact: Boolean, accept: () -> Boolean): Boolean = withContext(dispatcher) {
        if (closed || (!exact && capturing) || !accept()) return@withContext false
        engine().setPicture(uri, buffer, layerId).also {
            if (!exact) renderNow()
        }
    }

    suspend fun maxPictureSize(): Int = withContext(dispatcher) { if (closed) 1 else engine().maxPictureSize }

    suspend fun setPicture(uri: String, bitmap: Bitmap?, layerId: Long = 0): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        engine().setPicture(uri, bitmap, layerId).also { pictureRevision.value++; renderNow() }
    }

    suspend fun setPicture(uri: String, buffer: HardwareBuffer, layerId: Long = 0, opaque: Boolean = true): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        engine().setPicture(uri, buffer, layerId, opaque).also { pictureRevision.value++; renderNow() }
    }

    fun setView(view: PreviewView) {
        requestedView = view
        mediaScene?.let { videos?.update(it, view.timeMs, playing) }
        pendingView.set(view)
        requestRender()
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
        pendingView.getAndSet(null)?.let { view = it }
        try {
            view?.let { engine().setCameraView(it.camera) }
            pendingScene.getAndSet(null)?.let { setEngineScene(it) }
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
        animatedText = xml?.let { it.contains(app.absentmotion.compat.ForeignEffectIds.TEXT_SPACING) && it.contains("<kf") } == true
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
                if (target != 0L && native.renderScene(target, v.timeMs.toDouble(), v.scale, v.dx, v.dy, background, v.selectedId, v.handles, v) != 0) {
                    Log.e("AMEngine", "preview rendering failed; surface must be attached again")
                }
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

    suspend fun stillPng(xml: String, timeMs: Int, width: Int, height: Int, file: File): Boolean = captureLock.withLock {
        awaitMedia()
        fontJob?.join()
        effectJob?.join()
        val document = Scene.parse(xml)
        media?.prepare(document)
        var shown: String? = null
        try {
            withContext(dispatcher) {
                check(!closed) { "engine closed" }
                applyPending()
                shown = scene
                capturing = true
                setEngineScene(xml, timeMs)
            }
            suspend fun draw(): Boolean = withContext(dispatcher) {
                val pixels = engine().still(timeMs.toDouble(), width, height)
                pixels != null && writePng(pixels, width, height, file)
            }
            videos?.withExact(document, timeMs) { draw() } ?: draw()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AMVideo", "Cannot capture exact video still", e)
            false
        } finally {
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

    inner class ExportSession internal constructor(private val document: Scene) {
        suspend fun still(timeMs: Int, width: Int, height: Int): ByteArray? =
            atFrame(document, timeMs) { engine().still(timeMs.toDouble(), width, height) }

        suspend fun attach(surface: Surface): Long = withContext(dispatcher) { engine().attach(surface, forEncoder = true) }

        suspend fun detach(target: Long) = withContext(NonCancellable + dispatcher) { engine?.detach(target) }

        suspend fun render(target: Long, timeMs: Int, presentTimeNs: Long): Boolean =
            atFrame(document, timeMs) { engine().renderFrame(target, timeMs.toDouble(), 0xFF000000.toInt(), presentTimeNs) == 0 }
    }

    private suspend fun <T> atFrame(document: Scene, timeMs: Int, draw: () -> T): T {
        suspend fun run() = withContext(dispatcher) { prepareTextTime(timeMs); draw() }
        return if (videos != null) videos.withExact(document, timeMs) { run() } else run()
    }

    suspend fun <T> exportSession(xml: String, block: suspend ExportSession.() -> T): T = captureLock.withLock {
        awaitMedia()
        fontJob?.join()
        effectJob?.join()
        val document = Scene.parse(xml)
        media?.prepare(document)
        var shown: String? = null
        try {
            videos?.suspendPreview()
            withContext(dispatcher) {
                check(!closed) { "engine closed" }
                applyPending()
                shown = scene
                capturing = true
                setEngineScene(xml, 0)
            }
            ExportSession(document).block()
        } finally {
            var noPreview = true
            try {
                withContext(NonCancellable + dispatcher) {
                    try { restoreScene(shown) }
                    finally { capturing = false; noPreview = target == 0L }
                }
            } finally {
                if (noPreview) videos?.suspendPreview()
                else mediaScene?.let { videos?.update(it, requestedView?.timeMs ?: 0, playing) }
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
