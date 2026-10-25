package app.absentmotion.fonts

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Deflater

class FontFileTest {
    private val real: ByteArray = File(TestData.project, "app/src/main/assets/fonts/AbrilFatface/AbrilFatface-Regular.ttf").readBytes()

    private fun sfnt(flavor: String, tags: List<String>, base: Int = 0): ByteArray {
        val data = 12 + tags.size * 16
        val out = ByteBuffer.allocate(data + tags.size * 8)
        out.put(if (flavor == "1") byteArrayOf(0, 1, 0, 0) else flavor.toByteArray(Charsets.ISO_8859_1))
        out.putShort(tags.size.toShort()).putShort(0).putShort(0).putShort(0)
        tags.forEachIndexed { i, tag -> out.put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(0).putInt(base + data + i * 8).putInt(8) }
        return out.array()
    }

    private fun problem(bytes: ByteArray): FontProblem? = try { checkedFont(bytes); null } catch (e: FontImportError) { e.problem }

    private fun woff(font: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(font)
        val count = source.getShort(4).toInt()
        val tables = (0 until count).map { i ->
            val record = 12 + i * 16
            val offset = source.getInt(record + 8)
            val length = source.getInt(record + 12)
            Triple(font.copyOfRange(record, record + 4), source.getInt(record + 4), font.copyOfRange(offset, offset + length))
        }
        val packed = tables.map { (_, _, data) ->
            val deflater = Deflater().apply { setInput(data); finish() }
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(65536)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            deflater.end()
            out.toByteArray().takeIf { it.size < data.size } ?: data
        }
        var offset = 44 + count * 20
        val total = offset + packed.sumOf { (it.size + 3) / 4 * 4 }
        val out = ByteBuffer.allocate(total)
        out.put("wOFF".toByteArray()).put(font, 0, 4).putInt(total).putShort(count.toShort()).putShort(0).putInt(font.size)
        out.putShort(1).putShort(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
        tables.forEachIndexed { i, (tag, checksum, data) ->
            out.put(tag).putInt(offset).putInt(packed[i].size).putInt(data.size).putInt(checksum)
            offset += (packed[i].size + 3) / 4 * 4
        }
        packed.forEach { out.put(it); repeat((4 - it.size % 4) % 4) { out.put(0) } }
        return out.array()
    }

    @Test fun realTrueTypeFontPasses() {
        val (bytes, kind) = checkedFont(real)
        assertEquals(FontKind.Ttf, kind)
        assertEquals(real.size, bytes.size)
    }

    @Test fun openTypeAndCollectionsPass() {
        assertEquals(FontKind.Otf, checkedFont(sfnt("OTTO", listOf("CFF ", "cmap", "head", "maxp"))).second)
        val first = sfnt("1", listOf("cmap", "glyf", "head", "maxp"), base = 20)
        val second = sfnt("1", listOf("cmap", "glyf", "head", "maxp"), base = 20 + first.size)
        val collection = ByteBuffer.allocate(20 + first.size + second.size)
            .put("ttcf".toByteArray()).putInt(0x00010000).putInt(2).putInt(20).putInt(20 + first.size).put(first).put(second).array()
        assertEquals(FontKind.Ttc, checkedFont(collection).second)
    }

    @Test fun woffBecomesTheSameFont() {
        val (bytes, kind) = checkedFont(woff(real))
        assertEquals(FontKind.Ttf, kind)
        val original = ByteBuffer.wrap(real)
        val converted = ByteBuffer.wrap(bytes)
        val count = original.getShort(4).toInt()
        assertEquals(count, converted.getShort(4).toInt())
        val tables = { b: ByteBuffer, raw: ByteArray -> (0 until count).associate { i ->
            val r = 12 + i * 16
            String(raw, r, 4, Charsets.ISO_8859_1) to raw.copyOfRange(b.getInt(r + 8), b.getInt(r + 8) + b.getInt(r + 12)).toList()
        } }
        assertEquals(tables(original, real), tables(converted, bytes))
    }

    @Test fun everyFailureHasItsOwnReason() {
        assertEquals(FontProblem.NotFont, problem("PK\u0003\u0004 not a font at all".toByteArray()))
        assertEquals(FontProblem.NotFont, problem(ByteArray(0)))
        assertEquals(FontProblem.Unsupported, problem("wOF2".toByteArray() + ByteArray(60)))
        assertEquals(FontProblem.Unsupported, problem("%!PS-AdobeFont-1.0".toByteArray()))
        assertEquals(FontProblem.Corrupted, problem(real.copyOf(real.size / 2)))
        assertEquals(FontProblem.Corrupted, problem(sfnt("1", listOf("cmap", "head"))))
        assertEquals(FontProblem.Corrupted, problem(woff(real).copyOf(300)))
        assertEquals(FontProblem.TooLarge, problem(real.copyOf(MAX_FONT_BYTES + 1)))
    }

    @Test fun aWoffThatUnpacksPastTheLimitIsRefusedBeforeItIsUnpacked() {
        val zeros = ByteArray(20 shl 20)
        val deflater = Deflater(9).apply { setInput(zeros); finish() }
        val packed = ByteArrayOutputStream().also { out -> val chunk = ByteArray(65536); while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk)) }.toByteArray()
        deflater.end()
        val garbage = ByteArray(packed.size) { 0x55 }
        val dataAt = 44 + 2 * 20
        val total = dataAt + packed.size + garbage.size
        val b = ByteBuffer.allocate(total)
        b.put("wOFF".toByteArray()).put(byteArrayOf(0, 1, 0, 0)).putInt(total).putShort(2).putShort(0).putInt(1000)
        b.putShort(1).putShort(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
        b.put("aaaa".toByteArray()).putInt(dataAt).putInt(packed.size).putInt(zeros.size).putInt(0)
        b.put("bbbb".toByteArray()).putInt(dataAt + packed.size).putInt(garbage.size).putInt(zeros.size).putInt(0)
        b.put(packed).put(garbage)
        assertEquals(FontProblem.TooLarge, problem(b.array()))
    }

    @Test fun damagedWoffDataIsCorrupted() {
        val broken = woff(real)
        val b = ByteBuffer.wrap(broken)
        val entry = (0 until b.getShort(12).toInt()).map { 44 + it * 20 }.first { b.getInt(it + 8) < b.getInt(it + 12) }
        val at = b.getInt(entry + 4)
        for (i in at + 2 until at + 40) broken[i] = 0x55
        assertEquals(FontProblem.Corrupted, problem(broken))
    }
}
