package app.absentmotion.media

import android.media.MediaFormat

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

internal fun isHdrTransfer(transfer: Int): Boolean = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG

internal fun MediaFormat.requestSdr(): Boolean = isHdrTransfer(getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)).also { hdr ->
    if (hdr) setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
}
