package app.absentmotion.effects

import app.absentmotion.model.XmlNode
import app.absentmotion.ui.editor.panels.swatchRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

class PaletteFilesTest {
    private val gameBoy = listOf(0x0f380f, 0x306230, 0x8bac0f, 0x9bbc0f)
    private val opaque = 0xFF000000.toInt()

    private fun parse(name: String, text: String) = Palettes.fromFile(name, text.toByteArray())

    private fun near(expected: Int, actual: Int, tolerance: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (expected shr shift) and 255
            val b = (actual shr shift) and 255
            assertTrue("%06x is not close to %06x".format(actual, expected), abs(a - b) <= tolerance)
        }
    }

    private fun block(type: Int, body: ByteArray) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeShort(type); writeInt(body.size); write(body) } }.toByteArray()

    private fun named(name: String, extra: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeShort(name.length + 1); name.forEach { c -> writeChar(c.code) }; writeChar(0); extra() }
    }.toByteArray()

    private fun swatch(model: String, vararg values: Float) =
        block(1, named("sample") { writeBytes(model); values.forEach { writeFloat(it) }; writeShort(2) })

    private fun ase(vararg blocks: ByteArray) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeBytes("ASEF"); writeShort(1); writeShort(0); writeInt(blocks.size); blocks.forEach { b -> write(b) } }
    }.toByteArray()

    private fun riff(entries: List<Int>, count: Int = entries.size): ByteArray {
        val data = ByteBuffer.allocate(4 + 4 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0x0300).putShort(count.toShort())
        entries.forEach { data.put((it shr 16).toByte()).put((it shr 8).toByte()).put(it.toByte()).put(0) }
        val out = ByteBuffer.allocate(20 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(12 + data.capacity()).put("PAL ".toByteArray()).put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return out.array()
    }

    @Test fun textReadsAndFormatsTheStoredValue() {
        val text = "0f380f 306230 8bac0f 9bbc0f"
        assertEquals(gameBoy, Palettes.read(text))
        assertEquals(text, Palettes.format(Palettes.read(text)))
        assertEquals(gameBoy, Palettes.read("#0F380F, 306230\n8BAC0F\t#9bbc0f  nonsense 12345"))
        assertEquals("", Palettes.format(emptyList()))
        assertTrue(Palettes.read("").isEmpty())
    }

    @Test fun formatDropsDuplicatesAndAlphaAndStopsAt512() {
        assertEquals("000001 000002", Palettes.format(listOf(1, 2, 1, 0xFF000002.toInt())))
        val long = Palettes.format(List(600) { it }).split(' ')
        assertEquals(512, long.size)
        assertEquals("000000", long.first())
        assertEquals("0001ff", long.last())
    }

    @Test fun presetsAreComplete() {
        val presets = Palettes.presets
        assertEquals(listOf("1-bit Gray", "2-bit Gray", "4-bit Gray", "3-bit RGB", "6-bit RGB", "9-bit RGB", "Web Safe", "PICO-8", "Game Boy", "CGA",
            "CGA 4-Color", "Amstrad CPC", "ZX Spectrum", "Commodore 64", "NES", "MSX", "Apple II"), presets.map { it.name })
        assertEquals(listOf(2, 4, 16, 8, 64, 512, 216, 16, 4, 16, 4, 27, 15, 16, 55, 15, 15), presets.map { it.colors.size })
        for (preset in presets) {
            assertTrue(preset.name, preset.colors.size in 2..512)
            assertEquals(preset.name, preset.colors.size, preset.colors.toSet().size)
            assertTrue(preset.name, preset.colors.all { it in 0..0xFFFFFF })
            assertEquals(preset.name, preset, Palettes.presetOf(Palettes.format(preset.colors)))
        }
        assertEquals(gameBoy, presets.first { it.name == "Game Boy" }.colors)
        assertEquals(listOf(0x000000, 0x111111, 0x222222), presets.first { it.name == "4-bit Gray" }.colors.take(3))
        assertEquals(listOf(0x000000, 0x000080, 0x0000ff, 0x008000), presets.first { it.name == "Amstrad CPC" }.colors.take(4))
        assertNull(Palettes.presetOf("0f380f 306230"))
        assertEquals("Game Boy", Palettes.presetOf("0F380F 306230 8BAC0F 9BBC0F")?.name)
    }

    @Test fun adobeSwatchExchangeReadsEveryColourModel() {
        val bytes = ase(
            block(0xC001, named("Group") {}),
            swatch("RGB ", 0f, 1f, 0f),
            swatch("RGB ", 0.5f, 0.25f, 1f),
            swatch("CMYK", 0.5f, 0f, 0f, 0.5f),
            swatch("Gray", 0.5f),
            swatch("LAB ", 0.5f, 0f, 0f),
            swatch("LAB ", 0.542917f, 80.8151f, 69.8786f),
            swatch("XYZ ", 0.1f, 0.2f, 0.3f),
            block(0x1234, ByteArray(5)),
            block(0xC002, ByteArray(0)),
            swatch("RGB ", 1f, 1f, 1f),
        )
        val colors = Palettes.fromFile("sample.ase", bytes)!!
        assertEquals(listOf(0x00ff00, 0x8040ff, 0x408080, 0x808080), colors.take(4))
        near(0x777777, colors[4], 1)
        near(0xff0000, colors[5], 1)
        assertEquals(listOf(0xffffff), colors.drop(6))
        assertEquals(colors, Palettes.fromFile("renamed.bin", bytes))
        near(0x123456, Palettes.fromFile("x.ase", ase(swatch("LAB ", 0.20675f, -2.2757f, -24.604f)))!!.single(), 1)
        assertEquals(listOf(0xffffff), Palettes.fromFile("x.ase", ase(swatch("LAB ", 1f, 0f, 0f))))
    }

    @Test fun adobeSwatchExchangeSurvivesBrokenBlocks() {
        val red = swatch("RGB ", 1f, 0f, 0f)
        val odd = ase(red, swatch("RGB ", Float.NaN, Float.POSITIVE_INFINITY, -3f), block(1, ByteArray(3)), block(1, ByteArray(0)))
        assertEquals(listOf(0xff0000, 0x00ff00), Palettes.fromFile("x.ase", odd))
        val twice = ase(red, swatch("RGB ", 0f, 0f, 1f))
        assertEquals(listOf(0xff0000), Palettes.fromFile("x.ase", twice.copyOf(twice.size - 3)))
        assertNull(Palettes.fromFile("x.ase", ase(red).let { it.copyOf(it.size - 3) }))
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeBytes("ASEF"); writeInt(0x00010000); writeInt(1); writeShort(1); writeInt(Int.MAX_VALUE); write(ByteArray(8)) } }.toByteArray()
        assertNull(Palettes.fromFile("x.ase", huge))
        assertNull(Palettes.fromFile("x.ase", "ASEF".toByteArray()))
    }

    @Test fun photoshopColourTableUsesTheOptionalCount() {
        val table = ByteArray(768)
        listOf(0xff0000, 0x00ff00, 0x0000ff).forEachIndexed { i, c -> table[i * 3] = (c shr 16).toByte(); table[i * 3 + 1] = (c shr 8).toByte(); table[i * 3 + 2] = c.toByte() }
        assertEquals(listOf(0xff0000, 0x00ff00, 0x0000ff, 0x000000), Palettes.fromFile("a.act", table))
        assertEquals(listOf(0xff0000, 0x00ff00, 0x0000ff), Palettes.fromFile("A.ACT", table + byteArrayOf(0, 3, -1, -1)))
        assertEquals(4, Palettes.fromFile("a.act", table + byteArrayOf(0, 0, 0, 0))!!.size)
        assertNull(Palettes.fromFile("a.act", ByteArray(100)))
        assertNull(Palettes.fromFile("a.txt", table))
    }

    @Test fun gimpPaletteReadsDecimalLines() {
        val text = "GIMP Palette\nName: Game Boy\nColumns: 4\n#\n#\n 15  56  15\tDarkest\n 48  98  48\tDark\n139 172  15\tLight\n155 188  15\tLightest\n"
        assertEquals(gameBoy, parse("game-boy.gpl", text))
        assertEquals(gameBoy, parse("whatever.txt", text.replace("\n", "\r\n")))
        assertEquals(gameBoy, parse("x.gpl", "\uFEFF" + text))
        assertNull(parse("x.gpl", "GIMP Palette\nName: nothing here\n"))
    }

    @Test fun hexFilesTakeOneColourPerLine() {
        assertEquals(gameBoy, parse("x.hex", "0f380f\n306230\r\n8bac0f\n#9bbc0f\n\n"))
        assertEquals(gameBoy, parse("x.hex", "0F380F 306230,8BAC0F\n9BBC0F"))
        assertNull(parse("x.hex", "0f380f\nnot a colour\n"))
        assertEquals(listOf(1, 2), parse("x.hex", "000001\n000002\n000001\n"))
        assertEquals(List(512) { it }, parse("many.hex", (0 until 600).joinToString("\n") { "%06x".format(it) }))
    }

    @Test fun jascPalettesFollowTheirCount() {
        val lines = "15 56 15\r\n48 98 48\r\n139 172 15\r\n155 188 15"
        assertEquals(gameBoy, parse("x.pal", "JASC-PAL\r\n0100\r\n4\r\n$lines\r\n"))
        assertEquals(gameBoy.take(2), parse("x.pal", "JASC-PAL\r\n0100\r\n2\r\n$lines\r\n"))
        assertEquals(gameBoy, parse("x.pal", "JASC-PAL\n0100\n9\n${lines.replace("\r", "")}\n"))
        assertEquals(gameBoy, parse("x.pal", "JASC-PAL\n0100\n0\n${lines.replace("\r", "")}\n"))
        assertNull(parse("x.pal", "JASC-PAL\n0100\n0\n"))
        assertNull(parse("x.pal", "JASC-PAL\n0100\n1\n300 0 0\n"))
    }

    @Test fun microsoftRiffPalettesAreRead() {
        assertEquals(gameBoy, Palettes.fromFile("x.pal", riff(gameBoy)))
        assertEquals(gameBoy.take(3), Palettes.fromFile("x.pal", riff(gameBoy, count = 3)))
        assertEquals(gameBoy, Palettes.fromFile("x.pal", riff(gameBoy, count = 900)))
        assertEquals(gameBoy.take(1), Palettes.fromFile("x.pal", riff(gameBoy).copyOf(30)))
        assertNull(Palettes.fromFile("x.pal", riff(gameBoy).copyOf(22)))
        assertNull(Palettes.fromFile("x.pal", riff(gameBoy).also { "WEBP".toByteArray().copyInto(it, 8) }))
    }

    @Test fun paintNetFilesIgnoreAlphaAndComments() {
        val text = "; paint.net Palette File\n; Downloaded from a palette site\n; Palette Name: Game Boy\n; Colors: 4\nFF0F380F\nFF306230\n008BAC0F\nFF9BBC0F\n"
        assertEquals(gameBoy, parse("x.txt", text))
        assertEquals(gameBoy, parse("x.hex", text))
        assertNull(parse("x.txt", "; only comments\n"))
    }

    @Test fun garbageNeverThrows() {
        assertNull(Palettes.fromFile("a.hex", ByteArray(0)))
        assertNull(Palettes.fromFile("a.png", ByteArray(100) { it.toByte() }))
        assertNull(parse("a.txt", "hello world"))
        assertNull(parse("a.txt", "   \n\n"))
        assertNull(parse("a.gpl", "GIMP Palette"))
        assertNull(parse("a.hex", "0f380f\n".repeat(400_000)))
        val random = Random(7)
        val valid = listOf(ase(swatch("RGB ", 1f, 0f, 0f), swatch("Gray", 0.5f)), riff(gameBoy), ByteArray(772), "GIMP Palette\n1 2 3 a\n4 5 6 b\n".toByteArray(),
            "JASC-PAL\n0100\n2\n1 2 3\n4 5 6\n".toByteArray(), "FF0F380F\nFF306230\n".toByteArray())
        for (name in listOf("a.ase", "a.act", "a.pal", "a.gpl", "a.hex", "a.txt", "a.png", "")) {
            val inputs = List(60) { random.nextBytes(random.nextInt(0, 900)) } + valid.flatMap { good ->
                List(40) { good.copyOf(random.nextInt(0, good.size + 1)).also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt().toByte() } }
            }
            for (bytes in inputs) {
                val colors = Palettes.fromFile(name, bytes) ?: continue
                assertTrue(colors.size in 1..512)
                assertTrue(colors.all { it in 0..0xFFFFFF })
                assertEquals(colors.size, colors.toSet().size)
            }
        }
    }

    @Test fun pixelsKeepTheirOrderUpTo256Colours() {
        val strip = IntArray(32 * 8) { opaque or gameBoy[(it % 32) / 8] }
        assertEquals(gameBoy, Palettes.fromPixels(strip))
        assertEquals(gameBoy, Palettes.fromPixels(IntArray(4) { opaque or gameBoy[it] }))
        val grid = List(256) { (it shl 16) or ((255 - it) shl 8) or (it xor 0x5a) }
        val scaled = IntArray(64 * 64) { opaque or grid[(it / 64 / 4) * 16 + (it % 64) / 4] }
        assertEquals(grid, Palettes.fromPixels(scaled))
        assertEquals(listOf(0xabcdef, 0x000000), Palettes.fromPixels(intArrayOf(0x00123456, 0x7f654321, 0x80abcdef.toInt(), opaque, opaque)))
        assertTrue(Palettes.fromPixels(IntArray(100)).isEmpty())
        assertTrue(Palettes.fromPixels(IntArray(0)).isEmpty())
    }

    @Test fun pixelsOfAGradientPhotoReduceToExactly256() {
        val width = 320
        val height = 240
        val photo = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            opaque or ((x * 255 / (width - 1)) shl 16) or ((y * 255 / (height - 1)) shl 8) or ((x + y) * 255 / (width + height - 2))
        }
        val colors = Palettes.fromPixels(photo)
        assertEquals(256, colors.size)
        assertEquals(256, colors.toSet().size)
        var total = 0L
        var counted = 0
        for (i in photo.indices step 97) {
            val p = photo[i]
            total += colors.minOf { c -> listOf(16, 8, 0).sumOf { s -> ((p shr s) and 255).let { a -> ((c shr s) and 255).let { b -> (a - b) * (a - b) } } } }
            counted++
        }
        assertTrue("mean squared error ${total / counted}", total / counted < 80)
    }

    @Test fun pixelsJustOver256ColoursAreReducedAndBigPhotosStayFast() {
        val over = IntArray(257 * 4) { opaque or ((it / 4) shl 8) or (it / 8) }
        assertEquals(257, over.toSet().size)
        assertEquals(256, Palettes.fromPixels(over).size)
        val random = Random(3)
        assertEquals(256, Palettes.fromPixels(IntArray(2000 * 1000) { random.nextInt() or opaque }).size)
        val sprite = IntArray(2000 * 1000)
        for (i in 0 until 400) sprite[i * 4999] = opaque or (i * 40507 % 0xFFFFFF)
        assertEquals(256, Palettes.fromPixels(sprite).size)
    }

    @Test fun swatchGridKeepsEveryColourInTheFixedArea() {
        for (width in listOf(231f, 263f, 400f)) {
            var before = 2
            for (count in 0..512) {
                val rows = swatchRows(count, width, 28f)
                assertTrue(rows >= before)
                before = rows
                assertTrue("$count colours in $width", floor(width * rows / 28f).toInt() * rows >= count)
            }
        }
        assertEquals(2, swatchRows(16, 263f, 28f))
        assertEquals(2, swatchRows(0, 263f, 28f))
    }

    @Test fun parameterReadsThePaletteFlag() {
        val palette = EffectParam.parse(JSONObject("""{"id":"palette","label":"Palette","type":"text","palette":true,"default":"000000 1d2b53"}"""))
        assertTrue(palette.palette)
        assertEquals("000000 1d2b53", palette.default)
        assertEquals("string", palette.type)
        assertFalse(EffectParam.parse(JSONObject("""{"id":"characters","label":"Custom","type":"text","default":"01"}""")).palette)
        assertFalse(EffectParam.parse(JSONObject("""{"id":"x","type":"float","default":1,"palette":false}""")).palette)
        try {
            EffectParam.parse(JSONObject("""{"id":"x","type":"float","default":1,"palette":true}"""))
            fail("a palette parameter must be text")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, "x" in e.message!!)
        }
    }

    @Test fun showWhenReadsSwitchValues() {
        val definition = EffectDefinition.parse(JSONObject("""{"id":"t","category":"stylize","native":true,"params":[
            {"id":"mix","label":"Mix","type":"switch","default":false},
            {"id":"palette","label":"Palette","type":"text","palette":true,"default":"000000 ffffff","when":{"mix":[1]}},
            {"id":"plain","label":"Plain","type":"float","default":1,"when":{"mix":[0]}},
            {"id":"on","label":"On","type":"switch","default":true},
            {"id":"later","label":"Later","type":"float","default":1,"when":{"on":[1]}}]}"""))
        val (mix, palette, plain, _, later) = definition.params
        fun instance(value: String?) = XmlNode("effect", mapOf("id" to definition.id),
            if (value == null) emptyList() else listOf(XmlNode("property", mapOf("name" to "mix", "type" to "bool", "value" to value))))
        assertTrue(definition.shown(palette, instance("true"), 0f))
        assertFalse(definition.shown(palette, instance("false"), 0f))
        assertFalse(definition.shown(palette, instance(null), 0f))
        assertFalse(definition.shown(plain, instance("true"), 0f))
        assertTrue(definition.shown(plain, instance("false"), 0f))
        assertTrue(definition.shown(plain, instance(null), 0f))
        assertTrue(definition.shown(mix, instance("true"), 0f))
        assertTrue(definition.shown(later, instance(null), 0f))
    }
}
