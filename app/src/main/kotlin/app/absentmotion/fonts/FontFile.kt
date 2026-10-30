package app.absentmotion.fonts

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

enum class FontProblem { NotFont, Unsupported, TooLarge, AlreadyImported, Corrupted, Unreadable }

class FontImportError(val problem: FontProblem) : Exception(problem.name)

enum class FontKind(val extension: String) { Ttf("ttf"), Otf("otf"), Ttc("ttc"), Woff(""), Woff2("") }

const val MAX_FONT_BYTES = 32 * 1024 * 1024

private val RequiredTables = listOf("cmap", "head", "maxp")
private val OutlineTables = listOf("glyf", "CFF ", "CFF2", "CBDT", "EBDT", "sbix", "SVG ")

fun fontKind(bytes: ByteArray): FontKind? = when (tag(bytes, 0)) {
    "\u0000\u0001\u0000\u0000", "true" -> FontKind.Ttf
    "OTTO" -> FontKind.Otf
    "ttcf" -> FontKind.Ttc
    "wOFF" -> FontKind.Woff
    "wOF2" -> FontKind.Woff2
    else -> null
}

fun checkedFont(bytes: ByteArray): Pair<ByteArray, FontKind> {
    if (bytes.size > MAX_FONT_BYTES) throw FontImportError(FontProblem.TooLarge)
    val kind = fontKind(bytes) ?: throw FontImportError(if (tag(bytes, 0) == "typ1" || bytes.startsWith("%!PS-AdobeFont")) FontProblem.Unsupported else FontProblem.NotFont)
    return when (kind) {
        FontKind.Woff2 -> throw FontImportError(FontProblem.Unsupported)
        FontKind.Woff -> {
            val sfnt = try { woffToSfnt(bytes) } catch (e: FontImportError) { throw e } catch (_: Exception) { throw FontImportError(FontProblem.Corrupted) }
            checkedFont(sfnt)
        }
        FontKind.Ttc -> {
            val count = u32(bytes, 8)
            if (bytes.size < 12 || count < 1 || count > 1000 || 12 + count * 4 > bytes.size) throw FontImportError(FontProblem.Corrupted)
            for (i in 0 until count.toInt()) requireSfnt(bytes, u32(bytes, 12 + i * 4).toInt())
            bytes to kind
        }
        else -> { requireSfnt(bytes, 0); bytes to kind }
    }
}

private fun requireSfnt(bytes: ByteArray, at: Int) {
    if (at < 0 || at + 12 > bytes.size) throw FontImportError(FontProblem.Corrupted)
    val count = u16(bytes, at + 4)
    if (count < 1 || at + 12 + count * 16 > bytes.size) throw FontImportError(FontProblem.Corrupted)
    val tags = HashSet<String>()
    for (i in 0 until count) {
        val record = at + 12 + i * 16
        val offset = u32(bytes, record + 8)
        val length = u32(bytes, record + 12)
        if (offset + length > bytes.size) throw FontImportError(FontProblem.Corrupted)
        tags += tag(bytes, record)
    }
    if (!tags.containsAll(RequiredTables) || OutlineTables.none { it in tags }) throw FontImportError(FontProblem.Corrupted)
}

fun woffToSfnt(bytes: ByteArray): ByteArray {
    if (bytes.size < 44 || tag(bytes, 0) != "wOFF" || u32(bytes, 8) != bytes.size.toLong()) throw FontImportError(FontProblem.Corrupted)
    val count = u16(bytes, 12)
    if (count < 1 || 44 + count * 20 > bytes.size || u32(bytes, 16) > MAX_FONT_BYTES) throw FontImportError(FontProblem.Corrupted)
    class Table(val tag: ByteArray, val checksum: Long, val data: ByteArray)
    var unpacked = 0L
    val tables = (0 until count).map { i ->
        val entry = 44 + i * 20
        val offset = u32(bytes, entry + 4).toInt()
        val compressed = u32(bytes, entry + 8).toInt()
        val original = u32(bytes, entry + 12).toInt()
        if (offset < 0 || compressed < 0 || original < 0 || offset.toLong() + compressed > bytes.size || compressed > original || original > MAX_FONT_BYTES) throw FontImportError(FontProblem.Corrupted)
        unpacked += original
        if (unpacked > MAX_FONT_BYTES) throw FontImportError(FontProblem.TooLarge)
        val data = if (compressed == original) bytes.copyOfRange(offset, offset + compressed) else inflate(bytes, offset, compressed, original)
        Table(bytes.copyOfRange(entry, entry + 4), u32(bytes, entry + 16), data)
    }.sortedBy { String(it.tag, Charsets.ISO_8859_1) }
    var power = 1
    var log = 0
    while (power * 2 <= count) { power *= 2; log++ }
    val out = ByteArrayOutputStream()
    fun put16(v: Int) { out.write(v ushr 8 and 255); out.write(v and 255) }
    fun put32(v: Long) { put16((v ushr 16).toInt() and 0xFFFF); put16(v.toInt() and 0xFFFF) }
    out.write(bytes, 4, 4)
    put16(count); put16(power * 16); put16(log); put16(count * 16 - power * 16)
    var offset = 12L + count * 16
    for (table in tables) {
        out.write(table.tag)
        put32(table.checksum); put32(offset); put32(table.data.size.toLong())
        offset += (table.data.size + 3) / 4 * 4
    }
    for (table in tables) {
        out.write(table.data)
        repeat((4 - table.data.size % 4) % 4) { out.write(0) }
    }
    return out.toByteArray()
}

private fun inflate(bytes: ByteArray, offset: Int, length: Int, size: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(bytes, offset, length)
        val out = ByteArray(size)
        var done = 0
        while (done < size && !inflater.finished()) {
            val n = inflater.inflate(out, done, size - done)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            done += n
        }
        if (done != size) throw FontImportError(FontProblem.Corrupted)
        return out
    } finally { inflater.end() }
}

private fun ByteArray.startsWith(text: String) = size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

private fun tag(bytes: ByteArray, at: Int): String = if (at + 4 > bytes.size) "" else String(bytes, at, 4, Charsets.ISO_8859_1)

private fun u16(bytes: ByteArray, at: Int): Int = if (at + 2 > bytes.size) 0 else (bytes[at].toInt() and 255 shl 8) or (bytes[at + 1].toInt() and 255)

private fun u32(bytes: ByteArray, at: Int): Long = if (at + 4 > bytes.size) 0 else (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()
