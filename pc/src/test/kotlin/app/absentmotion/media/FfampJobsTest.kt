package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskProbe
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.Expectation
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.audioCodecsFor
import app.absentmotion.export.verifyExport
import app.absentmotion.export.videoCodecsFor
import app.absentmotion.pc.engine.DesktopNative
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

class FfampJobsTest {
    private val dir = File("build/ffamp-jobs").absoluteFile.also { it.mkdirs() }
    private val context = Context()
    private val source = File("../testdata/media/test-video.mp4").absoluteFile

    @Before fun withFfamp() {
        assumeTrue(MediaEngine.ffamp)
        DesktopNative.hashCode()
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { text }
        return text
    }

    private fun ffmpeg(vararg args: String) = run(app.absentmotion.Judge.ffmpeg, "-nostdin", "-y", "-v", "error", *args)

    private fun judged(file: File): JSONObject = JSONObject(run(app.absentmotion.Judge.ffprobe, "-v", "error", "-count_packets", "-show_streams", "-show_format", "-of", "json", file.path))

    private fun stream(json: JSONObject, type: String): JSONObject? =
        json.getJSONArray("streams").let { list -> (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("codec_type") == type } }

    private fun raw(file: File, vararg args: String): ByteArray {
        val process = ProcessBuilder(listOf(app.absentmotion.Judge.ffmpeg, "-nostdin", "-v", "error", "-i", file.path) + args + listOf("-")).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return process.inputStream.readBytes().also { process.waitFor() }
    }

    private fun <T> withoutFfmpeg(work: () -> T): T {
        val names = listOf("am.ffmpeg", "am.ffprobe")
        val old = names.associateWith { System.getProperty(it) }
        names.forEach { System.setProperty(it, File(dir, "absent/${it.substringAfter('.')}").path) }
        try {
            assertTrue(runCatching { app.absentmotion.Judge.engine.probe(source) }.isFailure)
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

    private val width = 320
    private val height = 180
    private val fps = 30
    private val frames = 45

    private fun picture(n: Int) = ByteArray(width * height * 4).also { out ->
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            out[i] = ((x * 255 / width + n * 3) and 255).toByte()
            out[i + 1] = (y * 255 / height).toByte()
            out[i + 2] = (if ((x / 40 + y / 40 + n / 5) % 2 == 0) 200 else 60).toByte()
            out[i + 3] = -1
        }
    }

    private fun tone(rate: Int, seconds: Double): ShortArray = ShortArray((rate * seconds).toInt() * 2) { i ->
        (sin(2 * Math.PI * (if (i % 2 == 0) 440 else 660) * (i / 2) / rate) * 8000).toInt().toShort()
    }

    private fun bytes(pcm: ShortArray) = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()

    private fun export(engine: MediaEngine, s: ExportSettings, name: String): File {
        val pcm = File(dir, "$name.pcm").also { it.writeBytes(bytes(tone(s.audio.effectiveRate, frames.toDouble() / fps))) }
        val result = File(dir, "$name.${s.container.ext}").also { it.delete() }
        val encoder = DeskEncoders.video(s.codec).first { engine.encoderWorks(it, s, width, height) }
        val sink = engine.videoExport(encoder, s, width, height, pcm, result, File(dir, "$name.log"))
        try {
            for (n in 0 until frames) sink.output.write(picture(n))
            sink.output.flush()
            assertTrue("$name: ${sink.problem()}", sink.finish())
        } finally { sink.close() }
        return result
    }

    @Test fun encodersAreListedAndTriedWithoutFfmpeg() {
        val old = app.absentmotion.Judge.engine.encoders()
        withoutFfmpeg {
            val names = MediaEngine.current.encoders()
            val own = when { DeskOs.mac -> listOf("h264_videotoolbox", "hevc_videotoolbox", "aac_at"); DeskOs.windows -> listOf("h264_mf", "hevc_mf", "aac"); else -> listOf("libx264", "libx265", "aac") }
            assertTrue("$names", names.containsAll(own + "flac"))
            assertTrue(names.all { it in old || it == "liboapv" })
            assertTrue(MediaEngine.current.encoderWorks(own[0], ExportSettings(720, 30), 1280, 720))
            assertTrue(MediaEngine.current.encoderWorks(own[1], ExportSettings(720, 30, Codec.Hevc), 1280, 720))
            assertTrue(!MediaEngine.current.encoderWorks("h264_nvenc", ExportSettings(720, 30), 1280, 720))
        }
    }

    @Test fun everyContainerAndCodecExportsWithoutFfmpeg() {
        val names = mapOf(Codec.H264 to "h264", Codec.Hevc to "hevc", Codec.Av1 to "av1", Codec.Vp9 to "vp9", Codec.Apv to "apv")
        val expected = frames.toDouble() / fps
        var pairs = 0
        for (container in Container.entries) for (codec in videoCodecsFor(container)) for (sound in audioCodecsFor(container)) {
            if (DeskEncoders.video(codec).isEmpty() || DeskEncoders.audio(sound).isEmpty()) continue
            val s = ExportSettings(height, fps, codec, container = container, audio = app.absentmotion.export.AudioSettings(sound, 128, 48000, 2))
            val label = "${container.ext}-${names[codec]}-${sound.label.lowercase()}"
            val made = withoutFfmpeg { export(FfampEngine, s, "new-$label") }
            pairs++
            val json = judged(made)
            val video = stream(json, "video")!!
            val audio = stream(json, "audio")!!
            assertEquals(label, names[codec], video.getString("codec_name"))
            assertEquals(label, if (sound == AudioCodec.Opus) "opus" else "aac", audio.getString("codec_name"))
            assertEquals(label, frames, video.getString("nb_read_packets").toInt())
            assertEquals(label, width to height, video.getInt("width") to video.getInt("height"))
            assertEquals(label, expected, json.getJSONObject("format").getString("duration").toDouble(), 0.05)
            if (codec == Codec.H264) assertEquals(label, 0, video.getInt("has_b_frames"))
            val shown = raw(made, "-map", "0:v:0", "-vf", "scale=in_color_matrix=bt709:in_range=tv", "-pix_fmt", "rgb0", "-f", "rawvideo")
            assertEquals(label, frames * width * height * 4, shown.size)
            val wanted = ByteArray(shown.size).also { all -> for (n in 0 until frames) System.arraycopy(picture(n), 0, all, n * width * height * 4, width * height * 4) }
            val quality = psnr(wanted, shown)
            assertTrue("$label: $quality dB", quality > 28)
            val sound16 = raw(made, "-map", "0:a:0", "-ar", "48000", "-ac", "2", "-f", "s16le")
            assertEquals(label, expected, sound16.size / 4 / 48000.0, 0.03)
            val verified = withoutFfmpeg { runBlocking { verifyExport(context, Uri.fromFile(made), Expectation(true, true, (expected * 1e6).toLong(), 1_000_000L / fps)) } }
            assertTrue("$label: ${verified.problems}", verified.ok && verified.firstFrame == true && verified.lastFrame == true)
            if ("lib" !in DeskEncoders.video(codec).first() || app.absentmotion.Judge.engine.encoders().contains(DeskEncoders.video(codec).first())) {
                val before = runCatching { export(app.absentmotion.Judge.engine, s, "old-$label") }.getOrNull() ?: continue
                val was = judged(before)
                assertEquals(label, stream(was, "video")!!.getString("nb_read_packets"), video.getString("nb_read_packets"))
                assertEquals(label, was.getJSONObject("format").getString("duration").toDouble(), json.getJSONObject("format").getString("duration").toDouble(), 0.1)
                val ratio = made.length().toDouble() / before.length()
                assertTrue("$label: size ratio $ratio", ratio in 0.4..2.5)
            }
        }
        assertTrue("$pairs pairs", pairs >= 8)
    }

    @Test fun audioExportsWithoutFfmpeg() {
        val pcm = tone(48000, 1.5)
        for (format in AudioFormat.entries) {
            val s = ExportSettings(720, 30, audioFormat = format, audio = app.absentmotion.export.AudioSettings(AudioCodec.Aac, 160, 48000, 2))
            fun make(engine: MediaEngine, name: String): File {
                val result = File(dir, "$name.${format.ext}").also { it.delete() }
                val sink = engine.audioExport(s, 48000, false, result, File(dir, "$name.log"))
                try {
                    val out = sink.output.buffered(1 shl 16)
                    out.write(bytes(pcm))
                    out.flush()
                    assertTrue("$name: ${sink.problem()}", sink.finish())
                } finally { sink.close() }
                return result
            }
            val made = withoutFfmpeg { make(FfampEngine, "new-${format.name}") }
            val before = make(app.absentmotion.Judge.engine, "old-${format.name}")
            val one = judged(made)
            val two = judged(before)
            assertEquals(format.name, stream(two, "audio")!!.getString("codec_name"), stream(one, "audio")!!.getString("codec_name"))
            assertEquals(format.name, two.getJSONObject("format").getString("format_name"), one.getJSONObject("format").getString("format_name"))
            val a = raw(made, "-f", "s16le", "-ar", "48000", "-ac", "2")
            val b = raw(before, "-f", "s16le", "-ar", "48000", "-ac", "2")
            assertEquals(format.name, 1.5, a.size / 4 / 48000.0, 0.03)
            assertEquals(format.name, b.size / 4 / 48000.0, a.size / 4 / 48000.0, 0.03)
            if (format.lossless) {
                assertArrayEquals(format.name, bytes(pcm), a)
                assertArrayEquals(format.name, b, a)
            } else {
                val heard = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val worst = (4800 until minOf(heard.remaining(), pcm.size) - 4800).maxOf { abs(heard[it] - pcm[it]) }
                assertTrue("${format.name}: sample error $worst", worst < 2500)
            }
            val verified = withoutFfmpeg { runBlocking { verifyExport(context, Uri.fromFile(made), Expectation(false, true, 1_500_000, 30_000)) } }
            assertTrue("${format.name}: ${verified.problems}", verified.ok)
        }
        val mono = ExportSettings(720, 30, audioFormat = AudioFormat.Wav16, audio = app.absentmotion.export.AudioSettings(AudioCodec.Aac, 160, 48000, 1))
        val result = File(dir, "mono.wav")
        val sink = FfampEngine.audioExport(mono, 48000, false, result, File(dir, "mono.log"))
        sink.output.write(bytes(pcm))
        assertTrue(sink.finish())
        sink.close()
        assertEquals(1, stream(judged(result), "audio")!!.getInt("channels"))
    }

    @Test fun exportCheckReadsTimesLikeFfprobe() {
        for (from in listOf("%+#1", "0.0%", "3.0%")) for (decoded in listOf(true, false)) {
            if (from == "%+#1" && !decoded) continue
            val old = app.absentmotion.Judge.engine.frameTimes(source, decoded, from)
            val new = withoutFfmpeg { MediaEngine.current.frameTimes(source, decoded, from) }
            assertEquals("$from $decoded", old.size, new.size)
            assertTrue("$from $decoded", old.sorted().zip(new.sorted()).all { (a, b) -> abs(a - b) <= 1 })
        }
        val verified = withoutFfmpeg { runBlocking { verifyExport(context, Uri.fromFile(source), Expectation(true, true, 5_000_000, 33_333)) } }
        assertTrue("${verified.problems}", verified.ok && verified.firstFrame == true && verified.lastFrame == true)
        val probe = DeskProbe.of(source)
        assertEquals(5_000_000L, probe.durationUs)
    }

    private fun packets(file: File, type: String): List<String> =
        run(app.absentmotion.Judge.ffprobe, "-v", "error", "-select_streams", "$type:0", "-show_entries", "packet=size", "-of", "csv=p=0", file.path).lines().map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }

    private fun seconds(file: File) = judged(file).getJSONObject("format").getString("duration").toDouble()

    private fun frame(file: File, at: String, w: Int = 320, h: Int = 180) = raw(file, "-ss", at, "-frames:v", "1", "-vf", "scale=$w:$h", "-pix_fmt", "rgb24", "-f", "rawvideo")

    @Test fun compressExtractAndTrimRunWithoutFfmpeg() = runBlocking {
        val uri = Uri.fromFile(source).toString()
        val job = app.absentmotion.tools.VideoJob(640, 360, 30, 900_000, 96, false, 0)
        val small = File(dir, "small.mp4").also { it.delete() }
        val seen = ArrayList<Float>()
        withoutFfmpeg { runBlocking { app.absentmotion.platform.compressVideoOnDesktop(uri, job, true, 2_000_000, small, { seen += it }, 1_000_000, 3_000_000) } }
        val before = File(dir, "small-old.mp4").also { it.delete() }
        app.absentmotion.Judge.engine.compress(source, job, true, 2_000_000, before, 1_000_000, 3_000_000, {}) { true }
        val one = judged(small)
        val two = judged(before)
        assertTrue(seen.isNotEmpty() && seen.zipWithNext().all { it.first <= it.second })
        assertEquals(640 to 360, stream(one, "video")!!.getInt("width") to stream(one, "video")!!.getInt("height"))
        assertEquals("aac", stream(one, "audio")!!.getString("codec_name"))
        assertEquals(seconds(before), seconds(small), 0.08)
        assertEquals(stream(two, "video")!!.getString("nb_read_packets").toInt().toDouble(), stream(one, "video")!!.getString("nb_read_packets").toInt().toDouble(), 2.0)
        assertTrue("size ${small.length()} against ${before.length()}", small.length().toDouble() / before.length() in 0.6..1.6)
        for (at in listOf("0", "1.0", "1.9")) {
            val quality = psnr(frame(before, at), frame(small, at))
            assertTrue("compressed frame at $at: $quality dB", quality > 27)
        }
        val wanted = psnr(frame(source, "2.0"), frame(small, "1.0"))
        assertTrue("compressed against the source: $wanted dB", wanted > 27)

        val copied = File(dir, "sound.m4a").also { it.delete() }
        withoutFfmpeg { runBlocking { app.absentmotion.platform.extractAudioOnDesktop(uri, app.absentmotion.tools.AudioJob(false, 128, true), 5_000_000, copied) {} } }
        val copiedOld = File(dir, "sound-old.m4a").also { it.delete() }
        app.absentmotion.Judge.engine.extractAudio(source, app.absentmotion.tools.AudioJob(false, 128, true), 5_000_000, copiedOld, {}) { true }
        assertEquals(packets(source, "a"), packets(copied, "a"))
        assertEquals(packets(copiedOld, "a"), packets(copied, "a"))
        assertArrayEquals(raw(copiedOld, "-f", "s16le"), raw(copied, "-f", "s16le"))
        for ((mp3, name) in listOf(false to "again.m4a", true to "again.mp3")) {
            val made = File(dir, name).also { it.delete() }
            withoutFfmpeg { runBlocking { app.absentmotion.platform.extractAudioOnDesktop(uri, app.absentmotion.tools.AudioJob(mp3, 128, false), 5_000_000, made) {} } }
            assertEquals(if (mp3) "mp3" else "aac", stream(judged(made), "audio")!!.getString("codec_name"))
            assertEquals(name, 5.0, seconds(made), 0.08)
        }

        val cut = File(dir, "cut.mp4").also { it.delete() }
        withoutFfmpeg { runBlocking { app.absentmotion.platform.trimCopyOnDesktop(uri, 1_000_000, 3_000_000, cut) {} } }
        val cutOld = File(dir, "cut-old.mp4").also { it.delete() }
        app.absentmotion.Judge.engine.trim(source, 1_000_000, 3_000_000, cutOld, {}) { true }
        assertEquals(packets(cutOld, "v"), packets(cut, "v"))
        assertEquals(seconds(cutOld), seconds(cut), 0.05)
        assertEquals(packets(cutOld, "a").size.toDouble(), packets(cut, "a").size.toDouble(), 2.0)
        assertTrue(psnr(frame(cutOld, "0.5"), frame(cut, "0.5")) > 60)
    }

    @Test fun reverseAndGifRunWithoutFfmpeg() {
        fun reversed(engine: MediaEngine, name: String): File {
            val out = File(dir, "$name.mp4").also { it.delete() }
            val folder = File(dir, "$name-parts").also { it.deleteRecursively(); it.mkdirs() }
            engine.reverse(ReverseParts(source, out, folder, 0, 3_000_000, 30.0, 90, 30, true), {}) { true }
            return out
        }
        val made = withoutFfmpeg { reversed(FfampEngine, "back") }
        val before = reversed(app.absentmotion.Judge.engine, "back-old")
        assertEquals(seconds(before), seconds(made), 0.08)
        assertEquals(packets(before, "v").size, packets(made, "v").size)
        assertTrue(stream(judged(made), "audio") != null)
        for ((at, from) in listOf("0.5" to "2.466667", "2.0" to "0.966667")) {
            val right = psnr(frame(source, from), frame(made, at))
            val wrong = psnr(frame(source, at), frame(made, at))
            assertTrue("reversed frame at $at: $right dB against $wrong dB", right > 27 && right > wrong + 3)
            assertTrue(psnr(frame(before, at), frame(made, at)) > 27)
        }

        val gif = File(dir, "moving.gif")
        ffmpeg("-f", "lavfi", "-i", "testsrc=size=64x48:rate=10", "-t", "1.2", gif.path)
        val (w, h) = checkNotNull(gifEncodeSize(64, 48) { a, b -> a <= 8192 && b <= 8192 })
        fun converted(engine: MediaEngine, name: String) = File(dir, "$name.mp4").also { it.delete(); engine.gifVideo(GifPlan(gif, it, null, w, h)) { true } }
        val film = withoutFfmpeg { converted(FfampEngine, "gif") }
        val filmOld = converted(app.absentmotion.Judge.engine, "gif-old")
        val one = stream(judged(film), "video")!!
        val two = stream(judged(filmOld), "video")!!
        assertEquals(w to h, one.getInt("width") to one.getInt("height"))
        assertEquals(two.getInt("width") to two.getInt("height"), one.getInt("width") to one.getInt("height"))
        assertEquals(two.getString("nb_read_packets"), one.getString("nb_read_packets"))
        assertEquals(seconds(filmOld), seconds(film), 0.11)
        val drawn = frame(gif, "0.55", w, h)
        val fresh = psnr(drawn, frame(film, "0.55", w, h))
        val earlier = psnr(drawn, frame(filmOld, "0.55", w, h))
        assertTrue("GIF picture: $fresh dB, FFmpeg $earlier dB", fresh > 15 && fresh > earlier - 3)
    }

    @Test fun framePipesRunWithoutFfmpeg() {
        fun read(engine: MediaEngine, w: Int, h: Int) = engine.steadyFrames(source, "1.000000", "0.500000", "10.000000", w, h).use { it.input.readBytes() }
        for ((w, h) in listOf(0 to 0, 160 to 90)) {
            val old = read(app.absentmotion.Judge.engine, w, h)
            val new = withoutFfmpeg { read(FfampEngine, w, h) }
            assertEquals(old.size, new.size)
            assertEquals(5 * (if (w == 0) 1280 * 720 else w * h) * 3, new.size)
            val quality = psnr(old, new)
            assertTrue("steady frames ${w}x$h: $quality dB", quality > 35)
        }
        for ((format, unit) in listOf("rgb24" to 3, "rgba" to 4, "gray" to 1)) {
            fun write(engine: MediaEngine, name: String): File {
                val out = File(dir, "$name.mp4").also { it.delete() }
                val spec = FrameSinkSpec(width, height, format, "15.000000", "1.000000".takeIf { format != "rgba" }, source.takeIf { format != "gray" },
                    "1.000000".takeIf { format == "rgba" }, "1.000000".takeIf { format == "rgba" }, if (format == "gray") null else if (format == "rgba") "1.000000" else "2.000000")
                val sink = engine.frameSink(out, spec)
                try {
                    val pipe = sink.output.buffered()
                    for (n in 0 until 16) {
                        val full = picture(n * 2)
                        pipe.write(when (unit) { 4 -> full; 3 -> ByteArray(width * height * 3) { full[it / 3 * 4 + it % 3] }; else -> ByteArray(width * height) { full[it * 4] } })
                    }
                    pipe.flush()
                    assertTrue(name, sink.finish())
                } finally { sink.close() }
                return out
            }
            val made = withoutFfmpeg { write(FfampEngine, "pipe-$format") }
            val before = write(app.absentmotion.Judge.engine, "pipe-$format-old")
            val one = judged(made)
            val two = judged(before)
            assertEquals(format, "h264", stream(one, "video")!!.getString("codec_name"))
            assertEquals(format, stream(two, "video")!!.getString("nb_read_packets"), stream(one, "video")!!.getString("nb_read_packets"))
            assertEquals(format, stream(two, "audio") != null, stream(one, "audio") != null)
            assertEquals(format, seconds(before), seconds(made), 0.08)
            assertTrue(format, DeskMedia.frame(made, 0, 64) != null)
            val quality = psnr(frame(before, if (format == "rgba") "0.5" else "1.5"), frame(made, if (format == "rgba") "0.5" else "1.5"))
            assertTrue("$format written frame: $quality dB", quality > 27)
        }
    }

    @Test fun speechIsShapedWithoutFfmpeg() {
        assumeTrue(File("/usr/bin/say").canExecute())
        val spoken = File(dir, "said.aiff")
        run("/usr/bin/say", "-o", spoken.path, "AbsentMotion reads this sentence aloud.")
        for ((tempo, pitch) in listOf(1f to 1f, 0.8f to 1f, 1f to 1.3f, 0.7f to 0.8f)) {
            val made = File(dir, "said-$tempo-$pitch.wav").also { it.delete() }
            val before = File(dir, "said-$tempo-$pitch-old.wav").also { it.delete() }
            assertTrue(withoutFfmpeg { MediaEngine.current.speech(spoken, made, 44100, tempo, pitch, 60_000) })
            assertTrue(app.absentmotion.Judge.engine.speech(spoken, before, 44100, tempo, pitch, 60_000))
            val one = stream(judged(made), "audio")!!
            assertEquals(listOf("pcm_s16le", "44100", "1"), listOf(one.getString("codec_name"), one.getString("sample_rate"), "${one.getInt("channels")}"))
            assertEquals("$tempo $pitch", seconds(before), seconds(made), seconds(before) * 0.02 + 0.02)
        }
        val probe = withoutFfmpeg { DeskProbe.parse(JSONObject(MediaEngine.current.probe(spoken))) }
        assertTrue(probe.audio != null && probe.durationUs > 500_000)
    }

    @Test fun cameraRunsWithoutFfmpeg() {
        assumeTrue(DeskOs.mac)
        val fake = app.absentmotion.ui.camera.DeskCameraSource.fakeDevices
        val record = File(dir, "camera.mp4").also { it.delete() }
        val shown = java.util.concurrent.atomic.AtomicInteger()
        val sizes = java.util.Collections.synchronizedSet(HashSet<Pair<Int, Int>>())
        withoutFfmpeg {
            assertTrue(MediaEngine.current.cameraEncoder().isEmpty())
            val devices = FfampNative.devices()
            assertTrue(devices.isEmpty() || devices.lines().filter { it.isNotBlank() }.all { it.split('\t').size == 3 })
            val feed = MediaEngine.current.camera(app.absentmotion.ui.camera.CameraInput(fake.video[0], fake.audio[0]), record, emptyList(), { jpeg ->
                org.jetbrains.skia.Image.makeFromEncoded(jpeg).use { sizes += it.width to it.height }
                shown.incrementAndGet()
            }) { _, _ -> }
            Thread.sleep(2000)
            assertTrue(feed.finish())
            assertTrue(feed.stopping)
        }
        assertTrue("${shown.get()} preview frames", shown.get() >= 10)
        assertEquals(1, sizes.size)
        val json = judged(record)
        assertEquals("h264", stream(json, "video")!!.getString("codec_name"))
        assertEquals("aac", stream(json, "audio")!!.getString("codec_name"))
        assertEquals(2.0, seconds(record), 0.6)
        val preview = withoutFfmpeg {
            val seen = java.util.concurrent.atomic.AtomicInteger()
            val feed = MediaEngine.current.camera(app.absentmotion.ui.camera.CameraInput(fake.video[0], null), null, emptyList(), { seen.incrementAndGet() }) { _, _ -> }
            Thread.sleep(700)
            feed.stop()
            seen.get()
        }
        assertTrue("$preview preview frames", preview >= 3)
    }

    @Test fun downloadedPiecesAreJoinedWithoutFfmpeg() {
        fun format(id: String, video: Boolean, sound: Boolean) = app.absentmotion.tools.parseLinkInfo(JSONObject().put("title", "t").put("thumbnails", org.json.JSONArray()).put("site", "s").put("items", org.json.JSONArray().put(
            JSONObject().put("title", "t").put("duration", 5).put("thumbnails", org.json.JSONArray()).put("pictures", org.json.JSONArray()).put("formats", org.json.JSONArray().put(
                JSONObject().put("id", id).put("ext", if (video) "mp4" else "m4a").put("v", if (video) "avc1.4d401f" else "none").put("a", if (sound) "mp4a.40.2" else "none")
                    .put("w", if (video) 1280 else 0).put("h", if (video) 720 else 0).put("fps", 30).put("abr", 128).put("tbr", 1000).put("size", 1000).put("proto", "https").put("note", "").put("ch", 2))))).toString()).items[0].formats[0]
        val picture = format("136", true, false)
        val sound = format("140", false, true)
        val both = format("18", true, true)
        fun folder(name: String) = File(dir, name).also { it.deleteRecursively(); it.mkdirs() }

        val two = folder("pieces")
        ffmpeg("-i", source.path, "-an", "-c", "copy", File(two, "item.136.mp4").path)
        ffmpeg("-i", source.path, "-vn", "-c", "copy", File(two, "item.140.m4a").path)
        val plan = app.absentmotion.tools.Plan(picture, sound)
        val options = MediaEngine.current.downloadOptions(plan, app.absentmotion.tools.SaveMode.Both, two)
        assertTrue("$options", options.containsAll(listOf("--fixup", "never", "136,140", "m3u8:native", "dash:native")))
        assertEquals(FfmpegEngine.ffmpeg, options[options.indexOf("--ffmpeg-location") + 1])
        assertTrue(options.last().endsWith("item.%(format_id)s.%(ext)s"))
        val joined = withoutFfmpeg { MediaEngine.current.downloaded(plan, app.absentmotion.tools.SaveMode.Both, two)!! }
        assertEquals("mp4", joined.extension)
        assertEquals(packets(source, "v"), packets(joined, "v"))
        assertEquals(packets(source, "a"), packets(joined, "a"))
        assertEquals(5.0, seconds(joined), 0.05)
        val old = folder("pieces-old")
        ffmpeg("-i", File(two, "item.136.mp4").path, "-i", File(two, "item.140.m4a").path, "-c", "copy", File(old, "item.mp4").path)
        assertEquals(packets(File(old, "item.mp4"), "v"), packets(joined, "v"))
        assertTrue(psnr(frame(File(old, "item.mp4"), "2.0"), frame(joined, "2.0")) > 60)

        val only = folder("sound")
        ffmpeg("-i", source.path, "-vn", "-c", "copy", File(only, "item.m4a").path)
        val heard = withoutFfmpeg { MediaEngine.current.downloaded(app.absentmotion.tools.Plan(null, sound), app.absentmotion.tools.SaveMode.AudioOnly, only)!! }
        assertEquals("m4a", heard.extension)
        assertEquals(packets(source, "a"), packets(heard, "a"))
        val webm = folder("opus")
        ffmpeg("-i", source.path, "-vn", "-c:a", "libopus", File(webm, "item.webm").path)
        val turned = withoutFfmpeg { MediaEngine.current.downloaded(app.absentmotion.tools.Plan(null, sound), app.absentmotion.tools.SaveMode.AudioOnly, webm)!! }
        assertEquals("aac", stream(judged(turned), "audio")!!.getString("codec_name"))
        assertEquals(5.0, seconds(turned), 0.08)

        val stream = folder("hls")
        ffmpeg("-i", source.path, "-c", "copy", "-f", "mpegts", File(stream, "item.mp4").path)
        val plain = withoutFfmpeg { MediaEngine.current.downloaded(app.absentmotion.tools.Plan(both, both), app.absentmotion.tools.SaveMode.Both, stream)!! }
        assertTrue(judged(plain).getJSONObject("format").getString("format_name").startsWith("mov,mp4"))
        assertEquals(150, packets(plain, "v").size)
        val whole = folder("whole")
        source.copyTo(File(whole, "item.mp4"))
        assertEquals("item.mp4", withoutFfmpeg { MediaEngine.current.downloaded(app.absentmotion.tools.Plan(both, both), app.absentmotion.tools.SaveMode.Both, whole)!! }.name)
        val silent = withoutFfmpeg { MediaEngine.current.withoutAudio(File(whole, "item.mp4")) }
        assertEquals("silent.mp4", silent.name)
        assertTrue(stream(judged(silent), "audio") == null)
        assertEquals(packets(source, "v"), packets(silent, "v"))
    }

    @Test fun copyJobsFfampCannotStoreAreLeftToFfmpeg() {
        val surround = File(dir, "dts.mkv").also { ffmpeg("-i", source.path, "-c:v", "copy", "-c:a", "dca", "-strict", "-2", it.path) }
        assertTrue(FfampFirst.reads(surround))
        val cut = File(dir, "dts-cut.mp4").also { it.delete() }
        FfampFirst.trim(surround, 1_000_000, 3_000_000, cut, {}) { true }
        assertEquals("dts", stream(judged(cut), "audio")!!.getString("codec_name"))
        assertEquals(2.0, seconds(cut), 0.1)
        val installed = System.getProperty("am.ffmpeg")
        System.setProperty("am.ffmpeg", File(dir, "missing/ffmpeg").path)
        val stopped = try { runCatching { FfampFirst.trim(surround, 1_000_000, 3_000_000, File(dir, "dts-cut-alone.mp4"), {}) { true } }.exceptionOrNull() } finally {
            if (installed == null) System.clearProperty("am.ffmpeg") else System.setProperty("am.ffmpeg", installed)
        }
        assertTrue("$stopped", stopped is ExportError)

        val phone = File(dir, "h263.3gp").also { ffmpeg("-i", source.path, "-vf", "scale=176:144", "-c:v", "h263", "-c:a", "aac", it.path) }
        assertTrue(FfampFirst.reads(phone))
        val silent = FfampFirst.withoutAudio(phone)
        assertEquals("silent.3gp", silent.name)
        assertTrue(stream(judged(silent), "audio") == null)
        assertEquals(packets(phone, "v"), packets(silent, "v"))

        val pieces = File(dir, "pieces-dts").also { it.deleteRecursively(); it.mkdirs() }
        ffmpeg("-i", source.path, "-an", "-c", "copy", File(pieces, "item.136.mp4").path)
        ffmpeg("-i", surround.path, "-vn", "-c", "copy", File(pieces, "item.140.mp4").path)
        val picture = app.absentmotion.tools.RemoteFormat("136", "mp4", app.absentmotion.tools.VideoCodec.Avc, app.absentmotion.tools.AudioCodec.None)
        val sound = app.absentmotion.tools.RemoteFormat("140", "mp4", app.absentmotion.tools.VideoCodec.None, app.absentmotion.tools.AudioCodec.Unknown)
        val joined = FfampFirst.downloaded(app.absentmotion.tools.Plan(picture, sound), app.absentmotion.tools.SaveMode.Both, pieces)!!
        assertEquals("joined.mp4", joined.name)
        assertEquals("dts", stream(judged(joined), "audio")!!.getString("codec_name"))
        assertEquals(packets(source, "v"), packets(joined, "v"))
    }

    @Test fun cancelStopsJobsAndLeavesNoFile() {
        val job = app.absentmotion.tools.VideoJob(640, 360, 30, 900_000, 96, false, 0)
        val out = File(dir, "stopped.mp4").also { it.delete() }
        var calls = 0
        val started = System.nanoTime()
        val stopped = runCatching { FfampEngine.compress(source, job, true, 5_000_000, out, 0, 0, { calls++ }) { calls < 2 } }.exceptionOrNull()
        assertTrue("$stopped", stopped is kotlin.coroutines.cancellation.CancellationException)
        assertTrue(!out.exists() || out.length() == 0L)
        assertTrue((System.nanoTime() - started) / 1e9 < 5)
        val back = File(dir, "stopped-back.mp4").also { it.delete() }
        val folder = File(dir, "stopped-parts").also { it.mkdirs() }
        var steps = 0
        val halted = runCatching { FfampEngine.reverse(ReverseParts(source, back, folder, 0, 5_000_000, 30.0, 150, 30, true), { steps++ }) { steps < 1 } }.exceptionOrNull()
        assertTrue("$halted", halted is kotlin.coroutines.cancellation.CancellationException)
        assertTrue(!back.exists() || back.length() == 0L)
        val running = runBlocking {
            val work = async(kotlinx.coroutines.Dispatchers.IO) { app.absentmotion.platform.trimCopyOnDesktop(Uri.fromFile(source).toString(), 0, 5_000_000, File(dir, "stopped-cut.mp4")) { } }
            work.cancel()
            runCatching { work.await() }.exceptionOrNull()
        }
        assertTrue("$running", running == null || running is kotlin.coroutines.cancellation.CancellationException)
    }

    @Test fun tagsAndSpokenFilesAreRead() {
        val tagged = File(dir, "tagged.m4a")
        ffmpeg("-i", source.path, "-vn", "-c", "copy", "-metadata", "title=A Title", "-metadata", "artist=An Artist", "-metadata", "album=An Album", tagged.path)
        val song = File(dir, "tagged.mp3")
        ffmpeg("-i", source.path, "-vn", "-c:a", "libmp3lame", "-metadata", "title=A Title", "-metadata", "artist=An Artist", "-metadata", "album=An Album", song.path)
        val lossless = File(dir, "tagged.flac")
        ffmpeg("-i", source.path, "-vn", "-c:a", "flac", "-metadata", "title=A Title", "-metadata", "artist=An Artist", "-metadata", "album=An Album", lossless.path)
        for (file in listOf(tagged, song, lossless)) {
            val facts = withoutFfmpeg { DeskMedia.probe(file) }
            assertEquals(file.name, listOf("A Title", "An Artist", "An Album"), listOf(facts["title"], facts["artist"], facts["album"]))
            val old = DeskProbe.parse(JSONObject(app.absentmotion.Judge.engine.probe(file)))
            val new = DeskProbe.parse(JSONObject(FfampEngine.probe(file)))
            assertEquals(file.name, listOf("title", "artist", "album").map { old.tags[it] }, listOf("title", "artist", "album").map { new.tags[it] })
        }
        assumeTrue(File("/usr/bin/say").canExecute())
        val spoken = File(dir, "words.aiff")
        run("/usr/bin/say", "-o", spoken.path, "One two three.")
        val uri = Uri.fromFile(spoken).toString()
        val heard = withoutFfmpeg {
            app.absentmotion.audio.PcmDecoder(context, uri).use { decoder ->
                var frames = 0
                var loud = false
                while (true) {
                    val chunk = decoder.next() ?: break
                    frames += chunk.frames
                    loud = loud || (0 until chunk.frames * 2).any { abs(chunk.pcm[it].toInt()) > 500 }
                }
                frames to loud
            }
        }
        val reference = raw(spoken, "-f", "s16le", "-ac", "1")
        assertEquals(reference.size / 2, heard.first)
        assertTrue(heard.second)
    }
}
