package app.absentmotion.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageEncode {
    fun flatten(rgba: ByteArray, background: Int): ByteArray {
        val bg = intArrayOf((background shr 16) and 255, (background shr 8) and 255, background and 255)
        val out = ByteArray(rgba.size)
        var i = 0
        while (i + 3 < rgba.size) {
            val a = rgba[i + 3].toInt() and 255
            for (c in 0 until 3) {
                val v = rgba[i + c].toInt() and 255
                out[i + c] = if (a == 255) v.toByte() else ((v * a + bg[c] * (255 - a) + 127) / 255).toByte()
            }
            out[i + 3] = -1
            i += 4
        }
        return out
    }

    fun bmp(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val row = (width * 3 + 3) and 3.inv()
        val size = 54 + row * height
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        b.putInt(40).putInt(width).putInt(height).putShort(1).putShort(24).putInt(0).putInt(row * height)
        b.putInt(2835).putInt(2835).putInt(0).putInt(0)
        val pad = ByteArray(row - width * 3)
        for (y in height - 1 downTo 0) {
            var i = y * width * 4
            for (x in 0 until width) {
                b.put(rgba[i + 2]).put(rgba[i + 1]).put(rgba[i])
                i += 4
            }
            b.put(pad)
        }
        return b.array()
    }
}
