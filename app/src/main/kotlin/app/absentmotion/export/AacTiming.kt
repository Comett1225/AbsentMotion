package app.absentmotion.export

internal fun aacDelayFrames(reported: Int?): Int = reported?.takeIf { it >= 0 } ?: 2048
internal fun aacInputFrames(contentUs: Long, delayFrames: Int): Long =
    contentUs * ExportAudio.SAMPLE_RATE / 1_000_000 + delayFrames.coerceAtLeast(0)

internal class AacTimestamps(var delayFrames: Int = aacDelayFrames(null)) {
    private var alreadyAligned: Boolean? = null

    fun map(ptsUs: Long): Long? {
        if (alreadyAligned == null) alreadyAligned = ptsUs < 0
        val shifted = ptsUs - if (alreadyAligned == true) 0 else delayFrames * 1_000_000L / ExportAudio.SAMPLE_RATE
        return if (shifted < 0 && shifted >= -1_000_000.0 / ExportAudio.SAMPLE_RATE) 0 else shifted
    }
}
