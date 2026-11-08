package app.absentmotion.pc

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import app.absentmotion.audio.AudioEngine
import app.absentmotion.audio.DeskOutput
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.audio.Waveforms
import app.absentmotion.audio.downmixGains
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskSounds
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class DeskAudioTest {
    private val dir = File("build/audio-test").absoluteFile.also { it.mkdirs() }
    private val context = Context()

    init {
        System.setProperty("am.data", File("build/audio-data").absolutePath)
        DesktopNative.hashCode()
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-nostdin", "-y", "-v", "error") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { out }
    }

    private fun tone(name: String, expr: String, rate: Int, seconds: Double, vararg extra: String): File = File(dir, name).also {
        if (!it.isFile) ffmpeg("-f", "lavfi", "-i", "aevalsrc=$expr:s=$rate:d=$seconds", *extra, it.path)
    }

    private val a get() = tone("a.m4a", "0.25*sin(2*PI*440*t)|0.2*sin(2*PI*550*t)", 44100, 3.0, "-c:a", "aac", "-b:a", "192k")
    private val b get() = tone("b.flac", "0.2*sin(2*PI*660*t)", 48000, 2.0, "-metadata", "title=Tone B", "-metadata", "artist=Tester")
    private val surround get() = tone("c.wav", "0.1*sin(2*PI*200*t)|0.1*sin(2*PI*300*t)|0.1*sin(2*PI*400*t)|0.1*sin(2*PI*50*t)|0.1*sin(2*PI*500*t)|0.1*sin(2*PI*600*t)", 48000, 1.0, "-channel_layout", "5.1")

    private fun floats(vararg args: String): FloatArray {
        val p = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error") + args + listOf("-f", "f32le", "-")).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun decodeAll(file: File, seekUs: Long = -1): Pair<ShortArray, Long> {
        PcmDecoder(context, Uri.fromFile(file).toString()).use { d ->
            if (seekUs >= 0) d.seek(seekUs)
            val out = ArrayList<Short>()
            var first = -1L
            var expected = -1L
            while (true) {
                val c = d.next() ?: break
                if (first < 0) first = c.ptsUs
                if (expected >= 0) assertTrue("chunks continue", abs(c.ptsUs - expected) <= 1)
                expected = c.ptsUs + c.frames * 1_000_000L / c.rate
                for (i in 0 until 2 * c.frames) out += c.pcm[i]
            }
            return out.toShortArray() to first
        }
    }

    private fun toShort(v: Float) = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    @Test fun decoderMatchesFfmpeg() {
        val (pcm, _) = decodeAll(a)
        val ref = floats("-i", a.path, "-ac", "2")
        assertEquals(ref.size, pcm.size)
        assertTrue(ref.indices.all { pcm[it] == toShort(ref[it]) })
        val (mono, _) = decodeAll(b)
        val refMono = floats("-i", b.path)
        assertEquals(refMono.size * 2, mono.size)
        assertTrue(refMono.indices.all { mono[2 * it] == toShort(refMono[it]) && mono[2 * it + 1] == toShort(refMono[it]) })
        val (seeked, first) = decodeAll(a, 1_234_000)
        assertEquals(1_234_000L, first)
        val offset = Math.round(1.234 * 44100).toInt() * 2
        val lag = (-64..64).minBy { l -> (0 until 4000).sumOf { i -> abs(seeked[i] - toShort(ref[offset + 2 * l + i]).toInt()).toLong() } }
        println("seek lag samples=$lag")
        assertEquals(0, lag)
        val (down, _) = decodeAll(surround)
        val six = floats("-i", surround.path)
        val gains = downmixGains(6, 0)
        for (f in 0 until six.size / 6 step 97) {
            var l = 0f; var r = 0f
            for (c in 0 until 6) { l += gains[2 * c] * six[6 * f + c] * 32767f; r += gains[2 * c + 1] * six[6 * f + c] * 32767f }
            assertEquals(l.toInt(), down[2 * f].toInt()); assertEquals(r.toInt(), down[2 * f + 1].toInt())
        }
    }

    private fun soundAt(file: File, seekUs: Long): Pair<Long, Long> {
        val (pcm, first) = decodeAll(file, seekUs)
        val at = pcm.indices.firstOrNull { abs(pcm[it].toInt()) > 300 } ?: -2
        return first to at / 2 * 1_000_000L / 48000
    }

    @Test fun delayedSoundKeepsItsPlaceOnTheVideoTimeline() {
        val late = File(dir, "late.mkv").also {
            if (!it.isFile) ffmpeg("-f", "lavfi", "-i", "color=c=red:s=64x64:r=25:d=3", "-itsoffset", "1", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-c:a", "flac", it.path)
        }
        val early = File(dir, "early.mkv").also {
            if (!it.isFile) ffmpeg("-itsoffset", "0.5", "-f", "lavfi", "-i", "color=c=red:s=64x64:r=25:d=3", "-f", "lavfi", "-i", "aevalsrc=if(gte(t\\,0.7)\\,0.25*sin(2*PI*440*t)\\,0):s=48000:d=3",
                "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-c:a", "flac", it.path)
        }
        fun near(expected: Pair<Long, Long>, got: Pair<Long, Long>) =
            assertTrue("expected $expected, got $got", got.first == expected.first && abs(got.second - expected.second) <= 1_000)
        near(0L to 1_000_000L, soundAt(late, -1))
        near(500_000L to 500_000L, soundAt(late, 500_000))
        near(1_500_000L to 0L, soundAt(late, 1_500_000))
        val lead = 700_000L - app.absentmotion.desk.DeskProbe.of(early).video!!.startUs
        assertTrue("video starts at ${700_000 - lead}", lead in 100_000L..300_000L)
        near(0L to lead, soundAt(early, -1))
        near(100_000L to lead - 100_000L, soundAt(early, 100_000))
        near(1_000_000L to 0L, soundAt(early, 1_000_000))
    }

    @Test fun extractorReadsTracks() {
        val video = File("../testdata/media/test-video.mp4").absoluteFile
        val x = MediaExtractor()
        x.setDataSource(context, Uri.fromFile(video), null)
        val formats = (0 until x.trackCount).map { x.getTrackFormat(it) }
        val v = formats.first { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
        val s = formats.first { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
        assertEquals(1280, v.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(720, v.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(30, v.getInteger(MediaFormat.KEY_FRAME_RATE)); assertEquals(5_000_000L, v.getLong(MediaFormat.KEY_DURATION))
        assertTrue(s.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0 && s.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 0)
        x.selectTrack(formats.indexOf(v))
        var count = 0; var syncs = 0
        while (x.sampleTime >= 0) { count++; if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs++; if (!x.advance()) break }
        assertEquals(150, count)
        assertTrue(syncs >= 1)
        x.seekTo(2_500_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        assertTrue(x.sampleTime in 0..2_500_000 && x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
        x.release()
    }

    @Test fun waveformPeaks() {
        val uri = Uri.fromFile(a).toString()
        Waveforms.request(context, uri)
        val deadline = System.currentTimeMillis() + 15_000
        while (Waveforms.peaks(uri) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val peaks = Waveforms.peaks(uri)!!
        println("waveform bins=${peaks.data.size} peak=${peaks.peak(0.0, 3000.0)}")
        assertTrue(peaks.data.size in 295..306)
        val top = floats("-i", a.path).maxOf { abs(it) }
        assertEquals((top * 32767f).toInt() / 128 / 255f, peaks.peak(0.0, 3000.0), 1.5f / 255)
        assertEquals(0.25f, peaks.peak(500.0, 2500.0), 0.01f)
    }

    @Test fun deviceSoundsFromFolders() {
        val home = File(dir, "home").also { it.deleteRecursively() }
        val music = File(home, "Music/Album").also { it.mkdirs() }
        b.copyTo(File(music, "b.flac"))
        a.copyTo(File(home, "Downloads/a.m4a").also { it.parentFile.mkdirs() })
        a.copyTo(File(home, "Music/.hidden/x.m4a").also { it.parentFile.mkdirs() })
        a.copyTo(File(home, "Music/Song.band/y.m4a").also { it.parentFile.mkdirs() })
        File(home, "Downloads/notes.txt").writeText("x")
        val cache = File(dir, "sounds-cache.json").also { it.delete() }
        fun linked(home: File): List<File> {
            app.absentmotion.desk.DeskLibrary.folders.forEach { app.absentmotion.desk.DeskLibrary.unlink(it) }
            app.absentmotion.desk.DeskLibrary.link(File(home, "Music"))
            app.absentmotion.desk.DeskLibrary.link(File(home, "Downloads"))
            return app.absentmotion.desk.DeskLibrary.files(setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "txt").minus("txt"))
        }
        val t0 = System.nanoTime()
        val first = DeskSounds.scan(linked(home), home, cache)
        val t1 = System.nanoTime()
        val again = DeskSounds.scan(linked(home), home, cache)
        println("device sounds: first scan %.1f ms, cached %.1f ms".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
        assertEquals(2, first.size)
        assertEquals(first, again)
        val tone = first.first { it.name == "b.flac" }
        assertEquals("Tone B", tone.title); assertEquals("Tester", tone.artist); assertEquals("Music/Album", tone.folder)
        assertEquals(2000L, tone.durationMs)
        assertEquals("a", first.first { it.name == "a.m4a" }.title)
    }

    @Test fun playbackMatchesFfmpegMixAndClockFollowsOutput() {
        val scene = Scene.parse("""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Sound" width="1080" height="1920" totalTime="2999" fps="30" bgcolor="#ff000000">
  <audio id="1" label="A" startTime="0" endTime="2999" src="${Uri.fromFile(a)}"><gain value="1.000000" /></audio>
  <audio id="2" label="B" startTime="500" endTime="2499" src="${Uri.fromFile(b)}"><gain value="0.500000" /></audio>
</scene>""")
        val rate = DeskOutput.nativeRate
        val captured = java.io.ByteArrayOutputStream()
        val sink = java.io.DataOutputStream(captured)
        var firstWrite = 0L
        DeskOutput.capture = { block, frames -> if (firstWrite == 0L) firstWrite = System.nanoTime(); for (i in 0 until 2 * frames) sink.writeFloat(block[i]) }
        val engine = AudioEngine(context)
        try {
            engine.setScene(scene)
            Thread.sleep(500)
            val samples = ArrayList<Pair<Long, Double>>()
            val begin = System.nanoTime()
            assertTrue(engine.play(0.0))
            while (System.nanoTime() - begin < 2_600_000_000L) {
                engine.positionMs()?.let { samples += System.nanoTime() to it }
                Thread.sleep(2)
            }
            engine.stop()
            val frozen = engine.positionMs()
            Thread.sleep(50)
            assertEquals(frozen, engine.positionMs())

            val moving = samples.filter { it.second > 5.0 }
            assertTrue("monotonic", samples.zipWithNext().all { it.second.second >= it.first.second })
            val xs = moving.map { (it.first - begin) / 1e6 }
            val ys = moving.map { it.second }
            val mx = xs.average(); val my = ys.average()
            val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.indices.sumOf { (xs[it] - mx) * (xs[it] - mx) }
            val icpt = my - slope * mx
            val residual = xs.indices.map { ys[it] - (icpt + slope * xs[it]) }
            File(dir, "clock.csv").writeText(xs.indices.joinToString("\n") { "%.3f,%.3f,%.3f".format(xs[it], ys[it], residual[it]) })
            val heardAt = -icpt / slope
            println("clock: rate=%d slope=%.6f (%.0f ppm) jitter rms=%.3f ms max=%.3f ms; first write %.1f ms after play(); clock says first frame heard %.1f ms after play(); CoreAudio latency %.1f ms".format(
                rate, slope, (slope - 1) * 1e6, sqrt(residual.sumOf { it * it } / residual.size), residual.maxOf { abs(it) }, (firstWrite - begin) / 1e6, heardAt, DeskOutput.latencyNs() / 1e6))
            assertEquals(1.0, slope, 0.002)
            assertTrue(residual.maxOf { abs(it) } < 3.0)

            DeskOutput.capture = null
            val got = FloatArray(captured.size() / 4).also { java.io.DataInputStream(captured.toByteArray().inputStream()).run { for (i in it.indices) it[i] = readFloat() } }
            val ref = floats("-i", a.path, "-i", b.path, "-filter_complex",
                "[0:a]aresample=$rate[a];[1:a]pan=stereo|c0=c0|c1=c0,aresample=$rate,volume=0.5,adelay=500:all=1[b];[a][b]amix=inputs=2:normalize=0:duration=first", "-ac", "2", "-ar", "$rate")
            val frames = minOf(got.size, ref.size) / 2
            val edges = listOf(0, 500, 2500).map { it * rate / 1000 }
            val keep = (0 until minOf(frames, (2.4 * rate).toInt())).filter { f -> edges.none { abs(f - it) < rate * 4 / 1000 } }
            val lag = (-32..32).minBy { l -> keep.filter { it + l in 0 until frames }.sumOf { f -> abs(got[2 * f] - ref[2 * (f + l)]).toDouble() } }
            var signal = 0.0; var noise = 0.0; var worst = 0f
            for (f in keep) for (c in 0..1) { val d = got[2 * f + c] - ref[2 * f + c]; signal += ref[2 * f + c] * ref[2 * f + c]; noise += d * d; worst = maxOf(worst, abs(d)) }
            println("mix vs ffmpeg: frames=$frames lag=$lag samples, SNR=%.1f dB, max diff=%.5f (%.1f dBFS)".format(10 * log10(signal / noise), worst, 20 * log10(worst.toDouble())))
            assertEquals(0, lag)
            assertTrue(10 * log10(signal / noise) > 50)
        } finally {
            DeskOutput.capture = null
            engine.close()
        }
    }

    @Test fun restartAndStopKeepClockSane() {
        val scene = Scene.parse("""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Sound" width="1080" height="1920" totalTime="2999" fps="30" bgcolor="#ff000000">
  <audio id="1" label="A" startTime="0" endTime="2999" src="${Uri.fromFile(a)}"><gain value="1.000000" /></audio>
</scene>""")
        DeskOutput.capture = { _, _ -> }
        val engine = AudioEngine(context)
        try {
            engine.setScene(scene)
            val starts = ArrayList<Double>()
            repeat(3) { round ->
                val begin = System.nanoTime()
                assertTrue(engine.play(1000.0 * round))
                while (System.nanoTime() - begin < 2_000_000_000L && (engine.positionMs() ?: 0.0) <= 1000.0 * round) Thread.sleep(1)
                starts += (System.nanoTime() - begin) / 1e6
                Thread.sleep(300)
                val p = engine.positionMs()!!
                assertTrue("round $round at $p", p in 1000.0 * round + 250..1000.0 * round + 350)
            }
            engine.stop()
            println("restart: clock moving %.1f / %.1f / %.1f ms after play() (includes device latency %.1f ms)".format(starts[0], starts[1], starts[2], DeskOutput.latencyNs() / 1e6))
        } finally {
            DeskOutput.capture = null
            engine.close()
        }
    }

    @Test fun soundPreviewStartsAndStops() {
        DeskOutput.capture = { _, _ -> }
        try {
            val player = MediaPlayer()
            val prepared = java.util.concurrent.CountDownLatch(1)
            var startedAt = 0L
            player.setDataSource(context, Uri.fromFile(a))
            player.setOnPreparedListener { it.start(); startedAt = System.nanoTime(); prepared.countDown() }
            player.setOnErrorListener { _, _, _ -> true }
            val t0 = System.nanoTime()
            player.prepareAsync()
            assertTrue(prepared.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(3000, player.duration)
            assertTrue(player.isPlaying)
            while (player.currentPosition <= 0 && System.nanoTime() - startedAt < 2_000_000_000L) Thread.sleep(1)
            val moving = (System.nanoTime() - startedAt) / 1e6
            Thread.sleep(400)
            val pos = player.currentPosition
            player.pause()
            assertTrue(!player.isPlaying)
            val held = player.currentPosition
            Thread.sleep(100)
            assertEquals(held, player.currentPosition)
            println("preview: prepared %.1f ms, position moving %.1f ms after start(), at %d ms after +400 ms".format((startedAt - t0) / 1e6, moving, pos))
            assertTrue(pos in 300..600)
            player.release()
        } finally {
            DeskOutput.capture = null
        }
    }
}
