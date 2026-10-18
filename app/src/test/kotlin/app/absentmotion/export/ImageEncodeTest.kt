package app.absentmotion.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageEncodeTest {
    private fun px(r: Int, g: Int, b: Int, a: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte())

    @Test
    fun flattenKeepsOpaqueAndBlendsTransparentOntoTheBackground() {
        val out = ImageEncode.flatten(px(10, 20, 30, 255) + px(200, 100, 0, 0) + px(255, 0, 0, 128), BackgroundWhite)
        assertEquals(listOf(10, 20, 30, 255), out.take(4).map { it.toInt() and 255 })
        assertEquals(listOf(255, 255, 255, 255), out.slice(4..7).map { it.toInt() and 255 })
        assertEquals(listOf(255, 127, 127, 255), out.slice(8..11).map { it.toInt() and 255 })
    }

    @Test
    fun flattenOntoBlackDarkensHalfTransparentPixels() {
        val out = ImageEncode.flatten(px(200, 100, 50, 128), BackgroundBlack)
        assertEquals(listOf(100, 50, 25, 255), out.map { it.toInt() and 255 })
    }

    @Test
    fun bmpHasTheRightHeaderRowPaddingAndBottomUpBgrRows() {
        val rgba = px(1, 2, 3, 255) + px(4, 5, 6, 255) + px(7, 8, 9, 255) + px(10, 11, 12, 255) + px(13, 14, 15, 255) + px(16, 17, 18, 255)
        val bmp = ImageEncode.bmp(rgba, 3, 2)
        val b = ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("BM", String(bmp, 0, 2))
        assertEquals(bmp.size, b.getInt(2))
        assertEquals(54 + 12 * 2, bmp.size)
        assertEquals(3, b.getInt(18))
        assertEquals(2, b.getInt(22))
        assertEquals(24, b.getShort(28).toInt())
        assertEquals(listOf(12, 11, 10), (54..56).map { bmp[it].toInt() })
        assertEquals(listOf(0, 0, 0), (63..65).map { bmp[it].toInt() })
        assertEquals(listOf(3, 2, 1), (66..68).map { bmp[it].toInt() })
    }

    @Test
    fun onlyTheLosslessAlphaFormatsKeepTransparency() {
        assertFalse(ImageKind.Jpeg.alpha)
        assertFalse(ImageKind.Bmp.alpha)
        assertEquals(listOf("png", "jpg", "webp", "webp", "bmp"), ImageKind.entries.map { it.ext })
    }
}
