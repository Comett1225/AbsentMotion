package app.absentmotion.platform

import android.os.Build
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxSize
import app.absentmotion.R
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt

import androidx.compose.ui.graphics.asImageBitmap as androidImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import app.absentmotion.library.openableUri

typealias Context = android.content.Context
typealias Uri = android.net.Uri
typealias Bitmap = android.graphics.Bitmap
typealias BitmapFactory = android.graphics.BitmapFactory
typealias Color = android.graphics.Color
typealias Matrix = android.graphics.Matrix
typealias Log = android.util.Log
typealias LruCache<K, V> = android.util.LruCache<K, V>
typealias SystemClock = android.os.SystemClock
typealias ViewConfiguration = android.view.ViewConfiguration
val LocalContext get() = androidx.compose.ui.platform.LocalContext
fun Bitmap.asImageBitmap() = this.androidImageBitmap()

fun xmlParser() = android.util.Xml.newPullParser().apply { runCatching { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } }
@androidx.compose.runtime.Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
object ActivityResultContracts {
    fun OpenDocument() = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    fun OpenMultipleDocuments() = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
}
@androidx.compose.runtime.Composable
fun <I, O> rememberLauncherForActivityResult(contract: androidx.activity.result.contract.ActivityResultContract<I, O>, onResult: (O) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(contract, onResult)
fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
@androidx.compose.runtime.Composable
fun appVersion(): String {
    val context = LocalContext.current
    return androidx.compose.runtime.remember { versionName(context) }
}
@androidx.compose.runtime.Composable
fun SceneSurface(engine: app.absentmotion.engine.EngineThread, divisor: Int, size: androidx.compose.ui.unit.IntSize, modifier: androidx.compose.ui.Modifier, onView: (Any) -> Unit = {}) {
    androidx.compose.ui.viewinterop.AndroidView(modifier = if (Build.VERSION.SDK_INT < 33) modifier.clipToBounds() else modifier, factory = { context ->
        android.view.SurfaceView(context).apply {
            onView(this)
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = engine.attach(holder.surface).let {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = engine.detach()
            })
        }
    }, update = { view ->
        if (divisor > 1 && size.width > 0) view.holder.setFixedSize((size.width / divisor).coerceAtLeast(1), (size.height / divisor).coerceAtLeast(1))
        else view.holder.setSizeFromLayout()
    })
}

typealias ContentResolver = android.content.ContentResolver
typealias Toast = android.widget.Toast

fun fontFamily(library: app.absentmotion.fonts.FontLibrary, name: String) = androidx.compose.ui.text.font.FontFamily(library.typeface(name))


fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveStroke(path: DrawPath, filled: Boolean, color: Int, width: Float, opacity: Float, matrix: Matrix, eraser: Boolean) {
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = if (eraser) 0x66FFFFFF else color
        if (!eraser) paint.alpha = (paint.alpha * opacity).roundToInt().coerceIn(0, 255)
        if (!filled) {
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = width
            paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        c.save(); c.concat(matrix); c.drawPath(path.path.asAndroidPath(), paint); c.restore()
    }
}

suspend fun slotPicture(context: Context, uri: String, video: Boolean): androidx.compose.ui.graphics.ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        if (video) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(openableUri(uri)))
                retriever.scaledFrame(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 288, 288)
            } finally { retriever.release() }
        } else {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 288).coerceAtLeast(1)) }
            resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) }
        }?.asImageBitmap()
    }.getOrNull()
}

class MediaPicker(private val launchBlock: () -> Unit) { fun launch() = launchBlock() }
@androidx.compose.runtime.Composable
fun rememberMediaPicker(videoOnly: Boolean = false, onResult: (Uri?) -> Unit): MediaPicker {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onResult(uri)
    }
    val wanted = if (videoOnly) androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly else androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
    return MediaPicker { launcher.launch(androidx.activity.result.PickVisualMediaRequest(wanted)) }
}
class DocumentPicker(private val launchBlock: () -> Unit) { fun launch() = launchBlock() }
@androidx.compose.runtime.Composable
fun rememberDocumentPicker(onResult: (Uri?) -> Unit): DocumentPicker {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), onResult)
    return DocumentPicker { launcher.launch(arrayOf("*/*")) }
}
fun wheel(onScroll: (delta: androidx.compose.ui.geometry.Offset, at: androidx.compose.ui.geometry.Offset, zoom: Boolean) -> Unit): androidx.compose.ui.Modifier =
    androidx.compose.ui.Modifier.composed {
        val latest by androidx.compose.runtime.rememberUpdatedState(onScroll)
        androidx.compose.ui.Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    latest(change.scrollDelta, change.position, event.keyboardModifiers.isCtrlPressed)
                    change.consume()
                }
            }
        }
    }

fun previewWheel(onScroll: (delta: androidx.compose.ui.geometry.Offset, at: androidx.compose.ui.geometry.Offset, zoom: Boolean, alt: Boolean, shift: Boolean) -> Unit): androidx.compose.ui.Modifier = androidx.compose.ui.Modifier

fun makeBitmap(width: Int, height: Int): Bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
fun recycleBitmap(bitmap: Bitmap) = bitmap.recycle()
fun encodeRgba(pixels: ByteArray, width: Int, height: Int, settings: app.absentmotion.export.ExportSettings, reuse: Bitmap?, out: java.io.OutputStream) {
    val kind = settings.image
    val source = if (kind.alpha) pixels else app.absentmotion.export.ImageEncode.flatten(pixels, settings.background, pixels)
    if (kind == app.absentmotion.export.ImageKind.Bmp) return app.absentmotion.export.ImageEncode.bmp(source, width, height, out)
    val bitmap = reuse ?: makeBitmap(width, height)
    try {
        bitmap.isPremultiplied = false
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(source))
        val format = when (kind) {
            app.absentmotion.export.ImageKind.Jpeg -> android.graphics.Bitmap.CompressFormat.JPEG
            app.absentmotion.export.ImageKind.Webp, app.absentmotion.export.ImageKind.WebpLossless -> webpFormat(kind == app.absentmotion.export.ImageKind.WebpLossless)
            else -> android.graphics.Bitmap.CompressFormat.PNG
        }
        app.absentmotion.export.exportCheck(bitmap.compress(format, if (kind.lossy) settings.imageQuality else 100, out)) {
            app.absentmotion.i18n.txt(R.string.export_error_encode_image, app.absentmotion.i18n.txt(kind.label))
        }
    } finally {
        if (reuse == null) bitmap.recycle()
    }
}
@androidx.compose.runtime.Composable
fun ExportBars(dark: Boolean, running: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(dark) {
        val bars = (view.context as? android.app.Activity)?.window?.let { androidx.core.view.WindowInsetsControllerCompat(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val navigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = !dark
        if (dark) bars?.isAppearanceLightNavigationBars = false
        onDispose { status?.let { bars.isAppearanceLightStatusBars = it }; if (dark) navigation?.let { bars.isAppearanceLightNavigationBars = it } }
    }
    androidx.compose.runtime.DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
}
internal const val MAX_SHARED_PICTURES = 500
fun shareExport(context: Context, result: app.absentmotion.export.ExportResult) {
    val many = result.uris.size > 1
    val send = if (many) android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(result.uris))
        else android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, result.uris.first())
    send.setType(if (many) "image/*" else result.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    fun tooMany() = Toast.makeText(context, context.getString(R.string.export_share_too_many, result.where), Toast.LENGTH_LONG).show()
    if (many && result.uris.size > MAX_SHARED_PICTURES) return tooMany()
    try {
        context.startActivity(android.content.Intent.createChooser(send, null))
    } catch (e: RuntimeException) {
        if (!many) throw e
        android.util.Log.w("AMShare", "cannot share ${result.uris.size} pictures at once", e)
        tooMany()
    }
}
@androidx.compose.runtime.Composable
fun ExportMediaPreview(kind: app.absentmotion.export.ExportKind, uris: List<Uri>, large: Boolean = false, onTap: (() -> Unit)? = null) = app.absentmotion.ui.export.ExportMediaPreview(kind, uris, large, onTap)

fun effectsMessageOnHome() = false

fun exportScope(own: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.CoroutineScope = own

fun exportEngine(engine: app.absentmotion.engine.EngineThread): app.absentmotion.engine.EngineThread = engine

fun exportDone(result: app.absentmotion.export.ExportResult) {}

suspend fun compressVideoOnDesktop(uri: String, job: app.absentmotion.tools.VideoJob, audio: Boolean, durationUs: Long, out: java.io.File, progress: (Float) -> Unit, startUs: Long = 0, endUs: Long = 0) = Unit

suspend fun extractAudioOnDesktop(uri: String, job: app.absentmotion.tools.AudioJob, durationUs: Long, out: java.io.File, progress: (Float) -> Unit) = Unit

suspend fun trimCopyOnDesktop(uri: String, startUs: Long, endUs: Long, out: java.io.File, progress: (Float) -> Unit) = Unit

object PlatformFeatures {
    const val desktop = false
    const val ai = true
    const val aiFaces = true
    const val camera = true
    const val tools = true
    const val userFiles = false
    const val linkFolders = false
}

fun textFieldFocused(): Boolean = false

fun androidx.compose.ui.Modifier.resizeCursor(horizontal: Boolean = true): androidx.compose.ui.Modifier = this

@androidx.compose.runtime.Composable
fun Hint(text: String, content: @androidx.compose.runtime.Composable () -> Unit) = content()

@androidx.compose.runtime.Composable
fun Hint(text: String, modifier: androidx.compose.ui.Modifier, content: @androidx.compose.runtime.Composable () -> Unit) = content()

fun linkFolderLabel(): String = ""

fun libraryFolderName(uri: android.net.Uri): String? = null

fun removeFromLibrary(uri: android.net.Uri, unlinkFolder: Boolean) {}

fun linkMediaFolder() {}

fun libraryFilePath(uri: android.net.Uri): String = uri.path.orEmpty()

fun libraryFolders(): List<String> = emptyList()

fun pinnedLibraryFolders(): Set<String> = emptySet()

fun pinLibraryFolder(path: String, pin: Boolean) {}

fun unlinkLibraryFolder(path: String) {}

fun revealLibraryFolder(path: String) {}

fun revealFolderLabel(): String = ""

internal const val CAPTION_MODEL_NAME = "ggml-base-q5_1.bin"
internal const val CAPTION_MODEL_BYTES = 59_707_625L
internal fun loadCaptionLibrary() {
    val fast = runCatching { app.absentmotion.engine.NativeEngine.fastCaptionCpu() }
        .getOrElse { fastCaptionCpu(runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")) }
    android.util.Log.i("AMCaptions", "caption library " + loadCaptionLibrary(fast, System::loadLibrary))
}
internal fun loadCaptionLibrary(fast: Boolean, load: (String) -> Unit): String {
    if (fast) try { load("whisper_jni_fast"); return "whisper_jni_fast" } catch (_: UnsatisfiedLinkError) {}
    load("whisper_jni")
    return "whisper_jni"
}
internal fun fastCaptionCpu(cpuinfo: String): Boolean {
    val cores = cpuinfo.lineSequence().filter { it.startsWith("Features") }.map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }.toList()
    return cores.isNotEmpty() && cores.all { it.containsAll(listOf("asimddp", "asimdhp", "fphp", "atomics", "asimdrdm", "crc32")) }
}
internal fun captionTextHeight(fonts: app.absentmotion.fonts.FontLibrary, text: String, font: String, size: Float, wrap: Int): Float {
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 3f; typeface = fonts.typeface(font) }
    return android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, wrap).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(true).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build().height.toFloat()
}
internal fun fontPreviewStyle() = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)

internal fun readPreviewPixel(surface: Any?, x: Float, y: Float, complete: (Int?) -> Unit) {
    val view = surface as? android.view.SurfaceView
    val frame = view?.holder?.surfaceFrame
    if (view == null || frame == null || frame.width() <= 0 || frame.height() <= 0) { complete(null); return }
    val px = (x * frame.width()).toInt().coerceIn(0, frame.width() - 1)
    val py = (y * frame.height()).toInt().coerceIn(0, frame.height() - 1)
    val pixel = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    runCatching {
        android.view.PixelCopy.request(view, android.graphics.Rect(px, py, px + 1, py + 1), pixel, { result ->
            complete(if (result == android.view.PixelCopy.SUCCESS) pixel.getPixel(0, 0) else null)
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }.onFailure { complete(null) }
}

internal fun captionDownloadDescription(context: Context, name: String, bytes: Long) =
    context.getString(R.string.platform_caption_download, name, app.absentmotion.export.formatBytes(bytes))

class Speech(context: Context) {
    private val context = context.applicationContext
    private val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private val tts = android.speech.tts.TextToSpeech(this.context) { ready.complete(it == android.speech.tts.TextToSpeech.SUCCESS) }
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    val noVoices = R.string.voice_none_offline

    init {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { pending.remove(it) }?.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { pending.remove(it) }?.complete(false) }
            override fun onError(id: String?, code: Int) { id?.let { pending.remove(it) }?.complete(false) }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { pending.remove(it) }?.complete(false) }
        })
    }

    suspend fun voices(): List<app.absentmotion.ui.editor.SpeechVoice> {
        if (kotlinx.coroutines.withTimeoutOrNull(START_MS) { ready.await() } != true) return emptyList()
        val usable = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        return usable.groupBy { it.locale.displayName }.flatMap { (language, list) ->
            list.sortedBy { it.name }.mapIndexed { i, v -> app.absentmotion.ui.editor.SpeechVoice(v.name, context.getString(R.string.platform_voice_name, (i + 1).toString()), language) }
        }
    }

    private fun use(voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float) {
        tts.voices?.firstOrNull { it.name == voice.id }?.let { tts.voice = it }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
    }

    fun preview(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float) {
        if (!ready.isCompleted || pending.isNotEmpty()) return
        use(voice, rate, pitch)
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    suspend fun toFile(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float): Uri {
        exportCheck(kotlinx.coroutines.withTimeoutOrNull(START_MS) { ready.await() } == true) { txt(R.string.platform_error_tts_unavailable) }
        val file = app.absentmotion.ai.aiFile(context, "wav")
        val id = java.util.UUID.randomUUID().toString()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>().also { pending[id] = it }
        try {
            tts.stop()
            use(voice, rate, pitch)
            exportCheck(tts.synthesizeToFile(text, null, file, id) == android.speech.tts.TextToSpeech.SUCCESS) { txt(R.string.platform_error_voice) }
            exportCheck(kotlinx.coroutines.withTimeoutOrNull(START_MS + 50L * text.length) { done.await() } == true && file.length() > 44) { txt(R.string.platform_error_voice) }
        } catch (e: Throwable) {
            pending.remove(id); tts.stop(); file.delete(); throw e
        }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun close() = tts.shutdown()

    companion object {
        val MAX_CHARS = android.speech.tts.TextToSpeech.getMaxSpeechInputLength()
        private const val START_MS = 60_000L
    }
}

@androidx.compose.runtime.Composable
fun DialogStatusBarIcons(darkIcons: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
    }
}

fun sheetDialogProperties() = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

@Suppress("DEPRECATION")
internal fun webpFormat(lossless: Boolean) = when {
    Build.VERSION.SDK_INT < 30 -> android.graphics.Bitmap.CompressFormat.WEBP
    lossless -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
    else -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
}

@androidx.compose.runtime.Composable
fun EffectPicture(catalog: app.absentmotion.effects.EffectCatalog, path: String?, scale: androidx.compose.ui.layout.ContentScale) {
    val drawable by androidx.compose.runtime.produceState<android.graphics.drawable.Drawable?>(null, catalog, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { catalog.drawable(path) }
    }
    androidx.compose.runtime.DisposableEffect(drawable) {
        val shown = drawable
        onDispose { if (Build.VERSION.SDK_INT >= 28) (shown as? android.graphics.drawable.AnimatedImageDrawable)?.stop() }
    }
    val d = drawable
    when {
        Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable -> androidx.compose.ui.viewinterop.AndroidView({ android.widget.ImageView(it) },
            androidx.compose.ui.Modifier.fillMaxSize().clipToBounds(), update = { v ->
                v.scaleType = if (scale == androidx.compose.ui.layout.ContentScale.Crop) android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
                if (v.drawable !== d) {
                    v.setImageDrawable(d)
                    d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
            })
        d is android.graphics.drawable.BitmapDrawable -> androidx.compose.foundation.Image(d.bitmap.asImageBitmap(), null, androidx.compose.ui.Modifier.fillMaxSize(), contentScale = scale)
        else -> {}
    }
}

private fun known(value: String?): String = value?.takeUnless { it == android.provider.MediaStore.UNKNOWN_STRING }.orEmpty()

internal fun deviceSounds(context: Context): List<app.absentmotion.audio.DeviceAudio> {
    val found = mutableListOf<app.absentmotion.audio.DeviceAudio>()
    val collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val place = if (Build.VERSION.SDK_INT >= 29) "relative_path" else "_data"
    try {
        context.contentResolver.query(collection, arrayOf("_id", "title", "_display_name", "duration", "date_added", "album", "artist", place),
            "duration > 0", null, "date_added DESC, _id DESC")?.use { c ->
            while (c.moveToNext()) {
                val where = c.getString(7).orEmpty()
                found += app.absentmotion.audio.DeviceAudio(android.content.ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: "", c.getLong(3), c.getLong(4), c.getString(2) ?: "", known(c.getString(5)), known(c.getString(6)),
                    if (Build.VERSION.SDK_INT >= 29) where.trimEnd('/') else where.substringBeforeLast('/', ""))
            }
        }
    } catch (_: Exception) { }
    return found
}

fun revealDeskFile(uri: android.net.Uri) {}

internal fun loadNoiseLibrary() { System.loadLibrary("amengine") }

internal suspend fun desktopReverse(context: android.content.Context, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.ReversedClip = error("Desktop only")

internal suspend fun desktopVideoMatte(context: android.content.Context, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, side: Int, keep: List<app.absentmotion.ai.Tap>, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.VideoMatte = error("Desktop only")

internal fun loadAiLibrary() { System.loadLibrary("absent_ai") }

internal suspend fun desktopRewriteVideo(context: android.content.Context, job: app.absentmotion.ai.VideoJob, progress: app.absentmotion.ai.AiProgress, work: app.absentmotion.ai.FrameWork): app.absentmotion.ai.AiMedia = error("Desktop only")

internal suspend fun desktopClipSmooth(context: android.content.Context, engine: app.absentmotion.engine.EngineThread, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, fps: Int, smoothing: app.absentmotion.export.Smoothing, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.AiMedia = error("Desktop only")

internal fun desktopFaces(argb: IntArray, width: Int, height: Int, turn: Int): List<app.absentmotion.ai.FaceBox> = error("Desktop only")

internal fun desktopCutout(handle: Long, bitmap: android.graphics.Bitmap, size: Int): Int = error("Desktop only")

internal suspend fun desktopGifVideo(context: Context, uri: Uri): app.absentmotion.media.GifVideo? = null

internal fun desktopFrameReader(context: Context, uri: String): app.absentmotion.media.DesktopFrameInput? = null

fun rawFrameImage(rgba: ByteArray, width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap =
    android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba)) }.androidImageBitmap()

fun releaseFrameImage(image: androidx.compose.ui.graphics.ImageBitmap) = Unit
