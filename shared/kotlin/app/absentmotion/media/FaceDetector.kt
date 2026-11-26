package app.absentmotion.media

import android.content.Context
import app.absentmotion.ai.uprightToFrame
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.sqrt

internal class FaceSpot(val x: Float, val y: Float, val size: Float)

internal class RgbFrame(val width: Int, val height: Int, val pixels: ByteArray)

internal class FaceDetector(model: ByteArray) : Closeable {
    private var handle = nativeCreate(model)

    @Synchronized fun detect(frame: RgbFrame): List<FaceSpot> {
        check(handle != 0L) { "The face detector is closed" }
        val found = nativeDetect(handle, frame.pixels, frame.width, frame.height, THRESHOLD)
        return List(found.size / 5) { i -> FaceSpot(found[5 * i] + found[5 * i + 2] / 2, found[5 * i + 1] + found[5 * i + 3] / 2, sqrt(found[5 * i + 2] * found[5 * i + 3])) }
    }

    fun faces(yuv: Yuv, turn: Int, mediaWidth: Int, mediaHeight: Int): List<FaceSpot> {
        val factor = faceFactor(yuv.width, yuv.height)
        val small = yuv.rgb(factor)
        val sx = mediaWidth.toFloat() / yuv.width * factor
        val sy = mediaHeight.toFloat() / yuv.height * factor
        return detect(small.turned(turn)).map { f ->
            val (x, y) = uprightToFrame(f.x, f.y, small.width.toFloat(), small.height.toFloat(), turn)
            FaceSpot(x * sx, y * sy, f.size * sqrt(sx * sy))
        }
    }

    @Synchronized override fun close() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    companion object {
        const val MODEL = "faces/face_detection_yunet_2023mar.onnx"
        const val SIDE = 384
        const val THRESHOLD = 0.6f

        init { runCatching { app.absentmotion.platform.loadNoiseLibrary() } }

        fun open(context: Context) = FaceDetector(context.assets.open(MODEL).use { it.readBytes() })

        @JvmStatic private external fun nativeCreate(model: ByteArray): Long
        @JvmStatic private external fun nativeDetect(handle: Long, rgb: ByteArray, width: Int, height: Int, threshold: Float): FloatArray
        @JvmStatic private external fun nativeRelease(handle: Long)
    }
}

internal fun faceFactor(width: Int, height: Int): Int = maxOf(1, ceil(maxOf(width, height) / FaceDetector.SIDE.toDouble()).toInt())

internal fun Yuv.rgb(factor: Int): RgbFrame {
    val w = maxOf(1, width / factor)
    val h = maxOf(1, height / factor)
    val out = ByteArray(w * h * 3)
    val area = factor * factor
    for (j in 0 until h) {
        for (i in 0 until w) {
            var sum = 0
            for (dy in 0 until factor) {
                val base = (minOf(height - 1, j * factor + dy) + top) * yRow + left * yPixel
                for (dx in 0 until factor) sum += y.get(base + minOf(width - 1, i * factor + dx) * yPixel).toInt() and 0xFF
            }
            val cx = (minOf(width - 1, i * factor + factor / 2) + left) / 2
            val cy = (minOf(height - 1, j * factor + factor / 2) + top) / 2
            val c = 1.164f * (sum.toFloat() / area - 16f)
            val d = (u.get(cy * uvRow + cx * uvPixel).toInt() and 0xFF) - 128f
            val e = (v.get(cy * uvRow + cx * uvPixel).toInt() and 0xFF) - 128f
            val at = (j * w + i) * 3
            out[at] = (c + 1.596f * e).toInt().coerceIn(0, 255).toByte()
            out[at + 1] = (c - 0.392f * d - 0.813f * e).toInt().coerceIn(0, 255).toByte()
            out[at + 2] = (c + 2.017f * d).toInt().coerceIn(0, 255).toByte()
        }
    }
    return RgbFrame(w, h, out)
}

internal fun RgbFrame.turned(turn: Int): RgbFrame {
    if (turn != 90 && turn != 180 && turn != 270) return this
    val w = if (turn == 180) width else height
    val h = if (turn == 180) height else width
    val out = ByteArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val (ux, uy) = when (turn) {
                90 -> height - 1 - y to x
                180 -> width - 1 - x to height - 1 - y
                else -> y to width - 1 - x
            }
            System.arraycopy(pixels, (y * width + x) * 3, out, (uy * w + ux) * 3, 3)
        }
    }
    return RgbFrame(w, h, out)
}
