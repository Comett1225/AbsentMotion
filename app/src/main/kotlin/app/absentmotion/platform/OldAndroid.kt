package app.absentmotion.platform

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream

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

internal fun MediaFormat.intOr(key: String, default: Int): Int = when {
    Build.VERSION.SDK_INT >= 29 -> getInteger(key, default)
    containsKey(key) -> getInteger(key)
    else -> default
}

internal fun MediaFormat.numberOf(key: String): Number? = when {
    Build.VERSION.SDK_INT >= 29 -> getNumber(key)
    !containsKey(key) -> null
    else -> runCatching<Number> { getInteger(key) }.recoverCatching { getFloat(key) }.recoverCatching { getLong(key) }.getOrNull()
}

internal val MediaCodecInfo.hardware: Boolean
    get() = if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")

internal val MediaCodecInfo.alias: Boolean
    get() = Build.VERSION.SDK_INT >= 29 && isAlias

internal val PackageInfo.versionCodeLong: Long get() = PackageInfoCompat.getLongVersionCode(this)

internal fun MediaMetadataRetriever.scaledFrame(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? =
    if (Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(timeUs, option, width, height)
    else getFrameAtTime(timeUs, option)?.let { fitBitmap(it, width, height) }

internal fun fitBitmap(full: Bitmap, width: Int, height: Int): Bitmap {
    val scale = minOf(width.toFloat() / full.width, height.toFloat() / full.height)
    if (scale >= 1f) return full
    val fitted = Bitmap.createScaledBitmap(full, maxOf(1, Math.round(full.width * scale)), maxOf(1, Math.round(full.height * scale)), true)
    if (fitted !== full) full.recycle()
    return fitted
}

internal fun sampledBitmap(maxSide: Int, open: () -> InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxSide) }
    return open()?.use { BitmapFactory.decodeStream(it, null, options) }
}
