package app.absentmotion.media

import java.nio.ByteBuffer
import kotlin.math.ceil

internal class Yuv(
    val y: ByteBuffer, val yRow: Int, val yPixel: Int,
    val u: ByteBuffer, val v: ByteBuffer, val uvRow: Int, val uvPixel: Int,
    val left: Int, val top: Int, val width: Int, val height: Int,
)

internal class LumaFrame(
    val width: Int, val height: Int, val pixels: ByteArray,
    val factor: Int = 1, val sourceWidth: Int = width * factor, val sourceHeight: Int = height * factor,
)

internal const val TRACK_SIDE = 960

internal fun trackFactor(width: Int, height: Int): Int = maxOf(1, ceil(maxOf(width, height) / TRACK_SIDE.toDouble()).toInt())

internal fun Yuv.luma(factor: Int): LumaFrame {
    val w = maxOf(1, width / factor)
    val h = maxOf(1, height / factor)
    val out = ByteArray(w * h)
    val area = factor * factor
    val row = IntArray(w)
    for (j in 0 until h) {
        row.fill(0)
        for (dy in 0 until factor) {
            val sy = minOf(height - 1, j * factor + dy) + top
            val base = sy * yRow + left * yPixel
            for (i in 0 until w) {
                var sum = 0
                var at = base + i * factor * yPixel
                for (dx in 0 until factor) {
                    sum += y.get(at).toInt() and 0xFF
                    at += yPixel
                }
                row[i] += sum
            }
        }
        for (i in 0 until w) out[j * w + i] = ((row[i] + area / 2) / area).toByte()
    }
    return LumaFrame(w, h, out, factor, width, height)
}
