package app.absentmotion.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import app.absentmotion.R
import app.absentmotion.audio.AudioEngine
import app.absentmotion.desk.DeskMedia
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.prepareText
import app.absentmotion.i18n.AppText
import app.absentmotion.media.Mesh
import app.absentmotion.model.Scene
import app.absentmotion.export.Smoothing
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.fontNames
import app.absentmotion.model.imageUris
import app.absentmotion.model.modelUris
import app.absentmotion.model.layerTracks
import app.absentmotion.media.loadModel
import app.absentmotion.model.videoTimeUs
import app.absentmotion.pc.engine.FfmpegVideoSource
import app.absentmotion.pc.engine.NativeVideoSource
import app.absentmotion.pc.engine.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object EngineDebug {
    @Volatile var failStart: String? = null
    @Volatile var forceGl = false
    @Volatile var failCheck: String? = null
}

class EngineThread(private val background: Int, private val context: Context? = null, private val preferOpenGl: () -> Boolean = { false }) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "am-engine").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val fonts = context?.let { FontLibrary(it) }
    val audio = context?.let { AudioEngine(it) }
    private val captureLock = Mutex()
    @Volatile var lastStillMs = 0.0
    @Volatile private var closed = false
    @Volatile private var playing = false
    private val effectCatalog = MutableStateFlow(EffectCatalog(message = AppText.get(R.string.engine_loading_effects)))
    val effects = effectCatalog.asStateFlow()
    private val pictureRevision = MutableStateFlow(0L)
    val mediaRevision = pictureRevision.asStateFlow()
    private val installedSources = MutableStateFlow<Map<String, String>>(emptyMap())
    val sources = installedSources.asStateFlow()
    val instance = instances.incrementAndGet()
    val sourcesChecked: Boolean get() = true
    @Volatile var generation = 0
    @Volatile internal var simulateGpuLoss = false
    private val renderedView = MutableStateFlow(RenderedView(0L, -1))
    val rendered = renderedView.asStateFlow()
    @Volatile var renders = 0L
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private var engine: NativeEngine? = null
    private var shownXml: String? = null
    private var pendingXml: Lazy<String>? = null
    private var pendingScene: Scene? = null
    private var shownScene: Scene? = null
    private var fontsDirty = false
    @Volatile private var mediaScene: Scene? = null
    @Volatile private var mediaJob: Job? = null
    private var mediaKey: Any? = null
    private val videos = HashMap<String, AutoCloseable>()
    private val mosh = context?.let { DeskMosh(it) }
    private val loadedModels = HashMap<String, Pair<String, Int>>()
    private var loadedPictures: Set<String> = emptySet()
    private var view: PreviewView? = null
    private var viewSerial = 0L
    private val serials = AtomicLong()
    private var outSize = IntSize.Zero
    private var outDivisor = 1
    private val targets = arrayOfNulls<org.jetbrains.skia.Bitmap>(3)
    private var nextTarget = 0
    @Volatile private var shownTarget: org.jetbrains.skia.Bitmap? = null
    private val renderPosted = AtomicBoolean(false)
    @Volatile private var capturing = false

    init {
        fonts?.let { library -> mediaScope.launch { library.revision.collect { fontsDirty = true; pictureRevision.update { it + 1 }; requestRender() } } }
    }

    private val effectJob = context?.let { app -> mediaScope.launch {
        val catalog = EffectCatalog.read(app)
        effectCatalog.value = withContext(dispatcher) {
            if (closed) catalog else catalog.loadInto { runCatching { engine().loadEffect(it) }.getOrElse { e -> "shader error: ${e.message}" } }
        }
        withContext(dispatcher) { renderNow() }
    } }

    fun relabelEffects(context: Context) {}

    suspend fun effectCheck(directory: File) {}

    internal fun publishSource(uri: String, identity: String?) = installedSources.update { if (identity == null) it - uri else it + (uri to identity) }

    fun textIdentity(revision: Long): String = "$instance:$revision:${fonts?.androidEmoji() ?: false}"

    private fun engine(): NativeEngine {
        check(!closed) { "engine closed" }
        return engine ?: NativeEngine().also { next ->
            engine = next
            effects.value.loadInto(next::loadEffect)
        }
    }

    val startProblem: Deferred<String?> by lazy { mediaScope.async { withContext(dispatcher) { runCatching { engine() }.exceptionOrNull()?.message } } }

    fun attach(surface: Surface, retry: Boolean = true): Boolean = false

    fun attachDesktop(divisor: Int, size: IntSize) {
        outDivisor = divisor.coerceAtLeast(1)
        outSize = size
        requestRender()
    }

    fun detach() {}

    fun setScene(next: Scene, xml: String) = setScene(next, lazyOf(xml))

    fun setScene(next: Scene, xml: Lazy<String> = lazy { next.toXml() }) {
        synchronized(this) { pendingXml = xml; pendingScene = next }
        fonts?.let { library ->
            val names = next.fontNames()
            mediaScope.launch { names.forEach { library.resolve(it) } }
        }
        val previous = mediaScene
        mediaScene = next
        audio?.setScene(next)
        val key = listOf(next.node.imageUris(), next.node.modelUris(), next.media, videoUris(next), app.absentmotion.media.ModelDetail.budget.value)
        if (previous == null || key != mediaKey) {
            mediaKey = key
            mediaJob?.cancel()
            mediaJob = mediaScope.launch { loadMedia(next) }.also { it.invokeOnCompletion { requestRender() } }
        }
        requestRender()
    }

    private fun videoUris(scene: Scene): Set<String> = scene.layerTracks().mapNotNull { it.layer.node["fillVideo"] }.toSet() +
        scene.media.filter { (it.node["type"] ?: "").startsWith("video/") }.map { it.uri }

    private fun file(uri: String): File? = runCatching { android.net.Uri.parse(uri).toFile() ?: File(uri) }.getOrNull()?.takeIf { it.isFile }

    private suspend fun loadMedia(scene: Scene) {
        val pictures = scene.node.imageUris()
        val clips = videoUris(scene)
        val models = scene.node.modelUris()
        withContext(dispatcher) {
            val native = engine()
            pictures.forEach { uri -> file(uri)?.let { native.setPictureFile(uri, it.absolutePath) } }
            (loadedPictures - pictures).forEach { native.setPicture(it, null) }
            loadedPictures = pictures
            (loadedModels.keys - models).forEach { uri ->
                val count = loadedModels.remove(uri)?.second ?: 0
                repeat(count) { native.setPicture("$uri#$it", null) }
                native.setModel(uri, null)
            }
            (videos.keys - clips).forEach { uri -> videos.remove(uri)?.close(); mosh?.drop(uri); native.dropVideo(uri) }
        }
        for (uri in models) {
            val source = file(uri) ?: continue
            val version = "${source.length()}:${source.lastModified()}"
            if (loadedModels[uri]?.first == version) continue
            val resolver = context?.contentResolver ?: continue
            val (mesh, textures) = loadModel(resolver, uri, 8192) ?: continue
            try {
                if (setModel(uri, mesh, textures)) loadedModels[uri] = version to textures.size
            } finally { textures.forEach { it?.recycle() } }
        }
        for (uri in clips) {
            if (videos.containsKey(uri)) continue
            val source = file(uri)?.let { f -> runCatching { NativeVideoSource(f) }.getOrNull() ?: runCatching { FfmpegVideoSource(f, DeskMedia.ffmpeg, DeskMedia.ffprobe) }.getOrNull() } ?: continue
            var handed = false
            try { withContext(dispatcher) { handed = true; if (closed) source.close() else videos[uri] = source } } finally { if (!handed) source.close() }
        }
        pictureRevision.update { it + 1 }
    }

    fun refreshMedia() { mediaScene?.let { scene -> mediaKey = null; setScene(scene) } }

    suspend fun awaitMedia() {
        do { val loading = mediaJob; loading?.join() } while (loading !== mediaJob)
    }

    suspend fun awaitVideoFrames(scene: Scene, timeMs: Int) {}

    fun holdVideo(): Job? = null

    fun trimMemory() {}

    fun releaseVideo(): Job? = null

    fun setPlaying(value: Boolean) { playing = value }

    suspend fun maxPictureSize(): Int = withContext(dispatcher) { if (closed) 1 else engine().maxPictureSize }

    suspend fun setPicture(uri: String, bitmap: Bitmap?, layerId: Long = 0): Boolean = withContext(dispatcher) {
        !closed && engine().setPicture(uri, bitmap, layerId).also { requestRender() }
    }

    suspend fun setModel(uri: String, mesh: Mesh?, textures: List<Bitmap?>): Boolean = withContext(dispatcher) {
        if (closed) return@withContext false
        val native = engine()
        textures.forEachIndexed { index, bitmap -> native.setPicture("$uri#$index", bitmap) }
        native.setModel(uri, mesh).also { if (mesh != null) pictureRevision.update { it + 1 }; requestRender() }
    }

    suspend fun setPicture(uri: String, buffer: HardwareBuffer, layerId: Long = 0, opaque: Boolean = true): Boolean = false

    fun setView(view: PreviewView): Long {
        val serial = serials.incrementAndGet()
        synchronized(this) { this.view = view; viewSerial = serial }
        requestRender()
        return serial
    }

    fun restart() { requestRender() }

    internal fun videoFrameReady() = requestRender()

    private fun requestRender() {
        if (closed || !renderPosted.compareAndSet(false, true)) return
        executor.execute { renderPosted.set(false); renderNow() }
    }

    private var timedText = false
    private var preparedAt = Int.MIN_VALUE

    private fun applyPending(timeMs: Int) {
        if (closed || capturing) return
        val (xml, again, parsed) = synchronized(this) { Triple(pendingXml.also { pendingXml = null }, fontsDirty.also { fontsDirty = false }, pendingScene) }
        val next = xml?.value ?: shownXml.takeIf { again || timedText && preparedAt != timeMs } ?: return
        val scene = if (xml != null) parsed else shownScene
        try {
            engine().setScene(fonts?.prepareText(next, timeMs, scene) ?: next)
            if (next !== shownXml) timedText = animatesTextSpacing(next)
            preparedAt = timeMs
            shownXml = next
            shownScene = scene
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("AMEngine", "scene rejected", e)
        }
    }

    private fun syncVideo(scene: Scene?, timeMs: Int, exact: Boolean = true): Boolean {
        scene ?: return true
        var current = true
        engine().clearLayerFrames()
        val late = ArrayList<Triple<String, Long, Pair<NativeVideoSource, Long>>>()
        for (track in scene.layerTracks()) {
            val layer = track.layer
            val uri = layer.node["fillVideo"] ?: continue
            val mapped = track.timeAt(timeMs.toDouble(), true) ?: continue
            val localTime = mapped.ms.toInt()
            val at = layer.videoTimeUs(localTime, track.scene, VideoTiming(layer)).coerceAtLeast(0)
            when (val source = videos[uri]) {
                is NativeVideoSource -> if (engine().showVideoLayer(uri, layer.id, source, at, exact, if (exact) 15000 else 0) == 0) { if (exact) current = false else late += Triple(uri, layer.id, source to at) }
                is VideoSource -> source.frame(at / 1000)?.let { engine().setLayerFrame(uri, layer.id, it.width, it.height, it.rgba) }
            }
            mosh?.apply(track.scene, layer, localTime, engine())
        }
        val until = System.nanoTime() + LIVE_VIDEO_WAIT_MS * 1_000_000L
        for ((uri, id, pick) in late) {
            val left = ((until - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(0)
            if (engine().showVideoLayer(uri, id, pick.first, pick.second, false, left) == 0) current = false
        }
        return current
    }

    private fun renderNow() {
        if (closed || capturing) return
        val (v, serial) = synchronized(this) { view to viewSerial }
        try {
            applyPending(v?.timeMs ?: 0)
            if (v == null || outSize.width <= 0 || outSize.height <= 0) return
            val w = (outSize.width / outDivisor).coerceAtLeast(1)
            val h = (outSize.height / outDivisor).coerceAtLeast(1)
            if (!syncVideo(mediaScene, v.timeMs, exact = false)) mediaScope.launch { delay(8); requestRender() }
            val native = engine()
            native.setCameraView(v.camera)
            native.setAccurateBlur(v.accurateBlur)
            val target = targets[nextTarget]?.takeIf { it.width == w && it.height == h } ?: org.jetbrains.skia.Bitmap().also {
                it.allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL, org.jetbrains.skia.ColorSpace.sRGB))
                it.setImmutable()
                targets[nextTarget]?.let { old -> javax.swing.SwingUtilities.invokeLater { old.close() } }
                targets[nextTarget] = it
            }
            val started = System.nanoTime()
            if (native.renderPreview(target, v.timeMs.toDouble(), v.scale, v.dx, v.dy, background, v.selectedId, v.handles, v)) {
                lastRenderMs = (System.nanoTime() - started) / 1e6
                nextTarget = (nextTarget + 1) % targets.size
                shownTarget = target
                val shown = target.asComposeImageBitmap()
                javax.swing.SwingUtilities.invokeLater { frame = shown }
                renders++
                renderedView.value = RenderedView(serial, v.timeMs)
            }
        } catch (e: Exception) {
            if (!closed) android.util.Log.e("AMEngine", "preview rendering failed", e)
        }
    }

    suspend fun capturePreviewFrame(document: Scene, timeMs: Int, width: Int, height: Int, accurateBlur: Boolean, accept: () -> Boolean): ByteArray? {
        if (closed || playing || mediaJob?.isActive == true || effectJob?.isActive == true || !accept() || !captureLock.tryLock()) return null
        try {
            return withContext(dispatcher) {
                if (closed || mediaScene != document || !accept()) return@withContext null
                applyPending(timeMs)
                syncVideo(document, timeMs)
                val started = System.nanoTime()
                engine().previewStill(timeMs.toDouble(), width, height, accurateBlur).also { lastStillMs = (System.nanoTime() - started) / 1e6 }
            }
        } finally { captureLock.unlock() }
    }

    private suspend fun <T> query(timeMs: Int, block: (NativeEngine) -> T): T = captureLock.withLock {
        withContext(dispatcher) { applyPending(timeMs); block(engine()) }
    }

    suspend fun layerQuad(layerId: Long, timeMs: Int): FloatArray? = query(timeMs) { it.layerQuad(layerId, timeMs.toDouble()) }
    suspend fun propertyNumbers(layerId: Long, effect: Int, name: String, timeMs: Int): FloatArray? = query(timeMs) { it.propertyNumbers(layerId, effect, name, timeMs.toDouble()) }
    suspend fun layerMatrix(layerId: Long, timeMs: Int): FloatArray? = query(timeMs) { it.layerMatrix(layerId, timeMs.toDouble()) }
    suspend fun layerBounds(layerId: Long, timeMs: Int): FloatArray? = query(timeMs) { it.layerBounds(layerId, timeMs.toDouble()) }
    suspend fun layerOutline(layerId: Long, timeMs: Int): String? = query(timeMs) { it.layerOutline(layerId, timeMs.toDouble()) }
    suspend fun hitTest(x: Float, y: Float, timeMs: Int): Long = query(timeMs) { it.hitTest(x, y, timeMs.toDouble()) }

    internal suspend fun previewForTest(width: Int, height: Int): ByteArray? = withContext(dispatcher) { applyPending(0); engine().still(0.0, width, height) }

    private suspend fun <T> withDocument(xml: String, timeMs: Int, block: suspend (Scene) -> T): T = captureLock.withLock {
        awaitMedia()
        effectJob?.join()
        val document = withContext(Dispatchers.Default) { Scene.parse(xml) }
        fonts?.let { library -> document.fontNames().forEach { library.resolve(it) } }
        loadMedia(document)
        var shown: String? = null
        try {
            withContext(dispatcher) {
                check(!closed) { "engine closed" }
                applyPending(timeMs)
                shown = shownXml
                capturing = true
                engine().setScene(fonts?.prepareText(xml, timeMs) ?: xml)
            }
            block(document)
        } finally {
            withContext(NonCancellable + dispatcher) {
                try { shown?.let { val at = view?.timeMs ?: 0; engine().setScene(fonts?.prepareText(it, at) ?: it); preparedAt = at } }
                finally { capturing = false }
            }
            mediaScene?.let { loadMedia(it) }
            requestRender()
        }
    }

    suspend fun stillPng(xml: String, timeMs: Int, width: Int, height: Int, file: File): Boolean = runCatching {
        withDocument(xml, timeMs) { document ->
            val pixels = withContext(dispatcher) { syncVideo(document, timeMs); engine().still(timeMs.toDouble(), width, height) }
            pixels != null && writePng(pixels, width, height, file)
        }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; false }

    inner class ExportSession internal constructor(private val document: Scene, private val interpolation: DeskInterpolation? = null) {
        suspend fun still(timeMs: Int, width: Int, height: Int): ByteArray? = withContext(dispatcher) {
            applyFonts(timeMs); syncVideo(document, timeMs); interpolation?.apply(document, timeMs, engine()); engine().still(timeMs.toDouble(), width, height)
        }
        suspend fun stillInto(timeMs: Int, width: Int, height: Int, out: ByteArray): Boolean = withContext(dispatcher) {
            applyFonts(timeMs); syncVideo(document, timeMs); interpolation?.apply(document, timeMs, engine()); engine().stillInto(timeMs.toDouble(), width, height, out)
        }
        suspend fun previewStill(timeMs: Int, width: Int, height: Int, accurateBlur: Boolean): ByteArray? = withContext(dispatcher) {
            applyFonts(timeMs); syncVideo(document, timeMs); engine().previewStill(timeMs.toDouble(), width, height, accurateBlur)
        }
        suspend fun attach(surface: Surface): Long = throw UnsupportedOperationException("Surface export is not used on desktop")
        suspend fun detach(target: Long) {}
        suspend fun render(target: Long, timeMs: Int, presentTimeNs: Long): Boolean = false
        suspend fun stampsPresentTime(): Boolean = false
        suspend fun openGl(): Boolean = false
        suspend fun renderInto(buffer: HardwareBuffer, timeMs: Int): Boolean = still(timeMs, buffer.width, buffer.height) != null
        suspend fun releaseBuffers() {}
        private var textTime = 0
        private val timedXml: String? by lazy { document.toXml().takeIf(::animatesTextSpacing) }
        private fun applyFonts(timeMs: Int) {
            if (timeMs == textTime) return
            textTime = timeMs
            val xml = timedXml ?: return
            engine().setScene(fonts?.prepareText(xml, timeMs) ?: xml)
        }
    }

    suspend fun <T> exportSession(xml: String, smoothing: Smoothing = Smoothing(), fps: Int = 0, block: suspend ExportSession.() -> T): T =
        withDocument(xml, 0) { document ->
            val interpolation = if (smoothing.active) DeskInterpolation.open(checkNotNull(context), smoothing, fps) else null
            try { ExportSession(document, interpolation).block() } finally { interpolation?.close() }
        }

    suspend fun shapeIcon(xml: String, size: Int, color: Int): Bitmap? {
        awaitMedia()
        return withContext(dispatcher) {
            applyPending(0)
            engine().shapeIcon(fonts?.prepareText(xml) ?: xml, size, color)?.let { px ->
                Bitmap.createBitmap(size, size).apply { copyPixelsFromBuffer(ByteBuffer.wrap(px)) }
            }
        }
    }

    private fun writePng(pixels: ByteArray, width: Int, height: Int, file: File): Boolean {
        val bitmap = Bitmap.createBitmap(width, height)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Volatile var lastRenderMs = 0.0

    fun pixelAt(x: Float, y: Float): Int? {
        val img = shownTarget ?: return null
        return img.getColor((x * img.width).toInt().coerceIn(0, img.width - 1), (y * img.height).toInt().coerceIn(0, img.height - 1))
    }

    suspend fun timing(): String = "{}"

    @Synchronized fun close() {
        if (closed) return
        closed = true
        audio?.close()
        mediaJob?.cancel()
        executor.execute {
            try { videos.values.forEach { it.close() }; videos.clear(); mosh?.close() }
            finally { engine?.close(); engine = null; executor.shutdown() }
        }
    }
}

private val instances = AtomicLong()


private const val LIVE_VIDEO_WAIT_MS = 12
