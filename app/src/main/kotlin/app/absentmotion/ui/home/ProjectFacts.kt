package app.absentmotion.ui.home

internal fun aspectName(w: Int, h: Int): String? =
    listOf(16 to 9, 9 to 16, 4 to 5, 1 to 1, 4 to 3).firstOrNull { (a, b) -> kotlin.math.abs(w.toLong() * b - h.toLong() * a) <= maxOf(a, b) }?.let { (a, b) -> "$a:$b" }
