package app.absentmotion.pc

import app.absentmotion.desk.DeskOpen
import app.absentmotion.desk.DeskOs
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.beating
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskSweepTest {
    private fun folder(name: String) = File("build/sweep/$name").also { it.deleteRecursively(); it.mkdirs() }

    @Test fun onlyTheAppsOwnToolsAreStoppedOnQuit() {
        for (tool in listOf("/opt/x/ffmpeg", "C:\\app\\resources\\ffmpeg.exe".replace('\\', File.separatorChar), "ffprobe", "/data/tools/yt-dlp_macos", "yt-dlp.exe", "yt-dlp_linux_aarch64")) assertTrue(tool, DeskOs.isTool(tool))
        for (other in listOf("/bin/sh", "AbsentMotionSetup.exe", "powershell.exe", "/usr/bin/ffmpegthumbnailer", "msiexec.exe", "AbsentMotion")) assertFalse(other, DeskOs.isTool(other))
    }

    @Test fun theExportWatchdogIsFedWhileTheFileIsFinished() = runBlocking {
        val beats = AtomicInteger()
        val seen = ArrayList<Float>()
        val result = beating({ beats.incrementAndGet(); synchronized(seen) { seen += it } }, 0.98f, everyMs = 10) { delay(200); "done" }
        assertEquals("done", result)
        assertTrue("beats ${beats.get()}", beats.get() >= 5)
        assertTrue(synchronized(seen) { seen.all { it == 0.98f } })
        val after = beats.get()
        delay(60)
        assertEquals(after, beats.get())
    }

    @Test fun bigPicturesAreReadSmallForThumbnails() {
        val dir = folder("thumbs")
        val big = File(dir, "big.png")
        val picture = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
        picture.createGraphics().apply { color = java.awt.Color(200, 40, 40); fillRect(0, 0, 1200, 1200); color = java.awt.Color(40, 40, 200); fillRect(1200, 0, 1200, 1200); dispose() }
        ImageIO.write(picture, "png", big)
        val thumb = android.graphics.BitmapFactory.decodeThumb(big, 100)!!
        assertTrue("width ${thumb.width}", thumb.width in 200..400)
        assertEquals(thumb.width / 2, thumb.height)
        assertEquals(200, (thumb.getPixel(thumb.width / 4, thumb.height / 2) shr 16) and 0xff)
        assertEquals(200, thumb.getPixel(thumb.width * 3 / 4, thumb.height / 2) and 0xff)
        val small = File(dir, "small.png")
        ImageIO.write(BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB), "png", small)
        assertEquals(120, android.graphics.BitmapFactory.decodeThumb(small, 100)!!.width)
        assertNull(android.graphics.BitmapFactory.decodeThumb(File(dir, "missing.png"), 100))
    }

    @Test fun aStrayXflFileDoesNotPackItsWholeFolder() {
        val dir = folder("xfl")
        val stray = File(dir, "stray.xfl").apply { writeText("PROXY-CS5") }
        File(dir, "holiday.mp4").writeBytes(ByteArray(1024))
        assertNull(DeskOpen.xflZip(android.net.Uri.fromFile(stray)))
        File(dir, "DOMDocument.xml").writeText("<DOMDocument/>")
        val zip = DeskOpen.xflZip(android.net.Uri.fromFile(stray))!!
        try {
            java.util.zip.ZipFile(zip).use { assertTrue(it.getEntry("DOMDocument.xml") != null) }
        } finally {
            zip.delete()
        }
    }

    @Test fun theCaptionProcessorCheckIsInTheEngine() {
        app.absentmotion.pc.engine.DesktopNative.hashCode()
        val check = app.absentmotion.ai.DeskAiNative::class.java.getDeclaredMethod("nativeCaptionCpu").apply { isAccessible = true }
        assertEquals(true, check.invoke(app.absentmotion.ai.DeskAiNative))
        assertTrue(app.absentmotion.ai.DeskAiNative.captionCpu())
    }

    @Test fun microsoftsSoftwareEncodersComeAfterTheBundledOnes() {
        assertFalse(DeskEncoders.hardware("h264_mf"))
        assertFalse(DeskEncoders.hardware("hevc_mf"))
        assertFalse(DeskEncoders.hardware("libx264"))
        assertTrue(DeskEncoders.hardware("h264_nvenc"))
        assertTrue(DeskEncoders.hardware("h264_videotoolbox"))
    }
}
