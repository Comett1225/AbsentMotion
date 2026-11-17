package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskProbe
import app.absentmotion.pc.VideoDecodeTest
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.NativeVideoSource
import app.absentmotion.platform.PlatformFeatures
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

class FfampEngineTest {
    private val dir = File("build/ffamp-test").absoluteFile.also { it.mkdirs() }
    private val context = Context()
    private val source = File("../testdata/media/test-video.mp4").absoluteFile

    @Before fun withFfamp() {
        assumeTrue(PlatformFeatures.ffamp)
        DesktopNative.hashCode()
    }

    private fun ffmpeg(vararg args: String) {
        val process = ProcessBuilder(listOf(app.absentmotion.Judge.ffmpeg, "-nostdin", "-y", "-v", "error") + args).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { text }
    }

    private fun <T> withoutFfmpeg(work: () -> T): T {
        val names = listOf("am.ffmpeg", "am.ffprobe")
        val old = names.associateWith { System.getProperty(it) }
        names.forEach { System.setProperty(it, File(dir, "absent/${it.substringAfter('.')}").path) }
        try {
            assertTrue(runCatching { app.absentmotion.Judge.engine.probe(source) }.isFailure)
            assertTrue(runCatching { app.absentmotion.Judge.engine.playback(source, 0, 48000) }.isFailure)
            return work()
        } finally {
            old.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }

    private fun psnr(a: ByteArray, b: ByteArray): Double {
        assertEquals(a.size, b.size)
        var sum = 0.0
        for (i in a.indices) { val d = (a[i].toInt() and 255) - (b[i].toInt() and 255); sum += d * d }
        return if (sum == 0.0) 99.0 else 10 * log10(255.0 * 255 / (sum / a.size))
    }

    private fun floats(stream: MediaStream): FloatArray = stream.use { s ->
        val bytes = s.input.readBytes()
        FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    @Test fun carriesFfmpegAndFfampAndReadsWithTheChosenOne() {
        System.getProperty("am.mediaReader")?.let { assertEquals(it == "ffamp", MediaEngine.ffamp) }
        assertSame(if (MediaEngine.ffamp) FfampFirst else FfmpegEngine, MediaEngine.current)
        if (DeskOs.mac) {
            val linked = ProcessBuilder("otool", "-L", System.getProperty("am.native")).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
            for (name in listOf("libavformat", "libavcodec", "libavutil", "libswscale")) assertTrue("$name in\n$linked", name in linked)
        }
        assertTrue(when { DeskOs.mac -> "h264_videotoolbox"; DeskOs.windows -> "h264_mf"; else -> "libx264" } in FfampNative.encoders())
        NativeVideoSource(source, true).use { assertTrue(it.ffamp) }
        NativeVideoSource(source, false).use { assertTrue(!it.ffamp && it.info.width == 1280) }
    }

    @Test fun ffampHandsFilesItCannotReadToFfmpeg() {
        val odd = File(dir, "odd.nut").also { it.delete() }
        ffmpeg("-i", source.path, "-t", "1", "-c:v", "mpeg4", "-c:a", "pcm_s16le", odd.path)
        assertTrue(FfampNative.openProblem(odd.path) != null || FfampNative.probe(odd.path) == null)
        assertTrue(FfampFirst.reads(source) && !FfampFirst.reads(odd))
        assertEquals(FfampEngine.probe(source), FfampFirst.probe(source))
        val probe = DeskProbe.parse(JSONObject(FfampFirst.probe(odd)))
        assertEquals(1280 to 720, probe.video!!.width to probe.video!!.height)
        assertEquals(null, FfampFirst.trouble(odd))
        FfampFirst.videoSource(odd, 320).use { assertTrue(it.frame(500) != null) }
        assertTrue(floats(FfampFirst.pcm(odd, 0, 0, 48000, 2)).any { it != 0f })
        NativeVideoSource(odd, true).use { assertTrue(!it.ffamp && it.info.width == 1280) }
    }

    @Test fun importAndPlaybackRunWithoutFfmpeg() {
        assumeTrue(MediaEngine.ffamp)
        val file = File(dir, "alone.mp4")
        source.copyTo(file, overwrite = true)
        val uri = Uri.fromFile(file).toString()
        withoutFfmpeg {
            val probe = DeskProbe.of(file)
            assertEquals(1280 to 720, probe.video!!.width to probe.video!!.height)
            assertEquals("h264", probe.video!!.codec)
            assertEquals("aac", probe.audio!!.codec)
            assertEquals(5_000_000L, probe.durationUs)
            val facts = DeskMedia.probe(file)
            assertEquals(listOf("1280", "720", "5000", "1"), listOf("width", "height", "duration", "audio").map { facts[it] })
            val still = DeskMedia.frame(file, 1000, 320)!!
            assertEquals(320 to 180, still.width to still.height)
            val stamps = VideoSources.timestamps(context, uri)
            assertEquals(150, stamps.size)
            assertEquals(0L, stamps[0])
            PcmDecoder(context, uri).use { decoder ->
                decoder.seek(1_000_000)
                val chunk = decoder.next()!!
                assertEquals(1_000_000L, chunk.ptsUs)
                assertTrue((0 until chunk.frames * 2).any { chunk.pcm[it].toInt() != 0 })
            }
            MediaEngine.current.playback(file, 500, 48000).use { assertEquals(4800 * 4, it.input.readNBytes(4800 * 4).size) }
            runBlocking {
                FrameReader(context, uri).use { reader ->
                    assertEquals(3, reader.read(listOf(stamps[30], stamps[31], stamps[32])) { _, _ -> })
                    assertEquals(2, reader.read(listOf(stamps[149], stamps[3])) { _, _ -> })
                }
            }
            MediaEngine.current.videoSource(file, 320).use { video ->
                assertEquals(1280 to 720, video.info.width to video.info.height)
                assertEquals(5000L, video.info.durationMs)
                val first = video.frame(0)!!.rgba.copyOf()
                assertEquals(320 * 180 * 4, first.size)
                assertTrue(!first.contentEquals(video.frame(2000)!!.rgba))
                assertTrue(video.frameExact(stamps[60]) != null)
            }
            for (loop in listOf(false, true)) MediaEngine.current.play(file, 4900, 160, 90, loop).use { stream ->
                val started = System.nanoTime()
                var frames = 0
                while (frames < 5 && stream.input.readNBytes(160 * 90 * 4).size == 160 * 90 * 4) frames++
                assertEquals(if (loop) 5 else 3, frames)
                assertTrue((System.nanoTime() - started) / 1e9 in (if (loop) 0.1..1.0 else 0.03..1.0))
            }
            val native = NativeVideoSource(file)
            val handle = DesktopNative.create()
            try {
                assertTrue(native.ffamp && (native.hardware || !DeskOs.mac) && native.hasAudio)
                DesktopNative.setScene(handle, VideoDecodeTest().sceneXml(file, 1280, 720))
                assertEquals(1, native.show(handle, file.absolutePath, 2_000_000, true))
                val pixels = DesktopNative.still(handle, 2000.0, 1280, 720, true)!!
                assertTrue(pixels.indices.step(4001).any { (pixels[it].toInt() and 255) > 40 })
            } finally {
                native.close()
                DesktopNative.destroy(handle)
            }
        }
    }

    @Test fun describesFilesLikeFfprobe() {
        val rotated = File(dir, "turned.mp4")
        ffmpeg("-display_rotation:v", "90", "-i", source.path, "-c", "copy", rotated.path)
        for (file in listOf(source, rotated)) {
            val old = DeskProbe.parse(JSONObject(app.absentmotion.Judge.engine.probe(file)))
            val new = DeskProbe.parse(JSONObject(FfampEngine.probe(file)))
            assertEquals(old.streams.size, new.streams.size)
            assertTrue(abs(old.durationUs - new.durationUs) <= 1)
            for ((a, b) in old.streams.zip(new.streams)) {
                assertEquals(listOf(a.index, a.type, a.codec, a.profile, a.sampleRate, a.channels, a.width, a.height, a.rotation, a.transfer, a.range, a.primaries, a.startUs).toString(),
                    listOf(b.index, b.type, b.codec, b.profile, b.sampleRate, b.channels, b.width, b.height, b.rotation, b.transfer, b.range, b.primaries, b.startUs).toString())
                assertTrue(abs(a.fps - b.fps) < 1e-6 && abs(a.durationUs - b.durationUs) <= 1 && abs(a.bitRate - b.bitRate) <= a.bitRate / 100)
            }
            val facts = DeskMedia.probe(file)
            assertEquals(listOf("${old.video!!.width}", "${old.video!!.height}", "${old.video!!.rotation}", "${old.durationUs / 1000}", "1"),
                listOf(facts["width"], facts["height"], facts["rotation"] ?: "0", facts["duration"], facts["audio"]))
            for (stream in old.streams) {
                val a = app.absentmotion.Judge.engine.packets(file, stream.index)
                val b = FfampEngine.packets(file, stream.index)
                assertEquals(a.size, b.size)
                assertTrue(a.indices.all { abs(a[it].seconds - b[it].seconds) < 2e-6 && a[it].size == b[it].size && a[it].sync == b[it].sync })
            }
        }
    }

    @Test fun framesMatchFfmpeg() {
        for ((seconds, count) in listOf("0.000000" to 3, "1.999999" to 4, "4.933332" to 9)) {
            val old = app.absentmotion.Judge.engine.yuv(source, seconds, count, true).use { it.input.readBytes() }
            val new = FfampEngine.yuv(source, seconds, count, true).use { it.input.readBytes() }
            assertTrue(old.isNotEmpty())
            assertArrayEquals("frames from $seconds", old, new)
        }
        val one = app.absentmotion.Judge.engine.yuv(source, "2.500000", 1, false).use { it.input.readBytes() }
        assertArrayEquals(one, FfampEngine.yuv(source, "2.500000", 1, false).use { it.input.readBytes() })
        app.absentmotion.Judge.engine.videoSource(source, 640).use { old ->
            FfampEngine.videoSource(source, 640).use { new ->
                assertEquals(old.info, new.info)
                for (ms in listOf(0L, 33L, 1000L, 1033L, 4966L)) {
                    val a = old.frame(ms)!!
                    val b = new.frame(ms)!!
                    assertEquals(a.width to a.height, b.width to b.height)
                    val quality = psnr(a.rgba, b.rgba)
                    assertTrue("frame at $ms ms: $quality dB", quality > 38)
                }
                val quality = psnr(old.frameExact(2_033_334)!!.rgba, new.frameExact(2_033_334)!!.rgba)
                assertTrue("exact frame: $quality dB", quality > 38)
                assertTrue(old.frame(6000) == null && new.frame(6000) == null)
            }
        }
        for ((ms, side) in listOf(0L to 256, 2500L to 2000)) {
            val a = app.absentmotion.Judge.engine.still(source, ms, side)!!
            val b = FfampEngine.still(source, ms, side)!!
            assertEquals(a.width to a.height, b.width to b.height)
            fun bytes(image: java.awt.image.BufferedImage) = ByteArray(image.width * image.height * 3).also { out ->
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                for (i in pixels.indices) { out[i * 3] = (pixels[i] shr 16).toByte(); out[i * 3 + 1] = (pixels[i] shr 8).toByte(); out[i * 3 + 2] = pixels[i].toByte() }
            }
            val quality = psnr(bytes(a), bytes(b))
            assertTrue("still at $ms ms: $quality dB", quality > 35)
        }
        val turned = File(dir, "turned-still.mp4")
        ffmpeg("-display_rotation:v", "90", "-i", source.path, "-c", "copy", turned.path)
        val a = app.absentmotion.Judge.engine.still(turned, 1000, 300)!!
        val b = FfampEngine.still(turned, 1000, 300)!!
        assertEquals(a.width to a.height, b.width to b.height)
        assertTrue(b.height > b.width)
        val w = 90
        val h = 160
        val old = app.absentmotion.Judge.engine.play(turned, 4800, w, h, false).use { it.input.readNBytes(w * h * 4) }
        val new = FfampEngine.play(turned, 4800, w, h, false).use { it.input.readNBytes(w * h * 4) }
        val quality = psnr(old, new)
        assertTrue("played frame: $quality dB", quality > 30)
    }

    @Test fun soundMatchesFfmpeg() {
        val flac = File(dir, "tone.flac")
        ffmpeg("-f", "lavfi", "-i", "aevalsrc=0.2*sin(2*PI*660*t)|0.1*sin(2*PI*220*t):s=48000:d=2", flac.path)
        val wav = File(dir, "six.wav")
        ffmpeg("-f", "lavfi", "-i", "aevalsrc=0.1*sin(2*PI*200*t)|0.1*sin(2*PI*300*t)|0.1*sin(2*PI*400*t)|0.1*sin(2*PI*50*t)|0.1*sin(2*PI*500*t)|0.1*sin(2*PI*600*t):s=44100:d=1", "-channel_layout", "5.1", wav.path)
        for ((file, rate, channels) in listOf(Triple(flac, 48000, 2), Triple(wav, 44100, 6))) {
            for ((before, after) in listOf(0L to 0L, 0L to 250_000L, 300_000L to 200_000L)) {
                val old = floats(app.absentmotion.Judge.engine.pcm(file, before, after, rate, channels))
                val new = floats(FfampEngine.pcm(file, before, after, rate, channels))
                assertTrue(old.isNotEmpty())
                assertArrayEquals("${file.name} from $before + $after", old, new, 0f)
            }
        }
        val old = floats(app.absentmotion.Judge.engine.pcm(source, 0, 0, 44100, 2))
        val new = floats(FfampEngine.pcm(source, 0, 0, 44100, 2))
        assertEquals(old.size, new.size)
        val worst = old.indices.maxOf { abs(old[it] - new[it]) }
        assertTrue("AAC differs by $worst", worst < 0.002f)
        val ac3 = File(dir, "ac3.mp4")
        ffmpeg("-i", source.path, "-c:v", "copy", "-c:a", "ac3", ac3.path)
        val dolby = floats(app.absentmotion.Judge.engine.pcm(ac3, 0, 0, 44100, 2))
        val ours = floats(FfampEngine.pcm(ac3, 0, 0, 44100, 2))
        assertEquals(dolby.size, ours.size)
        val noise = dolby.indices.sumOf { (dolby[it] - ours[it]).toDouble().let { d -> d * d } }
        val level = 10 * log10(dolby.sumOf { it.toDouble() * it } / noise)
        assertTrue("AC-3 in MP4 is $level dB from FFmpeg", level > 30)
        if (DeskOs.mac) {
            val apple = ProcessBuilder(app.absentmotion.Judge.ffmpeg, "-nostdin", "-v", "error", "-c:a", "aac_at", "-i", source.path, "-ac", "2", "-f", "f32le", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val same = floats(MediaStream(apple.inputStream, apple))
            assertEquals(same.size, new.size)
            assertTrue(same.indices.all { abs(same[it] - new[it]) <= 1.0f / 32768 })
        }
        val shorts = FfampEngine.playback(flac, 250, 48000).use { it.input.readBytes() }
        val reference = app.absentmotion.Judge.engine.playback(flac, 250, 48000).use { it.input.readBytes() }
        assertEquals(reference.size, shorts.size)
        val one = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val two = ByteBuffer.wrap(shorts).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertTrue((0 until one.remaining()).all { abs(one[it] - two[it]) <= 1 })

        val loud = File(dir, "loud.wav")
        ffmpeg("-f", "lavfi", "-i", "aevalsrc=0.9*sin(2*PI*200*t)|0.9*sin(2*PI*300*t)|0.9*sin(2*PI*400*t)|0.9*sin(2*PI*50*t)|0.9*sin(2*PI*500*t)|0.9*sin(2*PI*600*t):s=44100:d=1", "-channel_layout", "5.1", loud.path)
        val mixed = ByteBuffer.wrap(FfampEngine.playback(loud, 0, 44100).use { it.input.readBytes() }).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val wanted = ByteBuffer.wrap(app.absentmotion.Judge.engine.playback(loud, 0, 44100).use { it.input.readBytes() }).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(wanted.remaining(), mixed.remaining())
        val off = (0 until wanted.remaining()).maxOf { abs(wanted[it] - mixed[it]) }
        assertTrue("5.1 played as 16-bit stereo differs from FFmpeg by $off", off <= 2)
    }
}
