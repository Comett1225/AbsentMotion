package app.absentmotion.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ImageConvertTest {
    private fun le(v: Int, n: Int) = ByteArray(n) { (v ushr (8 * it) and 255).toByte() }

    private fun chunk(tag: String, data: ByteArray) = tag.toByteArray() + le(data.size, 4) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(byteArrayOf()) { a, b -> a + b }
        return "RIFF".toByteArray() + le(body.size + 4, 4) + "WEBP".toByteArray() + body
    }

    private fun anmf(x: Int, y: Int, w: Int, h: Int, ms: Int, flags: Int, colour: Int) =
        chunk("ANMF", le(x / 2, 3) + le(y / 2, 3) + le(w - 1, 3) + le(h - 1, 3) + le(ms, 3) + byteArrayOf(flags.toByte()) + chunk("VP8L", le(colour, 4)))

    private fun fakeDecode(still: ByteArray): IntArray? {
        val vp8l = webpChunks(still).single { it.tag == "VP8L" }.data
        val colour = vp8l.foldIndexed(0) { i, v, b -> v or ((b.toInt() and 255) shl (8 * i)) }
        val head = webpChunks(still).firstOrNull { it.tag == "VP8X" }
        return IntArray(64) { colour }.takeIf { head == null }
    }

    @Test fun formatsAreToldApartByTheirFirstBytes() {
        assertEquals(PictureFormat.Gif, PictureFormat.of("GIF89a".toByteArray()))
        assertEquals(PictureFormat.Webp, PictureFormat.of(riff()))
        assertEquals(PictureFormat.Png, PictureFormat.of(byteArrayOf(0x89.toByte()) + "PNG\r\n".toByteArray()))
        assertEquals(PictureFormat.Jpg, PictureFormat.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(null, PictureFormat.of("hello".toByteArray()))
        assertEquals(null, PictureFormat.of(byteArrayOf()))
    }

    @Test fun framesWrittenAsAnimatedWebpReadBackInOrderWithTheirTimes() {
        val file = File.createTempFile("anim", ".webp")
        try {
            RandomAccessFile(file, "rw").use { out ->
                val writer = AnimatedWebpWriter(out, 4, 2)
                for (i in 1..3) writer.frame(riff(chunk("ICCP", byteArrayOf(1, 2, 3)), chunk("VP8L", le(0xFF000000.toInt() or i, 4))), 100 * i)
                writer.finish()
            }
            val bytes = file.readBytes()
            assertArrayEquals(le(bytes.size - 8, 4), bytes.copyOfRange(4, 8))
            assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF", "ANMF"), webpChunks(bytes).map { it.tag })
            assertEquals(0x12, webpChunks(bytes).first().data[0].toInt())
            assertEquals(PictureFormat.Webp, PictureFormat.of(bytes))
            val seen = ArrayList<Pair<Int, Int>>()
            val count = readAnimatedWebp(bytes, ::fakeDecode) { canvas, w, h, ms ->
                assertEquals(4 to 2, w to h)
                seen += (canvas[0] and 255) to ms
            }
            assertEquals(3, count)
            assertEquals(listOf(1 to 100, 2 to 200, 3 to 300), seen)
        } finally { file.delete() }
    }

    @Test fun framesBlendAndDisposeOnTheCanvas() {
        val red = 0xFFFF0000.toInt()
        val clearBlue = 0x800000FF.toInt()
        val bytes = riff(
            chunk("VP8X", le(0x12, 4) + le(3, 3) + le(1, 3)), chunk("ANIM", ByteArray(6)),
            anmf(0, 0, 4, 2, 50, 2, red), anmf(2, 0, 2, 2, 60, 1, clearBlue), anmf(0, 0, 2, 2, 70, 0, 0),
        )
        val frames = ArrayList<IntArray>()
        readAnimatedWebp(bytes, ::fakeDecode) { canvas, _, _, _ -> frames += canvas.copyOf() }
        assertEquals(3, frames.size)
        assertArrayEquals(IntArray(8) { red }, frames[0])
        assertEquals(red, frames[1][0])
        assertEquals(over(clearBlue, red), frames[1][2])
        assertEquals(0, frames[2][2])
        assertEquals(red, frames[2][0])
    }

    @Test fun lossyFramesKeepTheirAlphaInsideAnExtendedHeader() {
        val still = stillWebp(listOf(WebpChunk("ALPH", byteArrayOf(9)), WebpChunk("VP8 ", byteArrayOf(1, 2)), WebpChunk("EXIF", byteArrayOf(3))), 5, 7)
        val parts = webpChunks(still)
        assertEquals(listOf("VP8X", "ALPH", "VP8 "), parts.map { it.tag })
        assertEquals(0x10, parts[0].data[0].toInt())
        assertEquals(4, parts[0].data[4].toInt())
        assertEquals(6, parts[0].data[7].toInt())
        assertEquals(listOf("VP8L"), webpChunks(stillWebp(listOf(WebpChunk("ALPH", byteArrayOf(1)), WebpChunk("VP8L", byteArrayOf(2))), 1, 1)).map { it.tag })
    }

    @Test fun blendingAndFlatteningFollowAlpha() {
        assertEquals(0xFF0000FF.toInt(), over(0xFF0000FF.toInt(), 0xFFFF0000.toInt()))
        assertEquals(0xFFFF0000.toInt(), over(0x000000FF, 0xFFFF0000.toInt()))
        val half = over(0x800000FF.toInt(), 0xFFFF0000.toInt())
        assertEquals(255, half ushr 24)
        assertEquals(127, half shr 16 and 255)
        assertEquals(128, half and 255)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, 10, 20, 30, -1), argbToRgba(intArrayOf(0, 0xFF0A141E.toInt()), opaque = true))
        assertArrayEquals(byteArrayOf(10, 20, 30, 0x40), argbToRgba(intArrayOf(0x400A141E), opaque = false))
        assertArrayEquals(byteArrayOf(-1, 127, 127, -1), argbToRgba(intArrayOf(0x80FF0000.toInt()), opaque = true))
    }
}
