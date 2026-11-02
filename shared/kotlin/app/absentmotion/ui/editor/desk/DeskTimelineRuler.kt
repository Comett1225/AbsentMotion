package app.absentmotion.ui.editor

internal object DeskRuler {
    class Steps(val major: Int, val minor: Int)

    private const val LABEL_GAP_DP = 80f
    private const val MINOR_GAP_DP = 8f
    private val SECONDS = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 18000)

    fun steps(fps: Int, dpPerFrame: Float): Steps {
        val candidates = intArrayOf(1, 2, 5, 10).filter { it < fps } + SECONDS.map { it * fps }
        val major = candidates.firstOrNull { it * dpPerFrame >= LABEL_GAP_DP } ?: candidates.last()
        val minor = intArrayOf(10, 5, 4, 2, 1).firstOrNull { major % it == 0 && major / it * dpPerFrame >= MINOR_GAP_DP }?.let { major / it } ?: major
        return Steps(major, minor)
    }

    fun label(frame: Int, fps: Int, major: Int): String {
        if (major % fps != 0) return TimelineMath.timecode(frame, fps)
        val seconds = frame / fps
        val minutes = seconds / 60
        val tail = (seconds % 60).toString().padStart(2, '0')
        return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail" else "$minutes:$tail"
    }
}
