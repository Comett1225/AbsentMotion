package app.absentmotion.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import app.absentmotion.Feature
import app.absentmotion.R
import app.absentmotion.Tier
import app.absentmotion.i18n.AppText
import app.absentmotion.media.decodePicture
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

internal const val CUTOUT_SIDE = 768
internal const val CUTOUT_LARGE_SIDE = 1024
private const val CUTOUT_LARGE_RAM = 11_000_000_000L

enum class CutoutQuality(@StringRes val title: Int, @StringRes val hint: Int) {
    Standard(R.string.cutout_standard, R.string.cutout_standard_hint),
    Best(R.string.cutout_best, R.string.cutout_best_hint),
}

internal class CutoutChoice(private val read: () -> String?, private val write: (String) -> Unit) {
    val saved: CutoutQuality? get() = CutoutQuality.entries.firstOrNull { it.name == read() }

    fun asks(offered: Boolean): Boolean = offered && saved == null

    fun quality(offered: Boolean): CutoutQuality = if (offered) saved ?: CutoutQuality.Standard else CutoutQuality.Standard

    fun choose(quality: CutoutQuality) = write(quality.name)

    companion object {
        private const val KEY = "cutoutQuality"

        fun of(context: Context): CutoutChoice = context.getSharedPreferences("settings", Context.MODE_PRIVATE).let { prefs ->
            CutoutChoice({ prefs.getString(KEY, null) }) { prefs.edit().putString(KEY, it).apply() }
        }
    }
}

internal class CutoutResult<T>(val value: T, @StringRes val note: Int?)

internal suspend fun <T> bestOrStandard(quality: CutoutQuality, install: suspend () -> Unit, best: suspend () -> T, standard: suspend () -> T): CutoutResult<T> {
    if (quality == CutoutQuality.Standard) return CutoutResult(standard(), null)
    try {
        install()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return CutoutResult(standard(), R.string.cutout_fallback_download)
    }
    try {
        return CutoutResult(best(), null)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    } catch (_: OutOfMemoryError) {
    } catch (_: LinkageError) {
    }
    return CutoutResult(standard(), R.string.cutout_fallback_failed)
}

internal fun cutoutSide(ramBytes: Long, gpu: Boolean): Int = if (gpu && ramBytes >= CUTOUT_LARGE_RAM) CUTOUT_LARGE_SIDE else CUTOUT_SIDE

internal object BestCutout {
    val offered: Boolean get() = Tier.has(Feature.BestCutout) && BackgroundRemoval.AVAILABLE

    @Volatile var sideOverride: Int? = null

    suspend fun install(context: Context, progress: (Float) -> Unit) {
        if (!AiModel.Cutout.installed(context)) AiModel.Cutout.download(context, progress)
    }

    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, progress: AiProgress): AiMedia {
        val model = AiModel.Cutout
        var bitmap: Bitmap? = null
        var file: File? = null
        var success = false
        try {
            file = aiFile(context, "png")
            progress(0f, AppText.get(R.string.ai_loading_model))
            val gpu = AiDevice.probeGpu(context, model)
            val side = sideOverride ?: cutoutSide(aiRam(context), gpu)
            coroutineContext.ensureActive()
            val picture = decode(context, Uri.parse(uri), limit.coerceAtLeast(1))
            bitmap = picture
            progress(0.2f, AppText.get(R.string.ai_removing_background))
            AiNet.open(context, model, paramName = if (side == CUTOUT_LARGE_SIDE) CUTOUT_PARAM_1024 else CUTOUT_PARAM_768).use { it.cutout(picture, side) }
            picture.setHasAlpha(true)
            picture.isPremultiplied = false
            progress(0.9f, AppText.get(R.string.ai_saving_transparent))
            file.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) { AppText.get(R.string.ai_cannot_save_image) } }
            coroutineContext.ensureActive()
            return aiMedia(context, file, "image/png", mapOf("width" to "${picture.width}", "height" to "${picture.height}",
                "orientation" to "${original?.orientation ?: 0}")).also { success = true }
        } finally {
            bitmap?.recycle()
            if (!success) file?.delete()
        }
    }

    private fun decode(context: Context, uri: Uri, limit: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { AppText.get(R.string.ai_cannot_read_image) }
        val decoded = checkNotNull(decodePicture(resolver, uri, matteLimit(bounds.outWidth, bounds.outHeight, limit), keepOutOfMemory = true)) { AppText.get(R.string.ai_cannot_decode_image) }
        if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) return decoded
        return try { checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true)) { AppText.get(R.string.ai_cannot_decode_image) } } finally { decoded.recycle() }
    }
}
