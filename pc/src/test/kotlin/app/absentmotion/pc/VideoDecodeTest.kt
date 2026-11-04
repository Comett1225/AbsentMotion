package app.absentmotion.pc

import app.absentmotion.desk.DeskMedia
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.FfmpegVideoSource
import app.absentmotion.pc.engine.NativeVideoSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class VideoDecodeTest {
    private val out = File("build/video-check").also { it.mkdirs() }

    internal fun sceneXml(file: File, w: Int, h: Int) = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Video" width="$w" height="$h" bgcolor="#ff000000" totalTime="4999" fps="30">
  <media uri="${file.absolutePath}" type="video/mp4" orientation="0" duration="5000000" width="$w" height="$h" />
  <shape id="1" startTime="0" endTime="4999" fillType="media" fillVideo="${file.absolutePath}" outTime="5000" mediaFillMode="fill" s=".rect">
    <transform><location value="${w / 2f},${h / 2f},0" /></transform>
    <property name="size" type="vec2" value="${w / 2f},${h / 2f}" />
  </shape>
</scene>"""

    private fun rssMb(): Long = if (app.absentmotion.desk.DeskOs.windows)
        ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id ${ProcessHandle.current().pid()}).WorkingSet64").start().inputStream.bufferedReader().readText().trim().toLong() / 1_048_576
    else ProcessBuilder("ps", "-o", "rss=", "-p", "${ProcessHandle.current().pid()}").start().inputStream.bufferedReader().readText().trim().toLong() / 1024

    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6

    private fun stats(values: List<Double>): String {
        val s = values.sorted()
        return "median=%.1fms p95=%.1fms max=%.1fms".format(s[s.size / 2], s[(s.size * 95 / 100).coerceAtMost(s.size - 1)], s.last())
    }

    private class Preview(val handle: Long, val w: Int, val h: Int, val sceneW: Int) {
        val bitmap = org.jetbrains.skia.Bitmap().apply {
            allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL, org.jetbrains.skia.ColorSpace.sRGB))
        }
        fun render(timeMs: Double): Boolean {
            val px = bitmap.peekPixels()!!
            return DesktopNative.renderPreview(handle, timeMs, w, h, px.addr, px.rowBytes.toLong(), w.toFloat() / sceneW, 0f, 0f, 0xff000000.toInt(), 0, 0, false, 1, 0f, 0, null, floatArrayOf(), 1f)
        }
    }

    internal fun psnr(handle: Long, w: Int, h: Int, timeMs: Double, file: File, k: Int, png: String): Double {
        val rgba = DesktopNative.still(handle, timeMs, w, h, true)!!
        val ours = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        ours.setRGB(0, 0, w, h, IntArray(w * h) { i -> ((rgba[i * 4].toInt() and 255) shl 16) or ((rgba[i * 4 + 1].toInt() and 255) shl 8) or (rgba[i * 4 + 2].toInt() and 255) }, 0, w)
        ImageIO.write(ours, "png", out.resolve(png))
        val ref = ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-i", file.absolutePath, "-vf", "select=eq(n\\,$k),scale=in_color_matrix=bt709:in_range=tv:flags=accurate_rnd+full_chroma_int+bicubic",
            "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-").start().inputStream.readBytes()
        assertEquals(w * h * 3, ref.size)
        var sum = 0.0
        for (i in 0 until w * h) for (c in 0 until 3) { val d = (rgba[i * 4 + c].toInt() and 255) - (ref[i * 3 + c].toInt() and 255); sum += d * d }
        return 10 * log10(255.0 * 255 / (sum / (w * h * 3)))
    }

    @Test fun editListStartsAtCut() {
        val original = File("../testdata/media/test-video.mp4")
        val cut = out.resolve("cut.mp4")
        check(ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-y", "-ss", "0.5", "-i", original.path, "-c", "copy", cut.path).start().waitFor() == 0)
        val source = NativeVideoSource(cut)
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, sceneXml(cut, 1280, 720))
            for ((at, k) in listOf(0 to 15, 1000 to 45)) {
                assertEquals(1, source.show(handle, cut.absolutePath, frameUs(k - 15), true))
                val psnr = psnr(handle, 1280, 720, at.toDouble(), original, k, "cut-$at.png")
                println("[cut] t=${at}ms vs original frame $k PSNR=%.2f dB".format(psnr))
                assertTrue(psnr > 35)
            }
        } finally {
            source.close()
            DesktopNative.destroy(handle)
        }
    }

    internal fun frameUs(k: Int) = (k * 1_000_000L + 29) / 30 + 1000

    private fun bench(file: File, label: String) {
        val probe = NativeVideoSource(file)
        val (w, h) = probe.info.width to probe.info.height
        println("[$label] ${file.name} ${w}x$h fps=${probe.info.fps} durationMs=${probe.info.durationMs} rotation=${probe.orientation} audio=${probe.hasAudio} hardware=${probe.hardware}")
        probe.close()
        val uri = file.absolutePath
        val handle = DesktopNative.create()
        try {
            assertEquals(null, DesktopNative.setScene(handle, sceneXml(file, w, h)))
            val preview = Preview(handle, 1280, 1280 * h / w, w)
            val random = Random(7)
            val seeks = List(50) { random.nextLong(0, 4_900_000) }
            val rss0 = rssMb()

            var t = System.nanoTime()
            val native = NativeVideoSource(file)
            val openMs = ms(t)
            assertEquals(1, native.show(handle, uri, 0, true))
            val firstMs = ms(t)
            assertTrue(preview.render(0.0))
            val seekNative = seeks.map { at -> val s = System.nanoTime(); assertEquals(1, native.show(handle, uri, at, true)); ms(s) }
            t = System.nanoTime()
            for (k in 0 until 150) { assertEquals(1, native.show(handle, uri, frameUs(k), true)); assertTrue(preview.render(k * 1000.0 / 30)) }
            val seqNative = 150 / (ms(t) / 1000)
            val rssNative = rssMb()
            var stale = 0
            var waitMax = 0.0
            t = System.nanoTime()
            for (k in 0 until 150) {
                val due = t + k * 1_000_000_000L / 30
                while (System.nanoTime() < due) Thread.sleep(0, 200_000)
                val s = System.nanoTime()
                if (native.show(handle, uri, frameUs(k), false) != 1) stale++
                waitMax = maxOf(waitMax, ms(s))
                preview.render(k * 1000.0 / 30)
            }
            println("[$label] NATIVE open=%.1fms firstFrame=%.1fms seek(50 random) ${stats(seekNative)} sequential+preview=%.1f fps playback30: stale=$stale/150 maxShowMs=%.2f rss=${rss0}->${rssNative}MB".format(openMs, firstMs, seqNative, waitMax))

            for (k in listOf(15, 75, 140)) {
                assertEquals(1, native.show(handle, uri, frameUs(k), true))
                val psnr = psnr(handle, w, h, k * 1000.0 / 30, file, k, "${label}-native-$k.png")
                println("[$label] frame $k PSNR vs ffmpeg = %.2f dB".format(psnr))
                assertTrue("frame $k colours differ from ffmpeg (PSNR $psnr)", psnr > 35)
            }
            native.close()

            t = System.nanoTime()
            val old = FfmpegVideoSource(file, DeskMedia.ffmpeg, DeskMedia.ffprobe)
            fun oldShow(us: Long) = old.frame(us / 1000)!!.let { DesktopNative.setFrame(handle, uri, it.width, it.height, it.rgba) }
            oldShow(0)
            val oldFirst = ms(t)
            val seekOld = seeks.map { at -> val s = System.nanoTime(); oldShow(at); ms(s) }
            t = System.nanoTime()
            for (k in 0 until 150) { oldShow(frameUs(k)); preview.render(k * 1000.0 / 30) }
            val seqOld = 150 / (ms(t) / 1000)
            old.close()
            println("[$label] FFMPEG-PIPE firstFrame=%.1fms seek(50 random) ${stats(seekOld)} sequential+preview=%.1f fps rss=${rssMb()}MB".format(oldFirst, seqOld))
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    @Test fun engineThreadPlayback() {
        val file = File("../testdata/test4k_hevc.mp4").takeIf { it.isFile } ?: File("../testdata/media/test-video.mp4")
        val probe = NativeVideoSource(file)
        val (w, h) = probe.info.width to probe.info.height
        probe.close()
        val engine = app.absentmotion.engine.EngineThread(0xff000000.toInt())
        try {
            engine.setScene(app.absentmotion.model.Scene.parse(sceneXml(file, w, h)))
            engine.attachDesktop(1, androidx.compose.ui.unit.IntSize(1280, 1280 * h / w))
            kotlinx.coroutines.runBlocking { engine.awaitMedia() }
            fun showAt(timeMs: Int): Double {
                val s = System.nanoTime()
                val serial = engine.setView(app.absentmotion.engine.PreviewView(timeMs, 1280f / w, 0f, 0f, handles = 0))
                while (engine.rendered.value.serial != serial) Thread.sleep(0, 100_000)
                return ms(s)
            }
            showAt(0)
            Thread.sleep(300)
            val start = System.nanoTime()
            val waits = (0 until 150).map { k ->
                val due = start + k * 1_000_000_000L / 30
                while (System.nanoTime() < due) Thread.sleep(0, 200_000)
                showAt(k * 1000 / 30)
            }
            val random = Random(3)
            val scrubs = List(30) { showAt(random.nextInt(0, 4900)) }
            Thread.sleep(500)
            val centre = engine.pixelAt(0.5f, 0.5f)
            println("[engine] ${file.name} playback setView->rendered ${stats(waits.drop(1))} scrub ${stats(scrubs)} renders=${engine.renders} centre=${centre?.let { Integer.toHexString(it) }}")
            assertTrue("video not drawn", centre != null && centre and 0xffffff != 0)
        } finally {
            engine.close()
        }
    }

    @Test fun rotationMatchesPhone() {
        val rotated = out.resolve("rotated.mp4")
        check(ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-y", "-display_rotation:v", "90", "-i", "../testdata/media/test-video.mp4", "-c", "copy", rotated.path).start().waitFor() == 0)
        val source = NativeVideoSource(rotated)
        assertEquals(270, source.orientation)
        assertEquals(1280, source.info.width)
        source.close()
        assertEquals("270", DeskMedia.probe(rotated)["rotation"])
    }

    @Test fun hd() = bench(File("../testdata/media/test-video.mp4"), "720p-h264")

    @Test fun uhd() {
        val file = File("../testdata/test4k_hevc.mp4")
        assumeTrue(file.isFile)
        bench(file, "4k-hevc")
    }

    private val av1Dir = out.resolve("av1")

    private fun av1Clip(name: String, size: String, format: String): File {
        val file = av1Dir.resolve("$name.mp4")
        if (!file.isFile) {
            av1Dir.mkdirs()
            val args = listOf(DeskMedia.ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=$size:rate=30:duration=4", "-pix_fmt", format, "-c:v", "libsvtav1", "-preset", "10", "-g", "30", file.path)
            check(ProcessBuilder(args).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0)
        }
        return file
    }

    fun av1Probe(file: File): Pair<Boolean, Double> {
        val source = NativeVideoSource(file)
        val (w, h) = source.info.width to source.info.height
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, sceneXml(file, w, h))
            assertEquals(1, source.show(handle, file.absolutePath, frameUs(15), true))
            return source.hardware to psnr(handle, w, h, 15 * 1000.0 / 30, file, 15, "${file.nameWithoutExtension}.png")
        } finally {
            source.close()
            DesktopNative.destroy(handle)
        }
    }

    fun av1Speed(file: File): Pair<Boolean, Double> {
        val source = NativeVideoSource(file)
        val (w, h) = source.info.width to source.info.height
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, sceneXml(file, w, h))
            assertEquals(1, source.show(handle, file.absolutePath, 0, true))
            val start = System.nanoTime()
            for (k in 1 until 120) assertEquals(1, source.show(handle, file.absolutePath, frameUs(k), true))
            return source.hardware to 119 / (ms(start) / 1000)
        } finally {
            source.close()
            DesktopNative.destroy(handle)
        }
    }

    private fun av1Child(mode: String, file: File, env: Map<String, String>): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "-Dam.native=${System.getProperty("am.native")}", "-Dam.ffmpeg=${DeskMedia.ffmpeg}", "-Dam.ffprobe=${DeskMedia.ffprobe}",
            "-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE", "-Dskiko.data.path=${System.getProperty("skiko.data.path")}", "-Dam.assets=${System.getProperty("am.assets")}", "app.absentmotion.pc.Av1Child", mode, file.absolutePath)
        builder.environment().putAll(env)
        val process = builder.redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(text, 0, process.waitFor())
        return text.lines().last { it.startsWith("RESULT ") }.removePrefix("RESULT ")
    }

    private fun av1Hardware(): Boolean {
        if (System.getProperty("os.name") != "Mac OS X") return false
        val brand = ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start().inputStream.bufferedReader().readText()
        return Regex("Apple M(\\d+)").find(brand)?.groupValues?.get(1)?.toInt()?.let { it >= 3 } == true
    }

    @Test fun av1UsesHardware() {
        assumeTrue(av1Hardware())
        for ((name, size, format) in listOf(Triple("av1-1080p8", "1920x1080", "yuv420p"), Triple("av1-1080p10", "1920x1080", "yuv420p10le"))) {
            val (hardware, psnr) = av1Probe(av1Clip(name, size, format))
            println("[av1] $name hardware=$hardware PSNR vs libdav1d ffmpeg = %.2f dB".format(psnr))
            assertTrue("$name did not use the hardware decoder", hardware)
            assertTrue("$name colours differ (PSNR $psnr)", psnr > 35)
        }
    }

    @Test fun av1FallsBackToSoftware() {
        for ((name, size, format) in listOf(Triple("av1-1080p8", "1920x1080", "yuv420p"), Triple("av1-1080p10", "1920x1080", "yuv420p10le"))) {
            val (hardware, psnr) = av1Child("psnr", av1Clip(name, size, format), mapOf("AM_VIDEO_AV1_REFUSE" to "1")).split(' ').let { it[0].toBoolean() to it[1].toDouble() }
            println("[av1] $name forced fallback hardware=$hardware PSNR = %.2f dB".format(psnr))
            assertTrue(!hardware)
            assertTrue("$name fallback colours differ (PSNR $psnr)", psnr > 35)
        }
    }

    @Test fun av1Speed4k() {
        assumeTrue(av1Hardware())
        val file = av1Clip("av1-4k8", "3840x2160", "yuv420p")
        val (hw, hwFps) = av1Child("speed", file, mapOf()).split(' ').let { it[0].toBoolean() to it[1].toDouble() }
        val (sw, swFps) = av1Child("speed", file, mapOf("AM_VIDEO_SOFTWARE" to "1")).split(' ').let { it[0].toBoolean() to it[1].toDouble() }
        println("[av1] 4k8 hardware=$hw %.1f fps, software=$sw %.1f fps".format(hwFps, swFps))
        assertTrue(hw && !sw)
    }
}

object Av1Child {
    @JvmStatic fun main(args: Array<String>) {
        val test = VideoDecodeTest()
        val file = File(args[1])
        val (hardware, value) = if (args[0] == "speed") test.av1Speed(file) else test.av1Probe(file)
        println("RESULT $hardware $value")
        kotlin.system.exitProcess(0)
    }
}
