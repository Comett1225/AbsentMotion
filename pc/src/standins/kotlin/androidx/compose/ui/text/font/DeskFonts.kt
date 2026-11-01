package androidx.compose.ui.text.font

import androidx.compose.ui.text.platform.Font as FileFont

private val families = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

fun FontFamily(typeface: android.graphics.Typeface): FontFamily {
    val file = typeface.file ?: return when (typeface.family) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
    val key = "${file.path}:${file.lastModified()}:${typeface.weight}:${typeface.italic}"
    return families.getOrPut(key) {
        runCatching { FontFamily(FileFont(file, FontWeight(typeface.weight.coerceIn(1, 1000)), if (typeface.italic) FontStyle.Italic else FontStyle.Normal)) }.getOrDefault(FontFamily.SansSerif)
    }
}
