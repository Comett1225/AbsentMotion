package app.absentmotion.ai

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.absentmotion.media.decodePicture
import app.absentmotion.model.MediaDeclaration
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

internal class ModelNotReady : Exception("Background removal is not available in this build.")

internal const val MODEL_ASSET = "absent_bg.abg"
internal const val MAX_MATTE_PIXELS = 16_000_000L

internal fun matteLimit(width: Int, height: Int, limit: Int, maxPixels: Long = MAX_MATTE_PIXELS): Int {
    val side = maxOf(width, height)
    val fit = (side * sqrt(maxPixels.toDouble() / (width.toLong() * height))).toLong()
    return minOf(limit.toLong(), fit).toInt().coerceAtLeast(1)
}

internal fun needsCopy(target: File, stamp: File, version: String): Boolean =
    !target.isFile || !stamp.isFile || stamp.readText() != version

internal fun withMatte(argb: Int, matte: Byte): Int =
    (argb and 0x00FFFFFF) or ((((argb ushr 24) * (matte.toInt() and 0xFF) + 127) / 255) shl 24)

internal object BackgroundRemoval {
    val AVAILABLE: Boolean by lazy {
        runCatching { System.loadLibrary("amengine") }.isSuccess &&
            BackgroundRemoval::class.java.classLoader?.getResource("assets/$MODEL_ASSET") != null
    }
    private var handle = 0L

    private external fun nativeLoad(path: String): Long
    private external fun nativeMatte(handle: Long, rgb: ByteArray, width: Int, height: Int): ByteArray
    private external fun nativeRelease(handle: Long)

    suspend fun run(context: Context, uri: String, original: MediaDeclaration?, limit: Int, progress: AiProgress): AiMedia {
        if (!AVAILABLE) throw ModelNotReady()
        var bitmap: Bitmap? = null
        var file: File? = null
        var success = false
        try {
            file = aiFile(context, "png")
            progress(0f, "Loading background removal model…")
            model(context)
            coroutineContext.ensureActive()
            bitmap = decode(context, Uri.parse(uri), limit.coerceAtLeast(1))
            val width = bitmap.width
            val height = bitmap.height
            progress(0.2f, "Removing background…")
            val alpha = matte(context, bitmap)
            coroutineContext.ensureActive()
            check(alpha.size == width * height) { "The foreground mask has an unexpected size" }
            val row = IntArray(width)
            bitmap.setHasAlpha(true)
            for (y in 0 until height) {
                coroutineContext.ensureActive()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) row[x] = withMatte(row[x], alpha[y * width + x])
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            progress(0.9f, "Saving transparent image…")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot save image" } }
            coroutineContext.ensureActive()
            return aiMedia(context, file, "image/png", mapOf("width" to "$width", "height" to "$height",
                "orientation" to "${original?.orientation ?: 0}")).also { success = true }
        } finally {
            bitmap?.recycle()
            if (!success) file?.delete()
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

    private fun modelFile(context: Context): File {
        val target = File(context.filesDir, MODEL_ASSET)
        val stamp = File(context.filesDir, "$MODEL_ASSET.stamp")
        val version = "${context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime}"
        if (needsCopy(target, stamp, version)) {
            val temp = File(context.filesDir, "$MODEL_ASSET.tmp")
            context.assets.open(MODEL_ASSET).use { input -> temp.outputStream().use { input.copyTo(it) } }
            check(temp.renameTo(target)) { "Cannot install the background removal model" }
            stamp.writeText(version)
        }
        return target
    }

    private fun decode(context: Context, uri: Uri, limit: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read this image" }
        val decoded = checkNotNull(decodePicture(resolver, uri, matteLimit(bounds.outWidth, bounds.outHeight, limit))) { "Cannot decode this image" }
        if (decoded.isMutable) return decoded
        return try { checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true)) { "Cannot decode this image" } } finally { decoded.recycle() }
    }
}
