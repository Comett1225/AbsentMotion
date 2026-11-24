package app.absentmotion.library

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectQrTest {
    private val first = "https://alight.link/first"
    private val second = "https://example.org/second.absproj"
    private val third = "alight.link/third"

    @Test fun readsOneCode() {
        val picture = picture(200, 200, 180, first)
        assertEquals(listOf(first), projectQrLinks(picture, 200, 200))
    }

    @Test fun readsThreeCodesInOnePicture() {
        val picture = picture(600, 400, 200, first, second, third)
        val links = projectQrLinks(picture, 600, 400)
        assertEquals(3, links.size)
        assertEquals(setOf(first, second, third), links.toSet())
        assertEquals(links, projectQrLinks(picture, 600, 400))
    }

    @Test fun filtersNonProjectCodesAndDropsDuplicates() {
        val picture = picture(600, 400, 200, first, "plain text", first, second)
        val links = projectQrLinks(picture, 600, 400)
        assertEquals(2, links.size)
        assertEquals(setOf(first, second), links.toSet())
    }

    @Test fun readsARotatedCode() {
        val side = 200
        val picture = picture(side, side, 180, first)
        val rotated = IntArray(picture.size) { index -> picture[(side - 1 - index % side) * side + index / side] }
        assertEquals(listOf(first), projectQrLinks(rotated, side, side))
    }

    @Test fun readsASmallCode() {
        val link = "https://a.co/x"
        val picture = picture(41, 41, 41, link)
        assertEquals(listOf(link), projectQrLinks(picture, 41, 41))
    }

    @Test fun readsAnInvertedCode() {
        val picture = picture(200, 200, 180, second)
        val inverted = IntArray(picture.size) { index -> picture[index] xor 0xffffff }
        assertEquals(listOf(second), projectQrLinks(inverted, 200, 200))
    }

    private fun picture(width: Int, height: Int, size: Int, vararg links: String): IntArray {
        val pixels = IntArray(width * height) { -1 }
        val columns = width / size
        for ((index, link) in links.withIndex()) {
            val code = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size)
            val left = (index % columns) * size
            val top = (index / columns) * size
            for (y in 0 until size) for (x in 0 until size) {
                if (code[x, y]) pixels[(top + y) * width + left + x] = 0xff000000.toInt()
            }
        }
        return pixels
    }
}
