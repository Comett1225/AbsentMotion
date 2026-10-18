package app.absentmotion.export

import java.io.OutputStream

class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int) {
    private val indices = ByteArray(width * height)
    private val keyToIndex = IntArray(KEYS)
    private var errNow = IntArray((width + 2) * 3)
    private var errNext = IntArray((width + 2) * 3)
    private val want = IntArray(3)
    private var timeCs = 0
    private var frames = 0

    init {
        out.write("GIF89a".toByteArray())
        short(width); short(height)
        out.write(0x70); out.write(0); out.write(0)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
    }

    fun frame(rgba: ByteArray, endTimeMs: Long) {
        val n = width * height
        val count = IntArray(KEYS)
        val sum = Array(3) { IntArray(KEYS) }
        for (i in 0 until n) {
            val a = rgba[i * 4 + 3].toInt() and 255
            val r = (rgba[i * 4].toInt() and 255) * a / 255
            val g = (rgba[i * 4 + 1].toInt() and 255) * a / 255
            val b = (rgba[i * 4 + 2].toInt() and 255) * a / 255
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            count[key]++; sum[0][key] += r; sum[1][key] += g; sum[2][key] += b
        }
        val palette = medianCut(count, sum)
        keyToIndex.fill(-1)
        errNow.fill(0); errNext.fill(0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val a = rgba[i * 4 + 3].toInt() and 255
                val e = (x + 1) * 3
                for (ch in 0 until 3) want[ch] = ((rgba[i * 4 + ch].toInt() and 255) * a / 255 + errNow[e + ch] / 16).coerceIn(0, 255)
                val key = ((want[0] shr 3) shl 10) or ((want[1] shr 3) shl 5) or (want[2] shr 3)
                if (keyToIndex[key] < 0) keyToIndex[key] = nearest(palette, count, sum, key)
                val index = keyToIndex[key]
                indices[i] = index.toByte()
                for (ch in 0 until 3) {
                    val err = want[ch] - (palette[index] shr (16 - 8 * ch) and 255)
                    errNow[e + 3 + ch] += err * 7
                    errNext[e - 3 + ch] += err * 3
                    errNext[e + ch] += err * 5
                    errNext[e + 3 + ch] += err
                }
            }
            errNow.fill(0); errNow = errNext.also { errNext = errNow }
        }

        val endCs = ((endTimeMs + 5) / 10).toInt()
        val delay = (endCs - timeCs).coerceAtLeast(2)
        timeCs += delay
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, (delay and 255).toByte(), (delay shr 8).toByte(), 0, 0))
        out.write(0x2C); short(0); short(0); short(width); short(height)
        out.write(0x87)
        for (c in palette) { out.write(c shr 16); out.write(c shr 8); out.write(c) }
        Lzw(out).encode(indices)
        frames++
    }

    fun finish() = out.write(0x3B)

    private fun short(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }

    private fun nearest(palette: IntArray, count: IntArray, sum: Array<IntArray>, key: Int): Int {
        val r: Int; val g: Int; val b: Int
        if (count[key] > 0) { r = sum[0][key] / count[key]; g = sum[1][key] / count[key]; b = sum[2][key] / count[key] }
        else { r = (key shr 10 and 31) * 8 + 4; g = (key shr 5 and 31) * 8 + 4; b = (key and 31) * 8 + 4 }
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun medianCut(count: IntArray, sum: Array<IntArray>): IntArray {
        val used = (0 until KEYS).filter { count[it] > 0 }.toIntArray()
        val boxes = ArrayList<IntArray>().apply { add(used) }
        fun pixels(box: IntArray) = box.sumOf { count[it].toLong() }
        while (boxes.size < 256) {
            val box = boxes.filter { it.size > 1 }.maxByOrNull(::pixels) ?: break
            val spans = IntArray(3) { ch ->
                val shift = 10 - 5 * ch
                box.maxOf { it shr shift and 31 } - box.minOf { it shr shift and 31 }
            }
            val shift = 10 - 5 * spans.indices.maxByOrNull { spans[it] }!!
            val sorted = box.sortedBy { it shr shift and 31 }
            var seen = 0L
            val half = pixels(box) / 2
            var cut = sorted.indexOfFirst { seen += count[it]; seen >= half } + 1
            cut = cut.coerceIn(1, sorted.size - 1)
            boxes.remove(box)
            boxes.add(sorted.subList(0, cut).toIntArray())
            boxes.add(sorted.subList(cut, sorted.size).toIntArray())
        }
        val palette = IntArray(256)
        boxes.forEachIndexed { i, box ->
            val n = pixels(box).coerceAtLeast(1)
            fun mean(ch: Int) = (box.sumOf { sum[ch][it].toLong() } / n).toInt()
            palette[i] = (mean(0) shl 16) or (mean(1) shl 8) or mean(2)
        }
        return palette
    }

    private class Lzw(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var blockSize = 0
        private var acc = 0
        private var accBits = 0
        private var bits = 9
        private var maxCode = 511
        private var free = 258
        private var clearing = false

        fun encode(data: ByteArray) {
            out.write(8)
            val keys = IntArray(HASH) { -1 }
            val codes = IntArray(HASH)
            put(256)
            var prefix = data[0].toInt() and 255
            for (i in 1 until data.size) {
                val c = data[i].toInt() and 255
                val key = (prefix shl 8) or c
                var h = (key * -1640531535) ushr 18
                while (keys[h] != -1 && keys[h] != key) h = (h + 1) and (HASH - 1)
                if (keys[h] == key) { prefix = codes[h]; continue }
                put(prefix)
                prefix = c
                if (free < 4096) {
                    keys[h] = key; codes[h] = free++
                } else {
                    keys.fill(-1); free = 258; clearing = true
                    put(256)
                }
            }
            put(prefix)
            put(257)
            if (accBits > 0) byte(acc and 255)
            if (blockSize > 0) flush()
            out.write(0)
        }

        private fun put(code: Int) {
            acc = acc or (code shl accBits)
            accBits += bits
            while (accBits >= 8) { byte(acc and 255); acc = acc ushr 8; accBits -= 8 }
            if (free > maxCode || clearing) {
                if (clearing) { bits = 9; maxCode = 511; clearing = false }
                else { bits++; maxCode = if (bits == 12) 4096 else (1 shl bits) - 1 }
            }
        }

        private fun byte(v: Int) { block[blockSize++] = v.toByte(); if (blockSize == 255) flush() }
        private fun flush() { out.write(blockSize); out.write(block, 0, blockSize); blockSize = 0 }
    }

    private companion object {
        const val KEYS = 32768
        const val HASH = 16384
    }
}
