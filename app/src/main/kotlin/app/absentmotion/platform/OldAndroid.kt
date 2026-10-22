package app.absentmotion.platform

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
