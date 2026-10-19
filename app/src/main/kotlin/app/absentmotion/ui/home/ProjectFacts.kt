package app.absentmotion.ui.home

import java.util.Locale

internal fun aspectName(w: Int, h: Int): String? =
    listOf(16 to 9, 9 to 16, 4 to 5, 1 to 1, 4 to 3).firstOrNull { (a, b) -> kotlin.math.abs(w.toLong() * b - h.toLong() * a) <= maxOf(a, b) }?.let { (a, b) -> "$a:$b" }

internal fun fileSize(bytes: Long): String {
    fun unit(v: Double, suffix: String) =
        (if (v >= 10) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v).removeSuffix(".0")) + suffix
    return when {
        bytes < 1024 -> "${bytes}b"
        bytes < 1024L * 1024 - 512 -> unit(bytes / 1024.0, "KB")
        else -> unit(bytes / (1024.0 * 1024.0), "MB")
    }
}

