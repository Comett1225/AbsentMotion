package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageEncode {
    fun flatten(rgba: ByteArray, background: Int, into: ByteArray = ByteArray(rgba.size)): ByteArray {
        val bg = intArrayOf((background shr 16) and 255, (background shr 8) and 255, background and 255)
        var i = 0
        while (i + 3 < rgba.size) {
            val a = rgba[i + 3].toInt() and 255
            for (c in 0 until 3) {
                val v = rgba[i + c].toInt() and 255
                into[i + c] = if (a == 255) v.toByte() else ((v * a + bg[c] * (255 - a) + 127) / 255).toByte()
            }
            into[i + 3] = -1
            i += 4
        }
        return into
    }

    fun bmp(rgba: ByteArray, width: Int, height: Int, out: OutputStream) {
        val row = (width * 3 + 3) and 3.inv()
        val size = 54L + row.toLong() * height
        exportCheck(size <= Int.MAX_VALUE) { txt(R.string.export_error_too_large, "${width}x$height") }
        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size.toInt()).putInt(0).putInt(54)
        header.putInt(40).putInt(width).putInt(height).putShort(1).putShort(24).putInt(0).putInt(row * height)
        header.putInt(2835).putInt(2835).putInt(0).putInt(0)
        out.write(header.array())
        val line = ByteArray(row)
        for (y in height - 1 downTo 0) {
            var i = y * width * 4
            for (x in 0 until width) {
                line[3 * x] = rgba[i + 2]; line[3 * x + 1] = rgba[i + 1]; line[3 * x + 2] = rgba[i]
                i += 4
            }
            out.write(line)
        }
    }
}
