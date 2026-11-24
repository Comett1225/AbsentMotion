package app.absentmotion.library

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader

internal const val ProjectQrMaxSide = 2000
private const val ProjectQrSmallSide = 320
private val qrHints = mapOf(DecodeHintType.TRY_HARDER to true)

internal fun projectQrLinks(pixels: IntArray, width: Int, height: Int): List<String> {
    if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong()) return emptyList()
    val longest = maxOf(width, height)
    val scanWidth = if (longest <= ProjectQrMaxSide) width else scaledSide(width, longest)
    val scanHeight = if (longest <= ProjectQrMaxSide) height else scaledSide(height, longest)
    val scan = if (longest <= ProjectQrMaxSide) pixels else scalePixels(pixels, width, height, scanWidth, scanHeight)
    val texts = linkedSetOf<String>()
    fun scanImage(argb: IntArray, w: Int, h: Int) {
        val opaque = if (argb.any { it ushr 24 != 255 }) IntArray(argb.size) { onWhite(argb[it]) } else argb
        val source = RGBLuminanceSource(w, h, opaque)
        for (polarity in listOf(source, source.invert())) {
            for (result in readQrCodes(polarity)) {
                if (projectFileLink(result.text) != null) texts.add(result.text)
            }
        }
    }
    scanImage(scan, scanWidth, scanHeight)
    if (longest <= ProjectQrSmallSide) {
        scanImage(scalePixels(scan, scanWidth, scanHeight, scanWidth * 2, scanHeight * 2), scanWidth * 2, scanHeight * 2)
    }
    return texts.toList()
}

private fun readQrCodes(source: LuminanceSource): Array<Result> {
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val multiple = try {
        QRCodeMultiReader().decodeMultiple(bitmap, qrHints)
    } catch (_: ReaderException) {
        emptyArray()
    }
    if (multiple.isNotEmpty()) return multiple
    return try {
        GenericMultipleBarcodeReader(QRCodeReader()).decodeMultiple(bitmap, qrHints)
    } catch (_: ReaderException) {
        emptyArray()
    }
}

private fun scaledSide(side: Int, longest: Int): Int =
    (side.toLong() * ProjectQrMaxSide / longest).toInt().coerceAtLeast(1)

private fun scalePixels(pixels: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): IntArray =
    IntArray(targetWidth * targetHeight) { index ->
        val x = ((index % targetWidth * 2L + 1) * width / (2L * targetWidth)).toInt()
        val y = ((index / targetWidth * 2L + 1) * height / (2L * targetHeight)).toInt()
        pixels[y * width + x]
    }

private fun onWhite(argb: Int): Int {
    val alpha = argb ushr 24
    fun channel(shift: Int) = 255 - (255 - (argb ushr shift and 255)) * alpha / 255
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
