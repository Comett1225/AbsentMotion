package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import app.absentmotion.media.mirroredByExif
import app.absentmotion.media.pictureSample
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

internal class ModelNotReady : Exception(AppText.get(R.string.ai_model_not_ready_play))

internal object BackgroundRemoval {
    const val AVAILABLE = true

    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, progress: AiProgress): AiMedia {
        val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        var bitmap: Bitmap? = null
        var file: java.io.File? = null
        var success = false
        try {
            file = aiFile(context, "png")
            progress(0f, AppText.get(R.string.ai_checking_model))
            val modules = ModuleInstall.getClient(context)
            if (!modules.areModulesAvailable(client).completed().areModulesAvailable()) {
                modules.deferredInstall(client)
                throw ModelNotReady()
            }
            coroutineContext.ensureActive()
            bitmap = decode(context, Uri.parse(uri), limit.coerceAtLeast(1))
            progress(0.2f, AppText.get(R.string.ai_removing_background))
            val result = client.process(InputImage.fromBitmap(bitmap, 0)).completed()
            coroutineContext.ensureActive()
            val mask = checkNotNull(result.foregroundConfidenceMask) { AppText.get(R.string.ai_no_foreground_mask) }
            mask.rewind()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            check(mask.remaining() == pixels.size) { AppText.get(R.string.ai_mask_unexpected_size) }
            for (i in pixels.indices) {
                if (i % bitmap.width == 0) coroutineContext.ensureActive()
                val confidence = mask.get().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                val alpha = ((pixels[i] ushr 24) * confidence).roundToInt()
                pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
            }
            bitmap.setHasAlpha(true)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            progress(0.9f, AppText.get(R.string.ai_saving_transparent))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { AppText.get(R.string.ai_cannot_save_image) } }
            coroutineContext.ensureActive()
            return aiMedia(context, file, "image/png", mapOf("width" to "${bitmap.width}", "height" to "${bitmap.height}",
                "orientation" to "${original?.orientation ?: 0}")).also { success = true }
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                ModuleInstall.getClient(context).deferredInstall(client)
                throw ModelNotReady()
            }
            throw e
        } finally {
            client.close(); bitmap?.recycle()
            if (!success) file?.delete()
        }
    }

    suspend fun <T> withFrameMatte(context: Context, block: suspend (suspend (IntArray, Int, Int) -> ByteArray) -> T): T {
        val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            val modules = ModuleInstall.getClient(context)
            if (!modules.areModulesAvailable(client).completed().areModulesAvailable()) {
                modules.deferredInstall(client)
                throw ModelNotReady()
            }
            return block { argb, width, height ->
                val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
                try {
                    val mask = checkNotNull(client.process(InputImage.fromBitmap(bitmap, 0)).completed().foregroundConfidenceMask) { AppText.get(R.string.ai_no_foreground_mask) }
                    mask.rewind()
                    check(mask.remaining() == width * height) { AppText.get(R.string.ai_mask_unexpected_size) }
                    ByteArray(width * height) { (mask.get().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f } * 255 + 0.5f).toInt().toByte() }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                ModuleInstall.getClient(context).deferredInstall(client)
                throw ModelNotReady()
            }
            throw e
        } finally {
            client.close()
        }
    }

    private fun decode(context: Context, uri: Uri, limit: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { AppText.get(R.string.ai_cannot_read_image) }
        val side = matteLimit(bounds.outWidth, bounds.outHeight, limit)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true; inScaled = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inSampleSize = pictureSample(maxOf(bounds.outWidth, bounds.outHeight), side)
        }
        val raw = checkNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }) { AppText.get(R.string.ai_cannot_decode_image) }
        if (maxOf(raw.width, raw.height) <= side) return raw.mirroredByExif(resolver, uri)
        val scale = side.toFloat() / maxOf(raw.width, raw.height)
        val scaled = try {
            Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1), true)
        } finally { raw.recycle() }
        return scaled.mirroredByExif(resolver, uri)
    }
}

private suspend fun <T> Task<T>.completed(): T = suspendCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.resumeWithException(kotlinx.coroutines.CancellationException()) }
}
