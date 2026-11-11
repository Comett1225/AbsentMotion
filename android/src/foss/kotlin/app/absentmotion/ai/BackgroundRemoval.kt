package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.absentmotion.media.decodePicture
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

internal class ModelNotReady : Exception(AppText.get(R.string.ai_model_not_ready_foss))

internal const val MODEL_ASSET = "absent_bg.abg"
internal const val MODEL_BYTES = 2_943_735L
internal const val MODEL_SHA256 = "9bfbfcba996eb0b0b4f5da03d7dcbce704f5b8810101eb92f62b9e6d44465439"

internal fun modelIntact(file: File): Boolean = file.length() == MODEL_BYTES && file.inputStream().use(CaptionModel::sha256) == MODEL_SHA256

internal object BackgroundRemoval {
    val AVAILABLE: Boolean by lazy { runCatching { System.loadLibrary("amengine") }.isSuccess }
    private var handle = 0L
    @Volatile private var checked = false

    private external fun nativeLoad(path: String): Long
    private external fun nativeMatte(handle: Long, rgb: ByteArray, width: Int, height: Int): ByteArray
    private external fun nativeRelease(handle: Long)

    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, cutout: Cutout = Cutout(), progress: AiProgress): AiMedia {
        if (!AVAILABLE) throw ModelNotReady()
        var bitmap: Bitmap? = null
        var file: File? = null
        var success = false
        try {
            file = aiFile(context, "png")
            progress(0f, AppText.get(R.string.ai_loading_model))
            kotlinx.coroutines.coroutineScope {
                var shown = -1
                ensureModel(context) { fraction ->
                    val percent = (fraction * 100).toInt()
                    if (percent != shown) { shown = percent; launch { progress(0.2f * fraction, AppText.get(R.string.ai_downloading_model)) } }
                }
            }
            progress(0.2f, AppText.get(R.string.ai_loading_model))
            model(context)
            coroutineContext.ensureActive()
            bitmap = decode(context, Uri.parse(uri), limit.coerceAtLeast(1))
            val width = bitmap.width
            val height = bitmap.height
            progress(0.2f, AppText.get(R.string.ai_removing_background))
            val turn = original?.orientation ?: 0
            val upright = bitmap.turned(turn)
            val uprightAlpha = try { cutout.refine(matte(context, upright), upright.width, upright.height) } finally { if (upright !== bitmap) upright.recycle() }
            val alpha = uprightAlpha.turned(if (turn % 180 == 0) width else height, if (turn % 180 == 0) height else width, 360 - turn)
            coroutineContext.ensureActive()
            check(alpha.size == width * height) { AppText.get(R.string.ai_mask_unexpected_size) }
            val row = IntArray(width)
            bitmap.setHasAlpha(true)
            for (y in 0 until height) {
                coroutineContext.ensureActive()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) row[x] = withMatte(row[x], alpha[y * width + x])
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            progress(0.9f, AppText.get(R.string.ai_saving_transparent))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { AppText.get(R.string.ai_cannot_save_image) } }
            coroutineContext.ensureActive()
            return aiMedia(context, file, "image/png", mapOf("width" to "$width", "height" to "$height",
                "orientation" to "${original?.orientation ?: 0}")).also { success = true }
        } finally {
            bitmap?.recycle()
            if (!success) file?.delete()
        }
    }

    suspend fun <T> withFrameMatte(context: Context, block: suspend (suspend (IntArray, Int, Int) -> ByteArray) -> T): T {
        if (!AVAILABLE) throw ModelNotReady()
        val handle = model(context)
        return block { argb, width, height ->
            val rgb = ByteArray(width * height * 3)
            for (i in 0 until width * height) {
                rgb[3 * i] = (argb[i] shr 16).toByte(); rgb[3 * i + 1] = (argb[i] shr 8).toByte(); rgb[3 * i + 2] = argb[i].toByte()
            }
            synchronized(this) { nativeMatte(handle, rgb, width, height) }
        }
    }

    private fun matte(context: Context, bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                rgb[i] = (row[x] shr 16).toByte(); rgb[i + 1] = (row[x] shr 8).toByte(); rgb[i + 2] = row[x].toByte()
            }
        }
        return synchronized(this) { nativeMatte(model(context), rgb, width, height) }
    }

    @Synchronized
    fun release() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    @Synchronized
    private fun model(context: Context): Long {
        if (handle == 0L) handle = nativeLoad(modelFile(context).path)
        return handle
    }

    private fun modelFile(context: Context) = File(context.filesDir, MODEL_ASSET)

    private suspend fun ensureModel(context: Context, progress: (Float) -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = modelFile(context)
        File(context.filesDir, "$MODEL_ASSET.stamp").delete()
        if (checked && target.length() == MODEL_BYTES) return@withContext
        if (target.isFile && modelIntact(target)) { checked = true; return@withContext }
        target.delete()
        try {
            fetch(addon(MODEL_ASSET, MODEL_BYTES, MODEL_SHA256), target, { ensureActive() }, progress)
        } catch (_: java.io.IOException) {
            error(AppText.get(R.string.ai_model_download_failed))
        }
        checked = true
    }

    private fun decode(context: Context, uri: Uri, limit: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { AppText.get(R.string.ai_cannot_read_image) }
        val decoded = checkNotNull(decodePicture(resolver, uri, matteLimit(bounds.outWidth, bounds.outHeight, limit), keepOutOfMemory = true)) { AppText.get(R.string.ai_cannot_decode_image) }
        if (decoded.isMutable) return decoded
        return try { checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true)) { AppText.get(R.string.ai_cannot_decode_image) } } finally { decoded.recycle() }
    }
}
