package app.absentmotion.effects

import kotlin.math.pow
import kotlin.math.roundToInt

data class PalettePreset(val name: String, val colors: List<Int>)

object Palettes {
    const val MAX_COLORS = 512
    private const val MAX_PICTURE_COLORS = 256
    private const val MAX_TEXT_BYTES = 1 shl 21
    private const val SAMPLE = 1 shl 16

    private fun hex(text: String) = text.split(' ').map { it.toInt(16) }

    private fun cube(levels: List<Int>) = levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> (r shl 16) or (g shl 8) or b } } }

    val presets: List<PalettePreset> = listOf(
        PalettePreset("1-bit Gray", hex("000000 ffffff")),
        PalettePreset("2-bit Gray", hex("000000 555555 aaaaaa ffffff")),
        PalettePreset("4-bit Gray", List(16) { it * 0x111111 }),
        PalettePreset("3-bit RGB", cube(listOf(0x00, 0xff))),
        PalettePreset("6-bit RGB", cube(listOf(0x00, 0x55, 0xaa, 0xff))),
        PalettePreset("9-bit RGB", cube(listOf(0x00, 0x24, 0x49, 0x6d, 0x92, 0xb6, 0xdb, 0xff))),
        PalettePreset("Web Safe", cube(listOf(0x00, 0x33, 0x66, 0x99, 0xcc, 0xff))),
        PalettePreset("PICO-8", hex("000000 1d2b53 7e2553 008751 ab5236 5f574f c2c3c7 fff1e8 ff004d ffa300 ffec27 00e436 29adff 83769c ff77a8 ffccaa")),
        PalettePreset("Game Boy", hex("0f380f 306230 8bac0f 9bbc0f")),
        PalettePreset("CGA", hex("000000 0000aa 00aa00 00aaaa aa0000 aa00aa aa5500 aaaaaa 555555 5555ff 55ff55 55ffff ff5555 ff55ff ffff55 ffffff")),
        PalettePreset("CGA 4-Color", hex("000000 55ffff ff55ff ffffff")),
        PalettePreset("Amstrad CPC", cube(listOf(0x00, 0x80, 0xff))),
        PalettePreset("ZX Spectrum", hex("000000 0000d7 d70000 d700d7 00d700 00d7d7 d7d700 d7d7d7 0000ff ff0000 ff00ff 00ff00 00ffff ffff00 ffffff")),
        PalettePreset("Commodore 64", hex("000000 ffffff 68372b 70a4b2 6f3d86 588d43 352879 b8c76f 6f4f25 433900 9a6759 444444 6c6c6c 9ad284 6c5eb5 959595")),
        PalettePreset("NES", hex("7c7c7c 0000fc 0000bc 4428bc 940084 a80020 a81000 881400 503000 007800 006800 005800 004058 000000 bcbcbc 0078f8 0058f8 6844fc d800cc e40058 f83800 e45c10 ac7c00 00b800 00a800 00a844 008888 f8f8f8 3cbcfc 6888fc 9878f8 f878f8 f85898 f87858 fca044 f8b800 b8f818 58d854 58f898 00e8d8 787878 fcfcfc a4e4fc b8b8f8 d8b8f8 f8b8f8 f8a4c0 f0d0b0 fce0a8 f8d878 d8f878 b8f8b8 b8f8d8 00fcfc f8d8f8")),
        PalettePreset("MSX", hex("000000 3eb849 74d07d 5955e0 8076f1 b95e51 65dbef db6559 ff897d ccc35e ded087 3aa241 b766b5 cccccc ffffff")),
        PalettePreset("Apple II", hex("000000 6c2940 403578 d93cf0 135740 808080 2697f0 bfb4f8 404b07 d9680f eca8bf 26c30f bfca87 93d6bf ffffff")),
    )

    fun read(text: String): List<Int> = text.split(' ', '\t', '\r', '\n', ',').mapNotNull(::hexColor)

    fun format(colors: Iterable<Int>): String =
        colors.map { it and 0xFFFFFF }.distinct().take(MAX_COLORS).joinToString(" ") { it.toString(16).padStart(6, '0') }

    fun presetOf(text: String): PalettePreset? = read(text).let { colors -> presets.firstOrNull { it.colors == colors } }

    fun fromFile(name: String, bytes: ByteArray): List<Int>? = try {
        val found = when {
            bytes.tag(0, "ASEF") -> swatchExchange(bytes)
            bytes.tag(0, "RIFF") && bytes.tag(8, "PAL ") -> riffPalette(bytes)
            name.endsWith(".act", ignoreCase = true) -> colorTable(bytes)
            else -> textPalette(bytes)
        }
        found?.map { it and 0xFFFFFF }?.distinct()?.take(MAX_COLORS)?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    fun fromPixels(argb: IntArray): List<Int> {
        val seen = LinkedHashSet<Int>()
        var last = -1
        for (p in argb) {
            if (p ushr 24 < 128) continue
            val rgb = p and 0xFFFFFF
            if (rgb == last) continue
            last = rgb
            seen.add(rgb)
            if (seen.size > MAX_PICTURE_COLORS) return reduce(argb)
        }
        return seen.toList()
    }

    private fun hexColor(token: String): Int? {
        val digits = token.removePrefix("#")
        if ((digits.length != 6 && digits.length != 8) || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (digits.toLong(16) and 0xFFFFFF).toInt()
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 255
    private fun ByteArray.be16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.be32(at: Int) = (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)
    private fun ByteArray.le16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    private fun ByteArray.le32(at: Int) = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)
    private fun ByteArray.rgb(at: Int) = (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)
    private fun ByteArray.tag(at: Int, text: String) = at >= 0 && at + text.length <= size && text.indices.all { this[at + it].toInt() == text[it].code }

    private fun eight(v: Double) = if (v.isNaN()) 0 else (v.coerceIn(0.0, 1.0) * 255).roundToInt()

    private fun pack(r: Double, g: Double, b: Double) = (eight(r) shl 16) or (eight(g) shl 8) or eight(b)

    private fun unit(v: Double) = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 1.0)

    private fun lab(l: Double, a: Double, b: Double): Int {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inverse(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389
        val x = 0.9642 * inverse(fx)
        val y = inverse(fy)
        val z = 0.8249 * inverse(fz)
        fun encode(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return pack(
            encode(3.1338561 * x - 1.6168667 * y - 0.4906146 * z),
            encode(-0.9787684 * x + 1.9161415 * y + 0.0334540 * z),
            encode(0.0719453 * x - 0.2289914 * y + 1.4052427 * z),
        )
    }

    private fun swatchExchange(b: ByteArray): List<Int> {
        val found = ArrayList<Int>()
        var at = 12
        while (at + 6 <= b.size) {
            val end = at + 6 + b.be32(at + 2)
            if (end < at + 6 || end > b.size) break
            if (b.be16(at) == 1) swatch(b, at + 6, end)?.let(found::add)
            at = end
        }
        return found
    }

    private fun swatch(b: ByteArray, from: Int, end: Int): Int? {
        if (from + 2 > end) return null
        val model = from + 2 + 2 * b.be16(from)
        if (model + 4 > end) return null
        val kind = String(b, model, 4, Charsets.US_ASCII)
        val count = when (kind) {
            "RGB ", "LAB " -> 3
            "CMYK" -> 4
            "Gray" -> 1
            else -> return null
        }
        if (model + 4 + 4 * count > end) return null
        val v = DoubleArray(count) { Float.fromBits(b.be32(model + 4 + 4 * it)).toDouble() }
        return when (kind) {
            "RGB " -> pack(v[0], v[1], v[2])
            "CMYK" -> {
                val black = 1 - unit(v[3])
                pack((1 - unit(v[0])) * black, (1 - unit(v[1])) * black, (1 - unit(v[2])) * black)
            }
            "LAB " -> lab(v[0] * 100, v[1], v[2])
            else -> pack(v[0], v[0], v[0])
        }
    }

    private fun colorTable(b: ByteArray): List<Int>? {
        if (b.size != 768 && b.size != 772) return null
        val count = if (b.size == 772) b.be16(768).takeIf { it in 1..256 } ?: 256 else 256
        return List(count) { b.rgb(it * 3) }
    }

    private fun riffPalette(b: ByteArray): List<Int>? {
        var at = 12
        while (at >= 0 && at + 12 <= b.size) {
            val size = b.le32(at + 4)
            if (size < 0) return null
            if (b.tag(at, "data")) {
                val entries = minOf(b.le16(at + 10), (minOf(size, b.size - at - 8) - 4) / 4)
                return List(entries.coerceAtLeast(0)) { b.rgb(at + 12 + 4 * it) }
            }
            at += 8 + size + (size and 1)
        }
        return null
    }

    private fun textPalette(b: ByteArray): List<Int>? {
        if (b.size > MAX_TEXT_BYTES || (0 until minOf(b.size, 512)).any { b[it].toInt() == 0 }) return null
        val lines = String(b, Charsets.UTF_8).removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.firstOrNull() ?: return null
        return when {
            head.startsWith("GIMP Palette") -> lines.drop(1).filterNot { it.startsWith("#") }.mapNotNull(::decimalColor)
            head.startsWith("JASC-PAL") -> {
                val colors = lines.drop(3).mapNotNull(::decimalColor)
                lines.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { colors.take(it) } ?: colors
            }
            else -> hexLines(lines)
        }
    }

    private fun decimalColor(line: String): Int? {
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val v = parts.take(3).map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return (v[0] shl 16) or (v[1] shl 8) or v[2]
    }

    private fun hexLines(lines: List<String>): List<Int>? {
        val found = ArrayList<Int>()
        for (line in lines) {
            if (line.startsWith(";")) continue
            for (token in line.split(' ', '\t', ',')) if (token.isNotEmpty()) found += hexColor(token) ?: return null
        }
        return found
    }

    private class Box(val from: Int, val to: Int, val channel: Int, val spread: Int)

    private fun box(c: IntArray, from: Int, to: Int): Box {
        var spread = 0
        var channel = 0
        for (ch in 0..2) {
            val shift = 16 - 8 * ch
            var lo = 255
            var hi = 0
            for (i in from until to) {
                val v = (c[i] shr shift) and 255
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (hi - lo > spread) { spread = hi - lo; channel = ch }
        }
        return Box(from, to, channel, spread)
    }

    private fun swap(v: Int, ch: Int) = when (ch) {
        1 -> (v and 0xFF) or ((v shr 8 and 255) shl 16) or ((v shr 16 and 255) shl 8)
        2 -> ((v and 255) shl 16) or (v and 0xFF00) or (v shr 16 and 255)
        else -> v
    }

    private fun reduce(argb: IntArray): List<Int> {
        val opaque = argb.count { it ushr 24 >= 128 }
        val stride = maxOf(1, opaque / SAMPLE) or 1
        val c = IntArray(opaque / stride + 1)
        var size = 0
        var seen = 0
        for (p in argb) if (p ushr 24 >= 128 && seen++ % stride == 0) c[size++] = p and 0xFFFFFF
        if (size == 0) return emptyList()
        val boxes = arrayListOf(box(c, 0, size))
        while (boxes.size < MAX_PICTURE_COLORS) {
            val big = boxes.maxByOrNull { (it.to - it.from).toLong() * it.spread }?.takeIf { it.spread > 0 } ?: break
            boxes.remove(big)
            for (i in big.from until big.to) c[i] = swap(c[i], big.channel)
            java.util.Arrays.sort(c, big.from, big.to)
            for (i in big.from until big.to) c[i] = swap(c[i], big.channel)
            val shift = 16 - 8 * big.channel
            val mid = (big.from + big.to) / 2
            val v = (c[mid] shr shift) and 255
            var cut = mid
            while (cut > big.from && (c[cut - 1] shr shift and 255) == v) cut--
            if (cut == big.from) {
                cut = mid
                while (cut < big.to && (c[cut] shr shift and 255) == v) cut++
            }
            boxes += box(c, big.from, cut)
            boxes += box(c, cut, big.to)
        }
        return boxes.map { b ->
            var r = 0L
            var g = 0L
            var l = 0L
            for (i in b.from until b.to) { r += c[i] shr 16 and 255; g += c[i] shr 8 and 255; l += c[i] and 255 }
            val n = (b.to - b.from).toLong()
            (((r + n / 2) / n).toInt() shl 16) or (((g + n / 2) / n).toInt() shl 8) or ((l + n / 2) / n).toInt()
        }.distinct().sortedBy { (it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114 }
    }
}
