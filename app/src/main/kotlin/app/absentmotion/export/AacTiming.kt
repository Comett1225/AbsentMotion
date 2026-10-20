package app.absentmotion.export

internal fun aacDelayFrames(reported: Int?): Int = reported?.takeIf { it >= 0 } ?: 2048
internal fun aacInputFrames(contentUs: Long, delayFrames: Int, rate: Int = ExportAudio.SAMPLE_RATE): Long =
    contentUs * rate / 1_000_000 + delayFrames.coerceAtLeast(0)

internal class AacTimestamps(var delayFrames: Int = aacDelayFrames(null), private val rate: Int = ExportAudio.SAMPLE_RATE) {
    private var alreadyAligned: Boolean? = null

    fun map(ptsUs: Long): Long? {
        if (alreadyAligned == null) alreadyAligned = ptsUs < 0
        val shifted = ptsUs - if (alreadyAligned == true) 0 else delayFrames * 1_000_000L / rate
        return if (shifted < 0 && shifted >= -1_000_000.0 / rate) 0 else shifted
    }
}
