package app.absentmotion.pc

import android.content.ContentResolver
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import app.absentmotion.model.Scene
import app.absentmotion.model.exifOrientation
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class DeskExifTest {
    private fun photo(tag: Int): File {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 20) for (x in 0 until 40) image.setRGB(x, y, if (x < 20) Color.RED.rgb else Color.BLUE.rgb)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 34, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, tag.toByte(), 0, 0, 0, 0, 0, 0, 0)
        return File.createTempFile("exif$tag-", ".jpg").also { it.writeBytes(jpeg.copyOfRange(0, 2) + (if (tag == 1) ByteArray(0) else exif) + jpeg.copyOfRange(2, jpeg.size)); it.deleteOnExit() }
    }

    private fun colour(p: Int): String {
        val r = p shr 16 and 255
        val b = p and 255
        return when { r > 180 && b < 80 -> "red"; b > 180 && r < 80 -> "blue"; r < 40 && b < 40 -> "black"; else -> "other" }
    }

    private fun drawn(tag: Int): List<String> {
        val file = photo(tag)
        val turn = exifOrientation(ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)).first
        val scene = Scene.parse(
            """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="1000" fillType="media" mediaFillMode="stretch" s=".rect" fillImage="${file.toURI()}">
            <transform><location value="100,100,0"/><rotation value="$turn"/></transform><property name="size" type="vec2" value="100,50"/></shape></scene>""",
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            val image = engine.render(0, 200, 200)
            return listOf(100 to 25, 175 to 100, 100 to 175, 25 to 100).map { (x, y) -> colour(image.getRGB(x, y)) }
        }
    }

    @Test fun theTagIsReadAndTheSizeStaysAsStored() {
        for (tag in 1..8) {
            val file = photo(tag)
            assertEquals(tag, ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
            assertEquals(tag, file.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) })
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            assertEquals(40 to 20, bounds.outWidth to bounds.outHeight)
        }
    }

    @Test fun taggedPhotosDrawUprightWithTheRecordedTurnLikeOnThePhone() {
        val across = listOf("black", "blue", "black", "red")
        val flipped = listOf("black", "red", "black", "blue")
        val down = listOf("red", "black", "blue", "black")
        val up = listOf("blue", "black", "red", "black")
        assertEquals("tag 1", across, drawn(1))
        assertEquals("tag 2", flipped, drawn(2))
        assertEquals("tag 3", flipped, drawn(3))
        assertEquals("tag 4", across, drawn(4))
        assertEquals("tag 5", down, drawn(5))
        assertEquals("tag 6", down, drawn(6))
        assertEquals("tag 7", up, drawn(7))
        assertEquals("tag 8", up, drawn(8))
    }

    @Test fun mediaThumbnailsStandUpright() {
        val raw = ImageIO.read(photo(1))
        fun corners(tag: Int) = ContentResolver.upright(raw, tag).let { listOf(it.width to it.height, colour(it.getRGB(2, 2)), colour(it.getRGB(it.width - 3, it.height - 3))) }
        assertEquals(listOf(40 to 20, "red", "blue"), corners(1))
        assertEquals(listOf(40 to 20, "blue", "red"), corners(2))
        assertEquals(listOf(40 to 20, "blue", "red"), corners(3))
        assertEquals(listOf(20 to 40, "red", "blue"), corners(6))
        assertEquals(listOf(20 to 40, "blue", "red"), corners(8))
        assertEquals(listOf(20 to 40, "red", "blue"), corners(5))
        assertEquals(listOf(20 to 40, "blue", "red"), corners(7))
    }
}
