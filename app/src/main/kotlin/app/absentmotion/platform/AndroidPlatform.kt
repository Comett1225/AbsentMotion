package app.absentmotion.platform

import androidx.compose.ui.graphics.asImageBitmap as androidImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

fun xmlParser() = android.util.Xml.newPullParser()
@androidx.compose.runtime.Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
object ActivityResultContracts {
    fun OpenDocument() = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    fun OpenMultipleDocuments() = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
}
@androidx.compose.runtime.Composable
fun <I, O> rememberLauncherForActivityResult(contract: androidx.activity.result.contract.ActivityResultContract<I, O>, onResult: (O) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(contract, onResult)
fun availableCodecs(): List<app.absentmotion.export.Codec> {
    val supported = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { it.supportedTypes.asList() }
    return app.absentmotion.export.Codec.entries.filter { it.mime in supported }
}
@androidx.compose.runtime.Composable
fun appVersion(): String {
    val context = LocalContext.current
    return androidx.compose.runtime.remember { runCatching { context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName }.getOrNull().orEmpty() }
}
@androidx.compose.runtime.Composable
fun SceneSurface(engine: app.absentmotion.engine.EngineThread, lowQuality: Boolean, size: androidx.compose.ui.unit.IntSize, modifier: androidx.compose.ui.Modifier, onView: (Any) -> Unit = {}) {
    androidx.compose.ui.viewinterop.AndroidView(modifier = modifier, factory = { context ->
        android.view.SurfaceView(context).apply {
            onView(this)
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = engine.attach(holder.surface).let {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = engine.detach()
            })
        }
    }, update = { view ->
        if (lowQuality && size.width > 0) view.holder.setFixedSize((size.width / 2).coerceAtLeast(1), (size.height / 2).coerceAtLeast(1))
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
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 288, 288)
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
fun rememberMediaPicker(onResult: (Uri?) -> Unit): MediaPicker {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onResult(uri)
    }
    return MediaPicker { launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
}
class DocumentPicker(private val launchBlock: () -> Unit) { fun launch() = launchBlock() }
@androidx.compose.runtime.Composable
fun rememberDocumentPicker(onResult: (Uri?) -> Unit): DocumentPicker {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), onResult)
    return DocumentPicker { launcher.launch(arrayOf("*/*")) }
}
fun wheel(onScroll: (delta: androidx.compose.ui.geometry.Offset, at: androidx.compose.ui.geometry.Offset, zoom: Boolean) -> Unit): androidx.compose.ui.Modifier = androidx.compose.ui.Modifier

fun makeBitmap(width: Int, height: Int): Bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
fun recycleBitmap(bitmap: Bitmap) = bitmap.recycle()
fun encodeRgba(pixels: ByteArray, width: Int, height: Int, settings: app.absentmotion.export.ExportSettings, reuse: Bitmap?): ByteArray {
    val kind = settings.image
    val source = if (kind.alpha) pixels else app.absentmotion.export.ImageEncode.flatten(pixels, settings.background)
    if (kind == app.absentmotion.export.ImageKind.Bmp) return app.absentmotion.export.ImageEncode.bmp(source, width, height)
    val bitmap = reuse ?: makeBitmap(width, height)
    bitmap.isPremultiplied = false
    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(source))
    val out = java.io.ByteArrayOutputStream(pixels.size / 4)
    val format = when (kind) {
        app.absentmotion.export.ImageKind.Jpeg -> android.graphics.Bitmap.CompressFormat.JPEG
        app.absentmotion.export.ImageKind.Webp -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
        app.absentmotion.export.ImageKind.WebpLossless -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> android.graphics.Bitmap.CompressFormat.PNG
    }
    bitmap.compress(format, if (kind.lossy) settings.imageQuality else 100, out)
    if (reuse == null) bitmap.recycle()
    return out.toByteArray()
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
fun shareExport(context: Context, result: app.absentmotion.export.ExportResult) {
    val send = if (result.uris.size > 1) android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(result.uris))
        else android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, result.uris.first())
    send.setType(if (result.uris.size > 1) "image/*" else result.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, null))
}
@androidx.compose.runtime.Composable
fun ExportMediaPreview(kind: app.absentmotion.export.ExportKind, uris: List<Uri>) = app.absentmotion.ui.export.ExportMediaPreview(kind, uris)

fun effectsMessageOnHome() = false

internal const val CAPTION_MODEL_NAME = "ggml-base-q5_1.bin"
internal const val CAPTION_MODEL_BYTES = 59_707_625L
typealias Formatter = android.text.format.Formatter
internal fun loadCaptionLibrary() = System.loadLibrary("whisper_jni")
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
    "Captions are recognised on your phone by a speech model, downloaded once: $name, " +
        "${Formatter.formatShortFileSize(context, bytes)}, from Hugging Face."

class Speech(context: Context) {
    private val context = context.applicationContext
    private val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private val tts = android.speech.tts.TextToSpeech(this.context) { ready.complete(it == android.speech.tts.TextToSpeech.SUCCESS) }
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    init {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { pending.remove(id)?.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { pending.remove(id)?.complete(false) }
            override fun onError(id: String?, code: Int) { pending.remove(id)?.complete(false) }
            override fun onStop(id: String?, interrupted: Boolean) { pending.remove(id)?.complete(false) }
        })
    }

    suspend fun voices(): List<app.absentmotion.ui.editor.SpeechVoice> {
        if (kotlinx.coroutines.withTimeoutOrNull(START_MS) { ready.await() } != true) return emptyList()
        val usable = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        return usable.groupBy { it.locale.displayName }.flatMap { (language, list) ->
            list.sortedBy { it.name }.mapIndexed { i, v -> app.absentmotion.ui.editor.SpeechVoice(v.name, "Voice ${i + 1}", language) }
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
        check(kotlinx.coroutines.withTimeoutOrNull(START_MS) { ready.await() } == true) { "Text to speech is unavailable on this phone" }
        val file = app.absentmotion.ai.aiFile(context, "wav")
        val id = java.util.UUID.randomUUID().toString()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>().also { pending[id] = it }
        try {
            tts.stop()
            use(voice, rate, pitch)
            check(tts.synthesizeToFile(text, null, file, id) == android.speech.tts.TextToSpeech.SUCCESS) { "The voice could not be made" }
            check(kotlinx.coroutines.withTimeoutOrNull(START_MS + 50L * text.length) { done.await() } == true && file.length() > 44) { "The voice could not be made" }
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
