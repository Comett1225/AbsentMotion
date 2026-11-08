package app.absentmotion.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import app.absentmotion.desk.DeskFiles
import app.absentmotion.fonts.composeFamily
import java.io.ByteArrayOutputStream
import java.io.InputStream

typealias Context = android.content.Context
typealias Uri = android.net.Uri
typealias Bitmap = android.graphics.Bitmap
typealias BitmapFactory = android.graphics.BitmapFactory
typealias Color = android.graphics.Color
typealias Matrix = android.graphics.Matrix
typealias Log = android.util.Log
typealias LruCache<K, V> = android.util.LruCache<K, V>
typealias SystemClock = android.os.SystemClock
typealias ContentResolver = android.content.ContentResolver
typealias Toast = android.widget.Toast

object ViewConfiguration {
    fun get(context: Context) = this
    val scaledTouchSlop = 8
    val scaledDoubleTapSlop = 50
    fun getLongPressTimeout() = 500
    fun getDoubleTapTimeout() = 300
    fun getTapTimeout() = 100
}

val LocalContext get() = androidx.compose.ui.platform.LocalContext

fun Bitmap.asImageBitmap(): ImageBitmap = image.toComposeImageBitmap()

fun xmlParser(): org.xmlpull.v1.XmlPullParser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = app.absentmotion.desk.DeskBack.Handler(enabled, onBack)

fun versionName(context: Context): String = app.absentmotion.desk.DeskInfo.version

@Composable
fun appVersion(): String = app.absentmotion.desk.DeskInfo.version

@Composable
fun SceneSurface(engine: app.absentmotion.engine.EngineThread, divisor: Int, size: IntSize, modifier: Modifier, onView: (Any) -> Unit = {}) {
    remember(engine) { onView(engine); 0 }
    androidx.compose.runtime.DisposableEffect(engine, divisor, size) {
        engine.attachDesktop(divisor, size)
        onDispose { engine.detach() }
    }
    Box(modifier.drawBehind {
        val frame = engine.frame ?: return@drawBehind
        val box = this.size
        val s = minOf(box.width / frame.width, box.height / frame.height)
        val w = Math.round(frame.width * s)
        val h = Math.round(frame.height * s)
        drawImage(frame, dstOffset = IntOffset(Math.round((box.width - w) / 2), Math.round((box.height - h) / 2)), dstSize = IntSize(w, h))
    })
}

fun fontFamily(library: app.absentmotion.fonts.FontLibrary, name: String) = library.composeFamily(name)

fun DrawScope.drawLiveStroke(path: DrawPath, filled: Boolean, color: Int, width: Float, opacity: Float, matrix: Matrix, eraser: Boolean) {
    val v = matrix.v
    val m = androidx.compose.ui.graphics.Matrix(floatArrayOf(v[0], v[3], 0f, v[6], v[1], v[4], 0f, v[7], 0f, 0f, 1f, 0f, v[2], v[5], 0f, v[8]))
    val p = androidx.compose.ui.graphics.Path().apply { addPath(path.path); transform(m) }
    val c = if (eraser) androidx.compose.ui.graphics.Color(0x66FFFFFF) else androidx.compose.ui.graphics.Color(color).let { it.copy(alpha = it.alpha * opacity) }
    if (filled) drawPath(p, c)
    else drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

suspend fun slotPicture(context: Context, uri: String, video: Boolean): ImageBitmap? = DeskFiles.thumbnail(uri, video, 288)

object ActivityResultContracts {
    fun OpenDocument() = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    fun OpenMultipleDocuments() = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
}

@Composable
fun <I, O> rememberLauncherForActivityResult(contract: androidx.activity.result.contract.ActivityResultContract<I, O>, onResult: (O) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(contract, onResult)

internal fun android.media.MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default
internal fun android.media.MediaFormat.numberOf(key: String): Number? = if (!containsKey(key)) null else runCatching<Number> { getInteger(key) }.getOrNull()
internal val android.media.MediaCodecInfo.hardware: Boolean get() = false
internal val android.media.MediaCodecInfo.alias: Boolean get() = false
internal val android.content.pm.PackageInfo.versionCodeLong: Long get() = versionCode.toLong()
internal fun android.media.MediaMetadataRetriever.scaledFrame(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? = getScaledFrameAtTime(timeUs, option, width, height)
internal fun webpFormat(lossless: Boolean) = if (lossless) android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS else android.graphics.Bitmap.CompressFormat.WEBP_LOSSY

class MediaPicker(private val launchBlock: () -> Unit) { fun launch() = launchBlock() }

@Composable
fun rememberMediaPicker(videoOnly: Boolean = false, onResult: (Uri?) -> Unit): MediaPicker {
    val latest by rememberUpdatedState(onResult)
    return remember { MediaPicker { latest(DeskFiles.chooseOne(media = true, videoOnly = videoOnly)) } }
}

class DocumentPicker(private val launchBlock: () -> Unit) { fun launch() = launchBlock() }

@Composable
fun rememberDocumentPicker(onResult: (Uri?) -> Unit): DocumentPicker {
    val latest by rememberUpdatedState(onResult)
    return remember { DocumentPicker { latest(DeskFiles.chooseOne(media = false)) } }
}

fun wheel(onScroll: (delta: androidx.compose.ui.geometry.Offset, at: androidx.compose.ui.geometry.Offset, zoom: Boolean) -> Unit): Modifier =
    Modifier.composed {
        val latest by rememberUpdatedState(onScroll)
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    latest(change.scrollDelta, change.position, event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed)
                    change.consume()
                }
            }
        }
    }

fun previewWheel(onScroll: (delta: androidx.compose.ui.geometry.Offset, at: androidx.compose.ui.geometry.Offset, zoom: Boolean, alt: Boolean, shift: Boolean) -> Unit): Modifier =
    Modifier.composed {
        val latest by rememberUpdatedState(onScroll)
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val keys = event.keyboardModifiers
                    latest(change.scrollDelta, change.position, keys.isCtrlPressed || keys.isMetaPressed, keys.isAltPressed, keys.isShiftPressed)
                    change.consume()
                }
            }
        }
    }

fun makeBitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height)
fun recycleBitmap(bitmap: Bitmap) = bitmap.recycle()

fun encodeRgba(pixels: ByteArray, width: Int, height: Int, settings: app.absentmotion.export.ExportSettings, reuse: Bitmap?, out: java.io.OutputStream) {
    val kind = settings.image
    val source = if (kind.alpha) pixels else app.absentmotion.export.ImageEncode.flatten(pixels, settings.background, pixels)
    if (kind == app.absentmotion.export.ImageKind.Bmp) return app.absentmotion.export.ImageEncode.bmp(source, width, height, out)
    val webp = kind == app.absentmotion.export.ImageKind.Webp || kind == app.absentmotion.export.ImageKind.WebpLossless
    val data = if (webp) app.absentmotion.pc.engine.DesktopNative.encodeWebp(source, width, height, if (kind.lossy) settings.imageQuality else 75, !kind.lossy) else {
        val format = if (kind == app.absentmotion.export.ImageKind.Jpeg) org.jetbrains.skia.EncodedImageFormat.JPEG else org.jetbrains.skia.EncodedImageFormat.PNG
        val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        org.jetbrains.skia.Image.makeRaster(info, source, width * 4).use { image -> image.encodeToData(format, if (kind.lossy) settings.imageQuality else 100)?.use { it.bytes } }
    }
    app.absentmotion.export.exportCheck(data != null) {
        app.absentmotion.i18n.txt(app.absentmotion.R.string.export_error_encode_image, app.absentmotion.i18n.txt(kind.label))
    }
    out.write(data!!)
}

@Composable
fun ExportBars(dark: Boolean, running: Boolean) {
    remember { app.absentmotion.export.DeskEncoders.seedReport(android.content.Desk.context) }
}

internal const val MAX_SHARED_PICTURES = 500

fun shareExport(context: Context, result: app.absentmotion.export.ExportResult) = DeskFiles.reveal(result.uris)

@Composable
fun ExportMediaPreview(kind: app.absentmotion.export.ExportKind, uris: List<Uri>, large: Boolean = false, onTap: (() -> Unit)? = null) = app.absentmotion.ui.export.DeskExportPreview(kind, uris, large, onTap)

fun effectsMessageOnHome() = false

fun exportScope(own: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.CoroutineScope = app.absentmotion.export.DeskExports.scope

fun exportEngine(engine: app.absentmotion.engine.EngineThread): app.absentmotion.engine.EngineThread = app.absentmotion.export.DeskExports.engine()

fun exportDone(result: app.absentmotion.export.ExportResult) = app.absentmotion.export.DeskExports.saved(result)

object PlatformFeatures {
    const val desktop = true
    const val ai = true
    val aiFaces = app.absentmotion.desk.DeskOs.mac
    const val camera = true
    const val tools = true
    const val userFiles = true
    const val linkFolders = true
}

fun textFieldFocused(): Boolean = app.absentmotion.desk.DeskTyping.active > 0

fun Modifier.resizeCursor(horizontal: Boolean = true): Modifier =
    pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(if (horizontal) java.awt.Cursor.E_RESIZE_CURSOR else java.awt.Cursor.N_RESIZE_CURSOR)))

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Hint(text: String, content: @Composable () -> Unit) = Hint(text, Modifier, content)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Hint(text: String, modifier: Modifier, content: @Composable () -> Unit) = androidx.compose.foundation.TooltipArea(
    tooltip = {
        androidx.compose.material3.Text(text, color = app.absentmotion.ui.theme.Am.OnDark, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.background(app.absentmotion.ui.theme.Am.Field, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    },
    modifier = modifier,
    delayMillis = 450,
) { content() }

fun linkFolderLabel(): String = "Link folder"

fun libraryFolderName(uri: android.net.Uri): String? = app.absentmotion.desk.DeskLibrary.folderOf(uri)?.let { it.name.ifEmpty { it.path } }

fun removeFromLibrary(uri: android.net.Uri, unlinkFolder: Boolean) = app.absentmotion.desk.DeskLibrary.hide(uri, unlinkFolder)

fun linkMediaFolder() { app.absentmotion.desk.DeskLibrary.chooseFolder()?.let { app.absentmotion.desk.DeskLibrary.link(it) } }

fun libraryFilePath(uri: android.net.Uri): String = uri.toFile()?.path.orEmpty()

fun libraryFolders(): List<String> = app.absentmotion.desk.DeskLibrary.folders.map { it.path }

fun pinnedLibraryFolders(): Set<String> = app.absentmotion.desk.DeskLibrary.pinned.map { it.path }.toSet()

fun pinLibraryFolder(path: String, pin: Boolean) = app.absentmotion.desk.DeskLibrary.pin(java.io.File(path), pin)

fun unlinkLibraryFolder(path: String) = app.absentmotion.desk.DeskLibrary.unlink(java.io.File(path))

fun revealLibraryFolder(path: String) { app.absentmotion.desk.DeskOs.reveal(java.io.File(path)) }

fun revealFolderLabel(): String = app.absentmotion.desk.DeskOs.revealLabel

internal const val CAPTION_MODEL_NAME = "ggml-base-q5_1.bin"
internal const val CAPTION_MODEL_BYTES = 59_707_625L
internal fun loadCaptionLibrary() { app.absentmotion.pc.engine.DesktopNative.hashCode() }
internal fun fastCaptionCpu(cpuinfo: String): Boolean = false

internal fun captionTextHeight(fonts: app.absentmotion.fonts.FontLibrary, text: String, font: String, size: Float, wrap: Int): Float {
    val measurer = androidx.compose.ui.text.TextMeasurer(androidx.compose.ui.text.font.createFontFamilyResolver(), androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr)
    return measurer.measure(text, androidx.compose.ui.text.TextStyle(fontFamily = fonts.composeFamily(font), fontSize = androidx.compose.ui.unit.TextUnit(size * 3f, androidx.compose.ui.unit.TextUnitType.Sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center), constraints = androidx.compose.ui.unit.Constraints(maxWidth = wrap.coerceAtLeast(1))).size.height.toFloat()
}

internal fun fontPreviewStyle(): androidx.compose.ui.text.PlatformTextStyle? = null

internal fun readPreviewPixel(surface: Any?, x: Float, y: Float, complete: (Int?) -> Unit) {
    val engine = surface as? app.absentmotion.engine.EngineThread
    complete(engine?.pixelAt(x, y))
}

internal fun captionDownloadDescription(context: Context, name: String, bytes: Long) =
    context.getString(app.absentmotion.R.string.platform_caption_download, name, app.absentmotion.export.formatBytes(bytes))

class Speech(private val context: Context) {
    val noVoices: Int get() = app.absentmotion.desk.DeskSpeech.noVoices
    suspend fun voices(): List<app.absentmotion.ui.editor.SpeechVoice> = app.absentmotion.desk.DeskSpeech.voices()
    fun preview(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float) = app.absentmotion.desk.DeskSpeech.preview(text, voice, rate, pitch)
    suspend fun toFile(text: String, voice: app.absentmotion.ui.editor.SpeechVoice, rate: Float, pitch: Float): Uri = app.absentmotion.desk.DeskSpeech.toFile(text, voice, rate, pitch, app.absentmotion.ai.aiFile(context, "wav"))
    fun close() = app.absentmotion.desk.DeskSpeech.stop()
    companion object { val MAX_CHARS = 4000 }
}

@Composable
fun DialogStatusBarIcons(darkIcons: Boolean) {}

fun sheetDialogProperties() = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)

internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun InputStream.readInto(target: ByteArray, offset: Int, length: Int): Int {
    var done = 0
    while (done < length) {
        val n = read(target, offset + done, length - done)
        if (n < 0) break
        done += n
    }
    return done
}

internal fun InputStream.drain() {
    val buffer = ByteArray(8192)
    while (true) if (read(buffer) < 0) return
}

internal fun fitBitmap(full: Bitmap, width: Int, height: Int): Bitmap {
    val scale = minOf(width.toFloat() / full.width, height.toFloat() / full.height)
    if (scale >= 1f) return full
    return Bitmap.createScaledBitmap(full, maxOf(1, Math.round(full.width * scale)), maxOf(1, Math.round(full.height * scale)), true)
}

internal fun sampledBitmap(maxSide: Int, open: () -> InputStream?): Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxSide) }
    return open()?.use { BitmapFactory.decodeStream(it, null, options) }
}

internal class StorageAccess {
    fun ready(retry: () -> Unit): Boolean = true
    fun answered(allowed: Boolean) {}
}

@Composable
internal fun rememberStorageAccess(): StorageAccess = remember { StorageAccess() }

internal fun deviceSounds(context: Context): List<app.absentmotion.audio.DeviceAudio> = app.absentmotion.desk.DeskSounds.list(context)

fun revealDeskFile(uri: android.net.Uri) { app.absentmotion.desk.DeskFiles.reveal(listOf(uri)) }

internal fun loadNoiseLibrary() { app.absentmotion.pc.engine.DesktopNative.hashCode() }

internal suspend fun desktopReverse(context: android.content.Context, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.ReversedClip = app.absentmotion.ai.desktopReverse(context, layer, media, progress)

internal suspend fun desktopVideoMatte(context: android.content.Context, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, side: Int, keep: List<app.absentmotion.ai.Tap>, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.VideoMatte = app.absentmotion.ai.desktopVideoMatte(context, layer, media, side, keep, progress)

internal fun loadAiLibrary() { app.absentmotion.pc.engine.DesktopNative.hashCode() }

internal suspend fun desktopRewriteVideo(context: android.content.Context, job: app.absentmotion.ai.VideoJob, progress: app.absentmotion.ai.AiProgress, work: app.absentmotion.ai.FrameWork): app.absentmotion.ai.AiMedia = app.absentmotion.ai.desktopRewriteVideo(context, job, progress, work)

internal suspend fun desktopClipSmooth(context: android.content.Context, engine: app.absentmotion.engine.EngineThread, layer: app.absentmotion.model.Layer, media: app.absentmotion.model.MediaDeclaration, fps: Int, smoothing: app.absentmotion.export.Smoothing, progress: app.absentmotion.ai.AiProgress): app.absentmotion.ai.AiMedia = app.absentmotion.ai.desktopClipSmooth(context, layer, media, fps, smoothing, engine, progress)

internal fun desktopFaces(argb: IntArray, width: Int, height: Int, turn: Int): List<app.absentmotion.ai.FaceBox> = app.absentmotion.ai.desktopFaces(argb, width, height, turn)

internal fun desktopCutout(handle: Long, bitmap: android.graphics.Bitmap, size: Int): Int = app.absentmotion.ai.DeskAiNative.cutout(handle, bitmap, size)

internal suspend fun desktopGifVideo(context: Context, uri: Uri): app.absentmotion.media.GifVideo? = app.absentmotion.media.convertDesktopGif(context, uri)

internal fun desktopFrameReader(context: Context, uri: String): app.absentmotion.media.DesktopFrameInput = app.absentmotion.media.DeskFrameReader(context, uri)

fun rawFrameImage(rgba: ByteArray, width: Int, height: Int): ImageBitmap = org.jetbrains.skia.Bitmap().apply {
    installPixels(org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL), rgba, width * 4)
    setImmutable()
}.asComposeImageBitmap()

fun releaseFrameImage(image: ImageBitmap) {
    javax.swing.SwingUtilities.invokeLater { runCatching { image.asSkiaBitmap().close() } }
}
