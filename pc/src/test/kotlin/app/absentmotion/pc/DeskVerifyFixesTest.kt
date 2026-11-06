package app.absentmotion.pc

import app.absentmotion.desk.DeskGallery
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.export.frameBufferCount
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeskVerifyFixesTest {
    @Test fun bigFramesUseFewerBuffers() {
        assertEquals(4, frameBufferCount(1920, 1080))
        assertEquals(2, frameBufferCount(2160, 8192))
    }

    @Test fun windowsOpenAvoidsCmd() {
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", "C:\\Q&A\\a.msi"), DeskOs.openCommand(false, true, "C:\\Q&A\\a.msi"))
    }

    @Test fun idsStayDistinctPerPath() {
        val a = DeskGallery.idOf(File("/tmp/am-id-a.png"))
        val b = DeskGallery.idOf(File("/tmp/am-id-b.png"))
        assertNotEquals(a, b)
        assertEquals(a, DeskGallery.idOf(File("/tmp/am-id-a.png")))
    }

    @Test fun frameTimeIgnoresDefaultLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try { assertEquals("1.500", "%.3f".format(Locale.ROOT, 1.5)); DeskMedia.frame(File("/nonexistent"), 1500, 64) } finally { Locale.setDefault(saved) }
    }
}
