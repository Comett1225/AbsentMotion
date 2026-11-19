package app.absentmotion.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import app.absentmotion.media.decodePicture
import app.absentmotion.library.openableUri
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class EnhanceMode(val xml: String, @StringRes val title: Int, @StringRes val hint: Int, @StringRes val help: Int, val scale: Int, val maxShortSide: Int) {
    Upscale2("upscale2", R.string.ai_enhance_upscale2, R.string.ai_enhance_upscale2_hint, R.string.ai_enhance_upscale2_help, 2, 720),
    Upscale4("upscale4", R.string.ai_enhance_upscale4, R.string.ai_enhance_upscale4_hint, R.string.ai_enhance_upscale4_help, 4, 360),
    Sharpen("sharpen", R.string.ai_enhance_sharpen, R.string.ai_enhance_sharpen_hint, R.string.ai_enhance_sharpen_help, 1, 1080),
    Faces("faces", R.string.ai_enhance_faces, R.string.ai_enhance_faces_hint, R.string.ai_enhance_faces_help, 1, 2160);

    val imageHint: Int get() = when (this) {
        Upscale2 -> R.string.ai_enhance_image_upscale2_hint
        Upscale4 -> R.string.ai_enhance_image_upscale4_hint
        else -> hint
    }

    val imageHelp: Int get() = when (this) {
        Upscale2 -> R.string.ai_enhance_image_upscale2_help
        Upscale4 -> R.string.ai_enhance_image_upscale4_help
        Sharpen -> R.string.ai_enhance_image_sharpen_help
        Faces -> R.string.ai_enhance_image_faces_help
    }

    fun fits(width: Int, height: Int): Boolean = width > 0 && height > 0 && min(width, height) <= maxShortSide
}

data class EnhanceOptions(val mode: EnhanceMode = EnhanceMode.Upscale2, val cartoon: Boolean = false, val grain: Boolean = false, val quick: Boolean = false, val max: Boolean = false) {
    internal val model: AiModel get() = when {
        max && cartoon -> AiModel.CartoonMax
        max -> AiModel.DetailMax
        cartoon -> AiModel.Cartoon
        else -> AiModel.Detail
    }
    internal val blend: AiModel? get() = if (!cartoon && !max && grain) AiModel.Grain else null
    internal val models: List<AiModel> get() = listOfNotNull(model, blend)
    internal fun forPlatform(image: Boolean, desktop: Boolean): EnhanceOptions = copy(max = max && image && desktop)
    val usesAi: Boolean get() = !(mode == EnhanceMode.Sharpen && quick)
    val xml: String get() = mode.xml + (if (cartoon) "+cartoon" else "") + (if (!cartoon && grain) "+grain" else "") + (if (max) "+max" else "")

    internal fun gmacPerFrame(width: Int, height: Int): Double = model.gmacPerMegapixel *
        if (mode == EnhanceMode.Faces) FACE_MEGAPIXELS else width.toDouble() * height / 1e6 * TILE_OVERHEAD

    companion object {
        fun parse(xml: String?): EnhanceOptions? {
            val parts = xml?.split('+') ?: return null
            val mode = EnhanceMode.entries.firstOrNull { it.xml == parts.first() } ?: return null
            return EnhanceOptions(mode, "cartoon" in parts, "grain" in parts, max = "max" in parts)
        }
    }
}

internal const val GRAIN_WEIGHT = 0.5f
internal const val QUICK_SHARPEN = "absent.sharpen"
internal const val TILE_OVERHEAD = 1.17
internal const val FACE_MEGAPIXELS = 0.1

internal class FaceBox(var cx: Float, var cy: Float, var side: Float, var missed: Int = 0)

internal fun trackFaces(tracks: MutableList<FaceBox>, found: List<FaceBox>, keep: Int = 8, follow: Float = 0.4f): List<FaceBox> {
    val unmatched = tracks.toMutableList()
    for (face in found) {
        val near = unmatched.filter { abs(it.cx - face.cx) + abs(it.cy - face.cy) < max(it.side, face.side) * 0.6f }
            .minByOrNull { abs(it.cx - face.cx) + abs(it.cy - face.cy) }
        if (near == null) { tracks += FaceBox(face.cx, face.cy, face.side); continue }
        unmatched -= near
        near.cx += (face.cx - near.cx) * follow
        near.cy += (face.cy - near.cy) * follow
        near.side += (face.side - near.side) * follow
        near.missed = 0
    }
    unmatched.forEach { it.missed++ }
    tracks.removeAll { it.missed > keep }
    return tracks.toList()
}

internal fun featherWeight(x: Int, y: Int, width: Int, height: Int, edge: Float = 0.3f): Float {
    val dx = (x + 0.5f - width / 2f) / (width / 2f)
    val dy = (y + 0.5f - height / 2f) / (height / 2f)
    return ((1 - sqrt(dx * dx + dy * dy)) / edge).coerceIn(0f, 1f)
}

internal fun mixArgb(base: Int, top: Int, weight: Float): Int {
    if (weight >= 1f) return top
    if (weight <= 0f) return base
    var out = base and 0xFF000000.toInt()
    for (shift in intArrayOf(16, 8, 0)) {
        val a = (base shr shift) and 0xFF
        val b = (top shr shift) and 0xFF
        out = out or ((a + (b - a) * weight).roundToInt().coerceIn(0, 255) shl shift)
    }
    return out
}

internal fun bleedUnderClear(pixels: IntArray, width: Int, height: Int, passes: Int = 4) {
    val known = BooleanArray(pixels.size) { pixels[it] ushr 24 != 0 }
    if (known.all { it } || known.none { it }) return
    repeat(passes) {
        val filled = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (known[i]) continue
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                val c = pixels[ny * width + nx]
                if (!known[ny * width + nx]) continue
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            if (n == 0) continue
            pixels[i] = (pixels[i] and 0xFF000000.toInt()) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            filled += i
        }
        if (filled.isEmpty()) return
        for (i in filled) known[i] = true
    }
}

internal fun scaledAlpha(input: IntArray, width: Int, height: Int, scale: Int, output: IntArray) {
    fun a(px: Int, py: Int) = (input[py * width + px] ushr 24).toFloat()
    for (y in 0 until height * scale) {
        val sy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, height - 1f)
        val y0 = sy.toInt()
        val y1 = minOf(y0 + 1, height - 1)
        val fy = sy - y0
        for (x in 0 until width * scale) {
            val sx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, width - 1f)
            val x0 = sx.toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val fx = sx - x0
            val top = a(x0, y0) + (a(x1, y0) - a(x0, y0)) * fx
            val bottom = a(x0, y1) + (a(x1, y1) - a(x0, y1)) * fx
            val i = y * width * scale + x
            output[i] = (output[i] and 0x00FFFFFF) or ((top + (bottom - top) * fy).roundToInt().coerceIn(0, 255) shl 24)
        }
    }
}

internal fun uprightToFrame(x: Float, y: Float, width: Float, height: Float, turn: Int): Pair<Float, Float> = when (turn) {
    90 -> y to height - x
    180 -> width - x to height - y
    270 -> width - y to x
    else -> x to y
}

internal fun displayTurn(orientation: Int): Int = ((orientation % 360) + 360) % 360 / 90 * 90

private class FaceWork(private val net: AiNet, orientation: Int) : FrameWork {
    private val tracks = mutableListOf<FaceBox>()
    private val turn = displayTurn(orientation)
    var found = false
        private set

    override suspend fun frame(pts: Long, argb: IntArray, width: Int, height: Int, emit: suspend (Long, IntArray) -> Unit) {
        val seen = detect(argb, width, height)
        if (seen.isNotEmpty()) found = true
        for (face in trackFaces(tracks, seen)) {
            val side = face.side.roundToInt().coerceAtLeast(16).coerceAtMost(min(width, height))
            val x0 = (face.cx - side / 2f).roundToInt().coerceIn(0, width - side)
            val y0 = (face.cy - side / 2f).roundToInt().coerceIn(0, height - side)
            val crop = IntArray(side * side) { argb[(y0 + it / side) * width + x0 + it % side] }
            val sharp = net.upscale(crop, side, side, 1)
            for (y in 0 until side) for (x in 0 until side) {
                val at = (y0 + y) * width + x0 + x
                argb[at] = mixArgb(argb[at], sharp[y * side + x], featherWeight(x, y, side, side))
            }
        }
        emit(pts, argb)
    }

    private suspend fun detect(argb: IntArray, width: Int, height: Int): List<FaceBox> = withContext(Dispatchers.Default) {
        if (app.absentmotion.platform.PlatformFeatures.desktop) return@withContext app.absentmotion.platform.desktopFaces(argb, width, height, turn)
        val k = min(1f, DETECT_SIDE / max(width, height).toFloat())
        val w = max(2, (width * k).toInt() and 1.inv())
        val h = max(2, (height * k).toInt() and 1.inv())
        val full = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(full, w, h, true)
        if (scaled !== full) full.recycle()
        val upright = if (turn == 0) scaled else Bitmap.createBitmap(scaled, 0, 0, w, h, Matrix().apply { postRotate(turn.toFloat()) }, true).also { scaled.recycle() }
        val rgb = upright.copy(Bitmap.Config.RGB_565, false)
        upright.recycle()
        try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(rgb.width, rgb.height, MAX_FACES).findFaces(rgb, faces)
            val mid = PointF()
            val toFrame = width.toFloat() / w
            faces.take(count).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }.map { face ->
                face.getMidPoint(mid)
                val eyes = face.eyesDistance()
                val (x, y) = uprightToFrame(mid.x, mid.y + eyes * 0.25f, w.toFloat(), h.toFloat(), turn)
                FaceBox(x * toFrame, y * (height.toFloat() / h), eyes * toFrame * 3.2f)
            }
        } finally {
            rgb.recycle()
        }
    }

    companion object {
        const val DETECT_SIDE = 640f
        const val MAX_FACES = 4
        const val MIN_CONFIDENCE = 0.3f
    }
}

internal fun imageEstimateText(estimate: AiEstimate): String = when (estimate.verdict) {
    AiVerdict.Cannot -> AppText.get(R.string.ai_image_cannot)
    AiVerdict.Struggle -> AppText.get(R.string.ai_image_struggle, aiDuration(estimate.seconds))
    AiVerdict.Fine -> if (estimate.seconds < 60) AppText.get(R.string.ai_image_under_minute) else AppText.get(R.string.ai_image_about_time, aiDuration(estimate.seconds))
}

internal fun imagePixelCap(mode: EnhanceMode, max: Boolean): Long = when (mode) {
    EnhanceMode.Upscale2 -> if (max) 4_000_000 else 2_000_000
    EnhanceMode.Upscale4 -> if (max) 1_000_000 else 750_000
    EnhanceMode.Sharpen -> if (max) 8_000_000 else 6_000_000
    EnhanceMode.Faces -> 8_000_000
}

internal fun imageFits(width: Int, height: Int, options: EnhanceOptions): Boolean =
    width > 0 && height > 0 && max(width, height) <= 8192 && width.toLong() * height <= imagePixelCap(options.mode, options.max)

internal suspend fun upscaleInStrips(net: AiNet, input: IntArray, width: Int, height: Int, scale: Int, rows: Int = AiNet.TILE, done: suspend (Float) -> Unit): IntArray {
    if (height <= rows) return net.upscale(input, width, height, scale).also { done(1f) }
    val output = IntArray(width * scale * height * scale)
    val line = width * scale
    var top = 0
    while (top < height) {
        coroutineContext.ensureActive()
        val bottom = min(height, top + rows)
        val from = max(0, top - AiNet.OVERLAP)
        val to = min(height, bottom + AiNet.OVERLAP)
        val strip = net.upscale(input.copyOfRange(from * width, to * width), width, to - from, scale)
        System.arraycopy(strip, (top - from) * scale * line, output, top * scale * line, (bottom - top) * scale * line)
        top = bottom
        done(top.toFloat() / height)
    }
    return output
}

internal object AiEnhance {
    suspend fun image(context: Context, uri: String, media: MediaDeclaration, options: EnhanceOptions, progress: AiProgress, upright: Boolean = false): AiMedia {
        check(imageFits(media.width, media.height, options)) { imageCapText(options) }
        var file: java.io.File? = null
        var picture: Bitmap? = null
        var result: Bitmap? = null
        var success = false
        try {
            progress(0f, AppText.get(R.string.ai_loading_ai_model))
            AiDevice.probeGpu(context, options.model)
            picture = checkNotNull(decodePicture(context.contentResolver, Uri.parse(openableUri(uri)), max(media.width, media.height), keepOutOfMemory = true)) { AppText.get(R.string.ai_cannot_decode_image) }
            val width = picture.width
            val height = picture.height
            check(imageFits(width, height, options)) { imageCapText(options) }
            val input = IntArray(width * height)
            picture.getPixels(input, 0, width, 0, 0, width, height)
            if (picture.hasAlpha()) bleedUnderClear(input, width, height)
            progress(0.1f, AppText.get(R.string.ai_preparing))
            val output = AiNet.open(context, options.model, options.blend, GRAIN_WEIGHT).use { net ->
                if (options.mode == EnhanceMode.Faces) {
                    val work = FaceWork(net, media.orientation)
                    var pixels: IntArray? = null
                    work.frame(0, input, width, height) { _, frame -> pixels = frame }
                    check(work.found) { AppText.get(R.string.ai_enhance_no_faces) }
                    checkNotNull(pixels)
                } else upscaleInStrips(net, input, width, height, options.mode.scale, (height / 10).coerceIn(64, AiNet.TILE)) { done ->
                    progress(0.1f + 0.85f * done, AppText.get(R.string.tools_enhance_running, (done * 100).toInt()))
                }
            }
            coroutineContext.ensureActive()
            val scale = options.mode.scale
            if (picture.hasAlpha() && scale > 1) {
                scaledAlpha(input, width, height, scale, output)
            } else if (picture.hasAlpha() && options.mode != EnhanceMode.Faces) {
                for (i in output.indices) output[i] = (output[i] and 0x00ffffff) or (input[i] and 0xff000000.toInt())
            }
            result = Bitmap.createBitmap(output, width * scale, height * scale, Bitmap.Config.ARGB_8888)
            if (upright && media.orientation != 0) result = result.turned(media.orientation).also { if (it !== result) result.recycle() }
            file = aiFile(context, "png")
            file.outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) { AppText.get(R.string.ai_cannot_save_image) } }
            coroutineContext.ensureActive()
            return aiMedia(context, file, "image/png", mapOf("width" to "${result.width}", "height" to "${result.height}", "orientation" to "${if (upright) 0 else media.orientation}")).also { success = true }
        } finally {
            picture?.recycle()
            result?.recycle()
            if (!success) file?.delete()
        }
    }

    fun imageCapText(options: EnhanceOptions): String = AppText.get(R.string.ai_enhance_image_cap, (imagePixelCap(options.mode, options.max) / 1_000_000.0).toString())

    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, options: EnhanceOptions, progress: AiProgress): AiMedia {
        check(options.mode.fits(media.width, media.height)) { capText(options.mode) }
        progress(0f, AppText.get(R.string.ai_loading_ai_model))
        AiDevice.probeGpu(context, options.model)
        val net = AiNet.open(context, options.model, options.blend, GRAIN_WEIGHT)
        return net.use {
            val faces = if (options.mode == EnhanceMode.Faces) FaceWork(net, media.orientation) else null
            val work = faces ?: FrameWork { pts, argb, width, height, emit -> emit(pts, net.upscale(argb, width, height, options.mode.scale)) }
            rewriteVideo(context, VideoJob(layer, media, options.mode.scale), progress, work).also {
                if (faces != null && !faces.found) {
                    it.file.delete()
                    error(AppText.get(R.string.ai_enhance_no_faces))
                }
            }
        }
    }

    fun capText(mode: EnhanceMode): String = AppText.get(R.string.ai_enhance_cap, AppText.get(mode.title), mode.maxShortSide.toString())
}
