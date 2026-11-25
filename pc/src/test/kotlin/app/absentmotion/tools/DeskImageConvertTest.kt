package app.absentmotion.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.absentmotion.export.GifWriter
import app.absentmotion.media.summarizeGif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class DeskImageConvertTest {
    @get:Rule val folder = TemporaryFolder()

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun Bitmap.argb() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun near(want: Int, got: Int, slack: Int = 12) =
        (0..24 step 8).all { abs((want ushr it and 255) - (got ushr it and 255)) <= slack }

    private fun convert(source: File, to: PictureFormat, turn: Int = 0): Pair<File, Int> {
        val out = folder.newFile("out-${source.nameWithoutExtension}-${to.ext}-$turn.${to.ext}")
        return out to convertPictureFile({ source.inputStream() }, { BitmapFactory.decodeFile(source.path) }, turn, to, out)
    }

    @Test fun anAnimatedGifBecomesAnAnimatedWebpAndBack() {
        val gif = folder.newFile("moving.gif")
        gif.outputStream().use { out ->
            val writer = GifWriter(out, 8, 6)
            listOf(red, green, blue).forEachIndexed { i, c -> writer.frame(argbToRgba(IntArray(48) { c }, true), 100L * (i + 1)) }
            writer.finish()
        }
        val (webp, frames) = convert(gif, PictureFormat.Webp)
        assertEquals(3, frames)
        val bytes = webp.readBytes()
        assertEquals(PictureFormat.Webp, PictureFormat.of(bytes))
        val seen = ArrayList<Pair<Int, Int>>()
        assertEquals(3, readAnimatedWebp(bytes, { data -> BitmapFactory.decodeByteArray(data, 0, data.size)?.argb() }) { canvas, w, h, ms ->
            assertEquals(8 to 6, w to h)
            seen += canvas[47] to ms
        })
        assertEquals(listOf(100, 100, 100), seen.map { it.second })
        assertTrue(seen.toString(), seen.map { it.first }.zip(listOf(red, green, blue)).all { (got, want) -> near(want, got) })

        val (back, again) = convert(webp, PictureFormat.Gif)
        assertEquals(3, again)
        val summary = summarizeGif { back.inputStream() }
        assertEquals(3, summary.frames)
        assertEquals(8 to 6, summary.width to summary.height)

        val (still, one) = convert(webp, PictureFormat.Png)
        assertEquals(1, one)
        assertTrue(near(red, BitmapFactory.decodeFile(still.path)!!.argb()[0]))
    }

    @Test fun stillPicturesConvertBetweenEveryFormat() {
        val image = BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 16) image.setRGB(x, y, if (x < 8) 0 else if (x < 12) red else blue)
        val png = folder.newFile("still.png").also { ImageIO.write(image, "png", it) }
        for (to in PictureFormat.entries) {
            val (out, frames) = convert(png, to)
            assertEquals(1, frames)
            val bytes = out.readBytes()
            assertEquals(to, PictureFormat.of(bytes))
            val picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(16 to 8, picture.width to picture.height)
            val pixels = picture.argb()
            when (to) {
                PictureFormat.Png, PictureFormat.Webp -> { assertEquals(0, pixels[0] ushr 24); assertEquals(red, pixels[8]); assertEquals(blue, pixels[15]) }
                PictureFormat.Gif -> { assertTrue(near(0xFFFFFFFF.toInt(), pixels[0])); assertTrue(near(blue, pixels[15])) }
                PictureFormat.Jpg -> assertTrue(Integer.toHexString(pixels[0]), near(0xFFFFFFFF.toInt(), pixels[0], 24))
            }
        }
        val (turned, _) = convert(png, PictureFormat.Webp, 90)
        assertEquals(8 to 16, BitmapFactory.decodeFile(turned.path)!!.let { it.width to it.height })
    }
}
