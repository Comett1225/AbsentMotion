package app.absentmotion.media

internal fun videoColorStandard(standard: Int): Int {
    if (standard < 64) return when (standard) {
        3 -> 1
        10 -> 2
        else -> standard
    }
    val matrix = if (standard >= 0x10000) (standard - 0x10000) / 256 else (standard - 64) / 7
    return when (matrix) {
        1 -> 1
        3 -> 2
        5 -> 6
        else -> 0
    }
}
