package app.absentmotion.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
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

    fun fits(width: Int, height: Int): Boolean = width > 0 && height > 0 && min(width, height) <= maxShortSide
}

data class EnhanceOptions(val mode: EnhanceMode = EnhanceMode.Upscale2, val cartoon: Boolean = false, val grain: Boolean = false, val quick: Boolean = false) {
    internal val model: AiModel get() = if (cartoon) AiModel.Cartoon else AiModel.Detail
    internal val blend: AiModel? get() = if (!cartoon && grain) AiModel.Grain else null
    internal val models: List<AiModel> get() = listOfNotNull(model, blend)
    val usesAi: Boolean get() = !(mode == EnhanceMode.Sharpen && quick)
    val xml: String get() = mode.xml + (if (cartoon) "+cartoon" else "") + (if (blend != null) "+grain" else "")

    internal fun gmacPerFrame(width: Int, height: Int): Double = model.gmacPerMegapixel *
        if (mode == EnhanceMode.Faces) FACE_MEGAPIXELS else width.toDouble() * height / 1e6 * TILE_OVERHEAD

    companion object {
        fun parse(xml: String?): EnhanceOptions? {
            val parts = xml?.split('+') ?: return null
            val mode = EnhanceMode.entries.firstOrNull { it.xml == parts.first() } ?: return null
            return EnhanceOptions(mode, "cartoon" in parts, "grain" in parts)
        }
    }
}

internal const val GRAIN_WEIGHT = 0.5f
internal const val TILE_OVERHEAD = 1.17
internal const val FACE_MEGAPIXELS = 0.1

internal class FaceBox(var cx: Float, var cy: Float, var side: Float, var missed: Int = 0)

internal fun trackFaces(tracks: MutableList<FaceBox>, found: List<FaceBox>, keep: Int = 8, follow: Float = 0.4f): List<FaceBox> {
    val unmatched = tracks.toMutableList()
    for (face in found) {
        val near = unmatched.minByOrNull { abs(it.cx - face.cx) + abs(it.cy - face.cy) }
            ?.takeIf { abs(it.cx - face.cx) + abs(it.cy - face.cy) < max(it.side, face.side) * 0.6f }
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

private class FaceWork(private val net: AiNet) : FrameWork {
    private val tracks = mutableListOf<FaceBox>()

    override suspend fun frame(pts: Long, argb: IntArray, width: Int, height: Int, emit: suspend (Long, IntArray) -> Unit) {
        for (face in trackFaces(tracks, detect(argb, width, height))) {
            val side = face.side.roundToInt().coerceIn(16, min(width, height))
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
        val k = min(1f, DETECT_SIDE / max(width, height).toFloat())
        val w = max(2, (width * k).toInt() and 1.inv())
        val h = max(1, (height * k).toInt())
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888), w, h, true)
        val rgb = small.copy(Bitmap.Config.RGB_565, false)
        small.recycle()
        try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(w, h, MAX_FACES).findFaces(rgb, faces)
            val mid = PointF()
            faces.take(count).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }.map { face ->
                face.getMidPoint(mid)
                val eyes = face.eyesDistance() / k
                FaceBox(mid.x / k, mid.y / k + eyes * 0.25f, eyes * 3.2f)
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

internal object AiEnhance {
    suspend fun run(context: Context, layer: Layer, media: MediaDeclaration, options: EnhanceOptions, progress: AiProgress): AiMedia {
        check(options.mode.fits(media.width, media.height)) { capText(options.mode) }
        progress(0f, AppText.get(R.string.ai_loading_ai_model))
        AiDevice.probeGpu(context, options.model)
        val net = withContext(Dispatchers.IO) { AiNet.open(context, options.model, options.blend, GRAIN_WEIGHT) }
        return net.use {
            val work = if (options.mode == EnhanceMode.Faces) FaceWork(net)
                else FrameWork { pts, argb, width, height, emit -> emit(pts, net.upscale(argb, width, height, options.mode.scale)) }
            rewriteVideo(context, VideoJob(layer, media, options.mode.scale), progress, work)
        }
    }

    fun capText(mode: EnhanceMode): String = AppText.get(R.string.ai_enhance_cap, AppText.get(mode.title), mode.maxShortSide.toString())
}
