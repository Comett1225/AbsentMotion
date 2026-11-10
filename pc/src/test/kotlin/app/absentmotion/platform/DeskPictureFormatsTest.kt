package app.absentmotion.platform

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskPictureFormatsTest {
    @Test fun webpPicturesDecodeAndReportTheirSize() {
        val file = File(requireNotNull(System.getProperty("am.assets")), "effects/thumb").listFiles { f -> f.extension == "webp" }.orEmpty().minBy { it.name }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeFile(file.path, bounds))
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        assertEquals(bounds.outWidth, bitmap.width)
        assertEquals(bounds.outHeight, bitmap.height)
    }

    @Test fun pngBoundsComeFromTheHeader() {
        val picture = java.awt.image.BufferedImage(37, 21, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(picture, "png", it) }.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds))
        assertEquals(37 to 21, bounds.outWidth to bounds.outHeight)
        assertEquals(37, requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).width)
    }
}
