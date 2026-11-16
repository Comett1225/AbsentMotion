package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.Judge
import app.absentmotion.R
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskProbe
import app.absentmotion.engine.EngineThread
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.NativeVideoSource
import app.absentmotion.platform.PlatformFeatures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FfampFormatsTest {
    private val dir = File("build/ffamp-formats").absoluteFile.also { it.mkdirs() }
    private val context = Context()
    private val source = File("../testdata/media/test-video.mp4").absoluteFile

    @Before fun withFfamp() {
        assumeTrue(PlatformFeatures.ffamp && (DeskOs.mac || MediaEngine.ffamp))
        DesktopNative.hashCode()
    }

    private fun made(name: String, vararg args: String): File? {
        val file = File(dir, name)
        val process = ProcessBuilder(listOf(Judge.ffmpeg, "-nostdin", "-y", "-v", "error") + args + file.path).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return file.takeIf { process.waitFor() == 0 && it.length() > 0 }
    }

    private fun scene(file: File, w: Int, h: Int) = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Formats" width="$w" height="$h" bgcolor="#ff00ff00" totalTime="999" fps="30">
  <media uri="${file.absolutePath}" type="video/mp4" orientation="0" duration="1000000" width="$w" height="$h" />
  <shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="${file.absolutePath}" outTime="1000" mediaFillMode="fill" s=".rect">
    <transform><location value="${w / 2f},${h / 2f},0" /></transform>
    <property name="size" type="vec2" value="${w / 2f},${h / 2f}" />
  </shape>
</scene>"""

    private fun preview(file: File, w: Int, h: Int, ms: Int = 300): ByteArray {
        val native = NativeVideoSource(file)
        val handle = DesktopNative.create()
        try {
            assertEquals(file.name, MediaEngine.ffamp, native.ffamp)
            assertEquals(null, DesktopNative.setScene(handle, scene(file, w, h)))
            assertEquals(1, native.show(handle, file.absolutePath, ms * 1000L, true))
            return DesktopNative.still(handle, ms.toDouble(), w, h, true)!!
        } finally {
            native.close()
            DesktopNative.destroy(handle)
        }
    }

    private fun exported(file: File, w: Int, h: Int, ms: Int = 300): ByteArray = runBlocking {
        val engine = EngineThread(0xff000000.toInt(), context)
        try { engine.exportSession(scene(file, w, h)) { still(ms, w, h) }!! } finally { engine.close() }
    }

    private fun apart(a: ByteArray, b: ByteArray): Double = a.indices.filter { it % 4 != 3 }.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)) }.toDouble() / (a.size / 4 * 3)

    private fun playsDivx(file: File, w: Int, h: Int, frames: Int): String {
        assertEquals(file.name, null, MediaEngine.current.trouble(file))
        val probe = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(file)))
        assertEquals(file.name, w to h, probe.video!!.width to probe.video!!.height)
        assertEquals(file.name, frames, VideoSources.timestamps(context, Uri.fromFile(file).toString()).size)
        val shown = listOf(100, 420, 740, 980).map { ms ->
            val still = preview(file, w, h, ms)
            val (r, g, b) = pixel(still, w, w / 2, h / 2)
            assertTrue("${file.name} at $ms ms: the picture is drawn ($r $g $b)", !(r < 30 && g > 220 && b < 30))
            val out = exported(file, w, h, ms)
            assertTrue("${file.name} at $ms ms: export and preview show the same frame (${apart(still, out)})", apart(still, out) < 3)
            still
        }
        val moves = (1 until shown.size).map { apart(shown[it - 1], shown[it]) }
        assertTrue("${file.name}: each frame moves on from the one before ($moves)", moves.all { it > 2 })
        return "${file.name} (frames move by %.1f to %.1f)".format(moves.min(), moves.max())
    }

    @Test fun divxAndXvidFilesWithBFramesPlayInPreviewAndExport() {
        assumeTrue("this build has no Xvid", FfampNative.divxDecoder())
        val fixtures = File("../third_party/FFamp/tests/fixtures/asp").absoluteFile
        val checked = mutableListOf(
            playsDivx(File(fixtures, "lavc_packed.avi"), 160, 120, 30),
            playsDivx(File(fixtures, "xvid_gmc.avi"), 160, 120, 30),
            playsDivx(File(fixtures, "xvid_interlaced.avi"), 176, 144, 30),
        )
        val clip = listOf("-i", source.path, "-t", "1", "-vf", "scale=320:180")
        val film = made("divx.avi", *clip.toTypedArray(), "-c:v", "mpeg4", "-bf", "2", "-flags", "+qpel+mv4", "-mbd", "2", "-vtag", "DX50", "-c:a", "libmp3lame")!!
        checked += playsDivx(film, 320, 180, 30)
        heard(film).let { (ms, loud) -> assertTrue("${film.name}: $ms ms", abs(ms - 1000) < 150 && loud) }
        val judge = ProcessBuilder(Judge.ffmpeg, "-nostdin", "-v", "error", "-i", film.path, "-vf", "select=eq(n\\,12)", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgba", "-").start()
        val theirs = judge.inputStream.readBytes()
        assertEquals(0, judge.waitFor())
        val ours = preview(film, 320, 180, 420)
        assertTrue("the preview of frame 12 is FFmpeg's frame 12 (${apart(ours, theirs)})", theirs.size == ours.size && apart(ours, theirs) < 4)
        println("divx checked: ${checked.joinToString()}; frame 12 of ${film.name} is %.2f levels from FFmpeg's".format(apart(ours, theirs)))
    }

    private fun pixel(pixels: ByteArray, w: Int, x: Int, y: Int) = Triple(pixels[(y * w + x) * 4].toInt() and 255, pixels[(y * w + x) * 4 + 1].toInt() and 255, pixels[(y * w + x) * 4 + 2].toInt() and 255)

    private fun heard(file: File): Pair<Int, Boolean> = PcmDecoder(context, Uri.fromFile(file).toString()).use { decoder ->
        var frames = 0
        var loud = false
        var rate = 1
        while (true) {
            val chunk = decoder.next() ?: break
            rate = chunk.rate
            frames += chunk.frames
            loud = loud || (0 until chunk.frames * 2).any { abs(chunk.pcm[it].toInt()) > 300 }
        }
        frames * 1000 / rate to loud
    }

    @Test fun olderContainersAndCodecsOpenPlayAndExport() {
        val clip = listOf("-i", source.path, "-t", "1", "-vf", "scale=320:180")
        val pictures = listOfNotNull(
            made("simple.avi", *clip.toTypedArray(), "-c:v", "mpeg4", "-bf", "0", "-c:a", "libmp3lame"),
            made("h264.avi", *clip.toTypedArray(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "ac3"),
            made("ac3.mp4", *clip.toTypedArray(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "ac3"),
            made("ac3.mkv", *clip.toTypedArray(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "ac3"),
            made("jpeg.mov", *clip.toTypedArray(), "-c:v", "mjpeg", "-pix_fmt", "yuvj420p", "-an"),
        )
        assertTrue("${pictures.map { it.name }}", pictures.size >= 4)
        for (file in pictures) {
            assertTrue(file.name, FfampFirst.reads(file))
            val probe = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(file)))
            assertEquals(file.name, 320 to 180, probe.video!!.width to probe.video!!.height)
            assertTrue(file.name, abs(probe.durationUs - 1_000_000) < 120_000)
            for (pixels in listOf(preview(file, 320, 180), exported(file, 320, 180))) {
                val (r, g, b) = pixel(pixels, 320, 160, 90)
                assertTrue("${file.name}: the picture is drawn ($r $g $b)", !(r < 30 && g > 220 && b < 30))
            }
            assertTrue(file.name, VideoSources.timestamps(context, Uri.fromFile(file).toString()).size in 28..31)
            if (probe.audio != null) heard(file).let { (ms, loud) -> assertTrue("${file.name}: $ms ms", abs(ms - 1000) < 150 && loud) }
        }
        val sounds = listOfNotNull(
            made("pcm.caf", "-i", source.path, "-t", "1", "-vn", "-c:a", "pcm_s16le"),
            made("aac.caf", "-i", source.path, "-t", "1", "-vn", "-c:a", "aac"),
            made("sound.ac3", "-i", source.path, "-t", "1", "-vn", "-c:a", "ac3"),
        ) + listOf("amr_nb.amr", "amr_wb.amr", "amr_nb.3gp", "caf_aac.caf", "ac3_51.ac3", "ac3_21.ac3").map { File("../third_party/FFamp/tests/fixtures/formats", it).absoluteFile }.filter { it.isFile }
        assertTrue("${sounds.map { it.name }}", sounds.size >= 7)
        for (file in sounds) {
            assertTrue(file.name, FfampFirst.reads(file))
            val probe = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(file)))
            assertTrue(file.name, probe.audio != null && probe.video == null)
            heard(file).let { (ms, loud) -> assertTrue("${file.name}: $ms ms against ${probe.durationUs / 1000}", abs(ms - probe.durationUs / 1000) < 150 && ms > 200 && loud) }
        }
        println("formats checked: ${(pictures + sounds).joinToString { it.name }}")
    }

    @Test fun alphaVideoKeepsItsTransparencyInPreviewAndExport() {
        val picture = "testsrc2=s=320x180:r=30:d=1,format=yuva444p10le,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='if(lt(X,160),0,1023)'"
        val small = "testsrc2=s=320x180:r=30:d=1,format=yuva420p,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='if(lt(X,160),0,255)'"
        val files = listOfNotNull(
            made("alpha.mov", "-f", "lavfi", "-i", picture, "-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le"),
            made("alpha.webm", "-f", "lavfi", "-i", small, "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0"),
        )
        assertTrue("${files.map { it.name }}", files.isNotEmpty())
        for (file in files) for ((name, pixels) in listOf("preview" to preview(file, 320, 180), "export" to exported(file, 320, 180))) {
            val (r, g, b) = pixel(pixels, 320, 60, 90)
            assertTrue("${file.name} $name: the clear half shows the green behind it ($r $g $b)", r < 40 && g > 215 && b < 40)
            val (r2, g2, b2) = pixel(pixels, 320, 250, 40)
            assertTrue("${file.name} $name: the solid half shows the video ($r2 $g2 $b2)", !(r2 < 40 && g2 > 215 && b2 < 40))
        }
        println("alpha checked: ${files.joinToString { it.name }}")
    }

    @Test fun oldFormatsPlayOrSayWhy() {
        val clip = listOf("-i", source.path, "-t", "1", "-vf", "scale=320:180")
        for ((name, video, sound) in listOf(Triple("old.wmv", "wmv2", "wmav2"), Triple("older.asf", "wmv1", "wmav1"), Triple("divx3.avi", "msmpeg4", "libmp3lame"))) {
            val file = made(name, *clip.toTypedArray(), "-c:v", video, "-c:a", sound) ?: continue
            assertTrue(name, FfampFirst.reads(file))
            assertEquals(null, MediaEngine.current.trouble(file))
            assertEquals(null, app.absentmotion.platform.mediaTrouble(context, Uri.fromFile(file)))
            val probe = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(file)))
            assertTrue(name, probe.video != null && probe.audio != null)
            assertTrue(name, heard(file).second)
            NativeVideoSource(file).close()
        }
        val film = made("advanced.avi", *clip.toTypedArray(), "-c:v", "mpeg4", "-bf", "2", "-c:a", "libmp3lame")!!
        assertEquals(if (FfampNative.divxDecoder()) null else R.string.media_error_mpeg4_asp, FfampEngine.trouble(film))
        assertEquals(null, MediaEngine.current.trouble(film))
        val probe = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(film)))
        assertTrue(probe.video != null && probe.audio != null)
        assertTrue(heard(film).second)
        NativeVideoSource(film).use { assertEquals(MediaEngine.ffamp && FfampNative.divxDecoder(), it.ffamp) }
        assertEquals(null, MediaEngine.current.trouble(source))
    }
}
