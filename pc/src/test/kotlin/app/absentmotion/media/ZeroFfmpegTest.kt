package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskProbe
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.Expectation
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.verifyExport
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.tools.AudioJob
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoJob
import kotlinx.coroutines.runBlocking
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
import kotlin.math.sin

class ZeroFfmpegTest {
    private val dir = File("build/zero-ffmpeg").absoluteFile.also { it.mkdirs() }
    private val context = Context()
    private val source = File("../testdata/media/test-video.mp4").absoluteFile
    private val names = listOf("am.ffmpeg", "am.ffprobe")
    private var old = emptyMap<String, String?>()

    @Before fun withoutFfmpeg() {
        assumeTrue(MediaEngine.ffamp)
        DesktopNative.hashCode()
        old = names.associateWith { System.getProperty(it) }
        names.forEach { System.setProperty(it, File(dir, "absent/${it.substringAfter('.')}").path) }
        assertSame(FfampFirst, MediaEngine.current)
        assertTrue(runCatching { FfmpegEngine.probe(source) }.isFailure)
    }

    @org.junit.After fun restore() {
        old.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
    }

    private fun fresh(name: String) = File(dir, name).also { it.delete() }

    private fun probe(file: File) = DeskProbe.parse(org.json.JSONObject(MediaEngine.current.probe(file).ifBlank { "{}" }))

    private fun shot(file: File, ms: Long, side: Int = 320): ByteArray = MediaEngine.current.videoSource(file, side).use { it.frame(ms)!!.rgba.copyOf() }

    private fun psnr(a: ByteArray, b: ByteArray): Double {
        assertEquals(a.size, b.size)
        var sum = 0.0
        for (i in a.indices) { val d = (a[i].toInt() and 255) - (b[i].toInt() and 255); sum += d * d }
        return if (sum == 0.0) 99.0 else 10 * log10(255.0 * 255 / (sum / a.size))
    }

    private fun sound(file: File): ShortArray = PcmDecoder(context, Uri.fromFile(file).toString()).use { decoder ->
        val all = ArrayList<Short>()
        while (true) {
            val chunk = decoder.next() ?: break
            for (i in 0 until chunk.frames * 2) all += chunk.pcm[i]
        }
        all.toShortArray()
    }

    private fun tone(rate: Int, seconds: Double) = ShortArray((rate * seconds).toInt() * 2) { i -> (sin(2 * Math.PI * (if (i % 2 == 0) 440 else 660) * (i / 2) / rate) * 8000).toInt().toShort() }

    private fun bytes(pcm: ShortArray) = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()

    private val width = 320
    private val height = 180

    private fun picture(n: Int) = ByteArray(width * height * 4).also { out ->
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            out[i] = ((x * 255 / width + n * 3) and 255).toByte()
            out[i + 1] = (y * 255 / height).toByte()
            out[i + 2] = (if ((x / 40 + y / 40 + n / 5) % 2 == 0) 200 else 60).toByte()
            out[i + 3] = -1
        }
    }

    @Test fun importAndPlayback() {
        val found = probe(source)
        assertEquals(listOf("h264", "aac"), listOf(found.video!!.codec, found.audio!!.codec))
        assertEquals(150, VideoSources.timestamps(context, Uri.fromFile(source.copyTo(fresh("copy.mp4"))).toString()).size)
        assertEquals(320 to 180, DeskMedia.frame(source, 1000, 320)!!.let { it.width to it.height })
        assertTrue(psnr(shot(source, 0), shot(source, 3000)) < 40)
        assertEquals(5 * 44100 * 2, sound(source).size)
    }

    @Test fun videoExportsAreReadBack() {
        val kinds = listOf(ExportSettings(height, 30), ExportSettings(height, 30, Codec.Hevc, container = Container.Mov),
            ExportSettings(height, 30, Codec.Vp9, container = Container.WebM, audio = AudioSettings(AudioCodec.Opus, 128, 48000, 2)),
            ExportSettings(height, 30, Codec.Av1, container = Container.Mkv, audio = AudioSettings(AudioCodec.Opus, 128, 48000, 2)))
        for (s in kinds) {
            val name = "${s.codec.name}.${s.container.ext}"
            val pcm = fresh("$name.pcm").also { it.writeBytes(bytes(tone(s.audio.effectiveRate, 1.0))) }
            val result = fresh(name)
            val encoder = DeskEncoders.video(s.codec).first { MediaEngine.current.encoderWorks(it, s, width, height) }
            val sink = MediaEngine.current.videoExport(encoder, s, width, height, pcm, result, File(dir, "$name.log"))
            try {
                for (n in 0 until 30) sink.output.write(picture(n))
                assertTrue("$name: ${sink.problem()}", sink.finish())
            } finally { sink.close() }
            val made = probe(result)
            assertEquals(name, width to height, made.video!!.width to made.video!!.height)
            assertTrue(name, abs(made.durationUs - 1_000_000) < 60_000 && made.audio != null)
            val verified = runBlocking { verifyExport(context, Uri.fromFile(result), Expectation(true, true, 1_000_000, 33_333)) }
            assertTrue("$name: ${verified.problems}", verified.ok && verified.firstFrame == true && verified.lastFrame == true)
            val quality = psnr(picture(15), shot(result, 500))
            assertTrue("$name: $quality dB", quality > 26)
            assertTrue(name, sound(result).let { heard -> heard.size > 80_000 && heard.any { abs(it.toInt()) > 2000 } })
        }
    }

    @Test fun soundExportsAreReadBack() {
        val pcm = tone(48000, 1.0)
        for (format in AudioFormat.entries) {
            val result = fresh("sound.${format.ext}")
            val sink = MediaEngine.current.audioExport(ExportSettings(720, 30, audioFormat = format, audio = AudioSettings(AudioCodec.Aac, 160, 48000, 2)), 48000, false, result, File(dir, "sound.log"))
            try {
                sink.output.write(bytes(pcm))
                assertTrue("${format.name}: ${sink.problem()}", sink.finish())
            } finally { sink.close() }
            val heard = sound(result)
            assertTrue(format.name, abs(heard.size - pcm.size) < 9600)
            if (format.lossless) assertTrue(format.name, heard.size == pcm.size && pcm.indices.all { abs(heard[it] - pcm[it]) <= 1 })
            assertTrue(format.name, runBlocking { verifyExport(context, Uri.fromFile(result), Expectation(false, true, 1_000_000, 30_000)) }.ok)
        }
    }

    @Test fun toolsWriteFilesThatReadBack() = runBlocking {
        val uri = Uri.fromFile(source).toString()
        val small = fresh("small.mp4")
        app.absentmotion.platform.compressVideoOnDesktop(uri, VideoJob(640, 360, 30, 900_000, 96, false, 0), true, 2_000_000, small, {}, 1_000_000, 3_000_000)
        assertEquals(640 to 360, probe(small).video!!.let { it.width to it.height })
        assertTrue(abs(probe(small).durationUs - 2_000_000) < 80_000)
        assertTrue(psnr(shot(source, 2000), shot(small, 1000)) > 27)
        val cut = fresh("cut.mp4")
        app.absentmotion.platform.trimCopyOnDesktop(uri, 1_000_000, 3_000_000, cut) {}
        assertTrue(abs(probe(cut).durationUs - 2_000_000) < 80_000 && probe(cut).audio != null)
        for ((job, name) in listOf(AudioJob(false, 128, true) to "copied.m4a", AudioJob(false, 128, false) to "again.m4a", AudioJob(true, 128, false) to "again.mp3")) {
            val out = fresh(name)
            app.absentmotion.platform.extractAudioOnDesktop(uri, job, 5_000_000, out) {}
            assertTrue(name, abs(probe(out).durationUs - 5_000_000) < 80_000 && probe(out).video == null)
        }
        assertTrue(sound(File(dir, "copied.m4a")).contentEquals(sound(source)))

        val back = fresh("back.mp4")
        MediaEngine.current.reverse(ReverseParts(source, back, dir, 0, 3_000_000, 30.0, 90, 30, true), {}) { true }
        assertTrue(abs(probe(back).durationUs - 3_000_000) < 80_000)
        assertTrue(psnr(shot(source, 2467), shot(back, 500)) > psnr(shot(source, 500), shot(back, 500)) + 3)

        val gif = fresh("moving.gif")
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next()
        javax.imageio.ImageIO.createImageOutputStream(gif).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(4) { n ->
                val image = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 48) for (x in 0 until 16) image.setRGB(x + n * 12, y, 0xff3300)
                writer.writeToSequence(javax.imageio.IIOImage(image, null, null), null)
            }
            writer.endWriteSequence()
        }
        val (w, h) = checkNotNull(gifEncodeSize(64, 48) { a, b -> a <= 8192 && b <= 8192 })
        val film = fresh("gif.mp4")
        MediaEngine.current.gifVideo(GifPlan(gif, film, null, w, h)) { true }
        assertEquals(w to h, probe(film).video!!.let { it.width to it.height })

        val pipe = fresh("pipe.mp4")
        val frames = MediaEngine.current.steadyFrames(source, "1.000000", "0.500000", "10.000000", width, height).use { it.input.readBytes() }
        assertEquals(5 * width * height * 3, frames.size)
        val sink = MediaEngine.current.frameSink(pipe, FrameSinkSpec(width, height, "rgb24", "10.000000", null, source, limit = "0.500000"))
        try {
            sink.output.write(frames)
            assertTrue(sink.finish())
        } finally { sink.close() }
        assertTrue(probe(pipe).video != null && probe(pipe).audio != null && DeskMedia.frame(pipe, 0, 64) != null)

        val stopped = fresh("stopped.mp4")
        var calls = 0
        val halted = runCatching { MediaEngine.current.compress(source, VideoJob(640, 360, 30, 900_000, 96, false, 0), true, 5_000_000, stopped, 0, 0, { calls++ }) { calls < 2 } }.exceptionOrNull()
        assertTrue("$halted", halted is kotlin.coroutines.cancellation.CancellationException && (!stopped.exists() || stopped.length() == 0L))
    }

    @Test fun speechCameraAndDownloadPieces() {
        if (File("/usr/bin/say").canExecute()) {
            val spoken = fresh("said.aiff")
            check(ProcessBuilder("/usr/bin/say", "-o", spoken.path, "AbsentMotion reads this aloud.").start().waitFor() == 0)
            val shaped = fresh("said.wav")
            assertTrue(MediaEngine.current.speech(spoken, shaped, 44100, 0.8f, 1.2f, 60_000))
            assertTrue(abs(probe(shaped).durationUs - probe(spoken).durationUs / 0.8) < 60_000)
        }
        if (DeskOs.mac) {
            val fake = app.absentmotion.ui.camera.DeskCameraSource.fakeDevices
            val record = fresh("camera.mp4")
            val shown = java.util.concurrent.atomic.AtomicInteger()
            val feed = MediaEngine.current.camera(app.absentmotion.ui.camera.CameraInput(fake.video[0], fake.audio[0]), record, emptyList(), { shown.incrementAndGet() }) { _, _ -> }
            Thread.sleep(1500)
            assertTrue(feed.finish())
            assertTrue(shown.get() >= 8 && probe(record).video != null && probe(record).audio != null)
        }

        val pieces = File(dir, "pieces").also { it.deleteRecursively(); it.mkdirs() }
        val item = app.absentmotion.tools.parseLinkInfo("""{"title":"t","thumbnails":[],"site":"s","items":[{"title":"t","duration":5,"thumbnails":[],"pictures":[],"formats":[
            {"id":"136","ext":"mp4","v":"avc1.4d401f","a":"none","w":1280,"h":720,"fps":30,"abr":0,"tbr":1000,"size":1000,"proto":"https","note":"","ch":0},
            {"id":"140","ext":"m4a","v":"none","a":"mp4a.40.2","w":0,"h":0,"fps":0,"abr":128,"tbr":128,"size":1000,"proto":"https","note":"","ch":2}]}]}""").items[0]
        val plan = Plan(item.formats.first { it.hasVideo }, item.formats.first { !it.hasVideo })
        assertTrue(MediaEngine.current.withoutAudio(source.copyTo(File(pieces, "whole.mp4"))).renameTo(File(pieces, "item.136.mp4")))
        runBlocking { app.absentmotion.platform.extractAudioOnDesktop(Uri.fromFile(source).toString(), AudioJob(false, 128, true), 5_000_000, File(pieces, "item.140.m4a")) {} }
        File(pieces, "whole.mp4").delete()
        val joined = MediaEngine.current.downloaded(plan, SaveMode.Both, pieces)!!
        val made = probe(joined)
        assertTrue(made.video != null && made.audio != null && abs(made.durationUs - 5_000_000) < 50_000)
        assertTrue(psnr(shot(source, 2000), shot(joined, 2000)) > 60)
        assertTrue(sound(joined).contentEquals(sound(source)))
    }
}
