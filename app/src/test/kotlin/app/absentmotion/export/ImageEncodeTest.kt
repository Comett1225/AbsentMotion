package app.absentmotion.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun aGifFrameIsEncodedFromItsOwnColoursOnly() {
        fun solid(r: Int, g: Int, b: Int) = ByteArray(64 * 4) { i -> when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> 255 }.toByte() }
        val gradient = ByteArray(64 * 4) { i -> if (i % 4 == 3) -1 else (i / 4 * 4 + i % 4 * 60).toByte() }
        fun second(first: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val writer = GifWriter(out, 8, 8)
            writer.frame(first, 100)
            val start = out.size()
            writer.frame(gradient, 200)
            return out.toByteArray().copyOfRange(start, out.size())
        }
        assertTrue(second(solid(255, 0, 0)).contentEquals(second(solid(0, 0, 255))))
    }

    @Test
    fun aTransparentGifFrameTakesTheChosenBackground() {
        fun firstColour(background: Int): List<Int> {
            val out = java.io.ByteArrayOutputStream()
            GifWriter(out, 2, 2).frame(ImageEncode.flatten(ByteArray(16), background), 100)
            return out.toByteArray().slice(50..52).map { it.toInt() and 255 }
        }
        assertEquals(listOf(255, 255, 255), firstColour(BackgroundWhite))
        assertEquals(listOf(0, 0, 0), firstColour(BackgroundBlack))
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

    @Test
    fun sequenceFrameNamesUseAsciiDigitsInEveryLanguage() {
        val before = Locale.getDefault()
        try {
            for (tag in listOf("ar-SA-u-nu-arab", "fa-IR-u-nu-arabext", "bn-BD-u-nu-beng", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "frame_00001.png", sequenceFrameName(0, "png"))
                assertEquals(tag, "frame_00120.jpg", sequenceFrameName(119, "jpg"))
            }
            Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertFalse("%05d".format(1) == "00001")
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun theGifSizeEstimateIsCloseForPhotographicFrames() {
        val (w, h) = 360 to 640
        val random = java.util.Random(7)
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val base = doubleArrayOf(128 + 100 * sin(x / 40.0) * sin(y / 70.0), 90 + 80 * sin((x + y) / 90.0), 60 + 50 * sin(y / 30.0 + x / 120.0))
            for (c in 0 until 3) rgba[(y * w + x) * 4 + c] = (base[c] + random.nextGaussian() * 8).toInt().coerceIn(0, 255).toByte()
            rgba[(y * w + x) * 4 + 3] = -1
        }
        val out = java.io.ByteArrayOutputStream()
        val writer = GifWriter(out, w, h)
        val header = out.size()
        writer.frame(rgba, 100)
        val frameBytes = out.size() - header
        val scene = app.absentmotion.model.Scene(app.absentmotion.model.Scene.create("t", w, h, 15, 0).node.with("totalTime", "999"))
        val frames = frameCount(scene, 15)
        val ratio = estimateBytes(ExportKind.Gif, scene, ExportSettings(360, 15), false).toDouble() / (frames.toLong() * frameBytes)
        assertTrue("estimate / encoded = $ratio", ratio in 0.6..1.6)
    }
}
