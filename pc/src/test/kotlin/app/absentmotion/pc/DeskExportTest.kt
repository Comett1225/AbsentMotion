package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.ExportAudio
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportResult
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.ImageKind
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import app.absentmotion.pc.media.AudioMixer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskExportTest {
    private val repo = File("..").canonicalFile
    private val out = File("build/desk-export").canonicalFile

    private fun scene(name: String, ms: Int = 1999): Scene {
        val xml = repo.resolve("testdata/scenes/$name").readText()
            .replace(Regex("totalTime=\"\\d+\""), "totalTime=\"$ms\"")
            .replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        return Scene.parse(xml)
    }

    private fun run(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    private fun probe(file: File): JSONObject = JSONObject(run(app.absentmotion.desk.DeskMedia.ffprobe, "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", file.path))

    private fun streams(file: File) = probe(file).getJSONArray("streams").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun ExportResult.file() = uris.first().toFile()!!

    private fun frames(file: File, name: String, vararg at: Double) = at.forEachIndexed { i, t ->
        run(app.absentmotion.desk.DeskMedia.ffmpeg, "-v", "error", "-ss", "$t", "-i", file.path, "-frames:v", "1", "-y", out.resolve("$name-$i.png").path)
    }

    @Test fun exportsEveryKind() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.exports", out.resolve("home").path)
        System.setProperty("am.data", out.resolve("data").path)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val exporter = Exporter(Desk.context, engine, MediaStoreExport(Desk.context))
        val report = StringBuilder()
        suspend fun export(kind: ExportKind, scene: Scene, s: ExportSettings, name: String): ExportResult {
            val started = System.nanoTime()
            val result = exporter.export(kind, scene, s, 15, name) {}
            report.append("$name: ${result.uris.size} file(s) in ${(System.nanoTime() - started) / 1_000_000} ms, problems=${result.verification?.problems?.size ?: "-"}, where=${result.where}\n")
            assertTrue("$name verification ${result.verification?.problems}", result.verification?.ok != false)
            return result
        }
        try {
            val styles = scene("styles.xml")
            val sound = AudioSettings(AudioCodec.Aac, 128, 48000, 2)
            for ((codec, container) in listOf(Codec.H264 to Container.Mp4, Codec.Hevc to Container.Mov, Codec.Av1 to Container.Mkv, Codec.Vp9 to Container.WebM)) {
                val r = export(ExportKind.Video, styles, ExportSettings(if (codec == Codec.H264) 1080 else 540, 30, codec, container = container), "styles-${codec.name}")
                val v = streams(r.file()).first { it.getString("codec_type") == "video" }
                report.append("  ${v.getString("codec_name")} ${v.getInt("width")}x${v.getInt("height")} frames=${v.optString("nb_read_frames")} duration=${probe(r.file()).getJSONObject("format").getString("duration")}\n")
                assertEquals(60, v.getString("nb_read_frames").toInt())
                frames(r.file(), "styles-${codec.name}", 0.0, 1.0, 1.95)
            }
            val media = scene("media.xml")
            val withSound = export(ExportKind.Video, media, ExportSettings(720, 30, audio = sound), "media-H264")
            val ms = streams(withSound.file())
            report.append("  " + ms.joinToString { "${it.getString("codec_type")}:${it.getString("codec_name")} ${it.optString("duration")}s frames=${it.optString("nb_read_frames")}" } + "\n")
            assertTrue(ms.any { it.getString("codec_name") == "aac" })
            frames(withSound.file(), "media-H264", 0.5, 1.5)
            val opus = export(ExportKind.Video, media, ExportSettings(360, 24, Codec.Vp9, container = Container.WebM, audio = sound.copy(codec = AudioCodec.Opus)), "media-VP9")
            assertTrue(streams(opus.file()).any { it.getString("codec_name") == "opus" })
            val gif = export(ExportKind.Gif, scene("text.xml"), ExportSettings(360, 15), "text-gif")
            val g = streams(gif.file()).first()
            report.append("  gif ${g.getInt("width")}x${g.getInt("height")} frames=${g.optString("nb_read_frames")}\n")
            assertEquals(30, g.getString("nb_read_frames").toInt())
            frames(gif.file(), "text-gif", 0.0, 1.0)
            val png = export(ExportKind.Frame, media, ExportSettings(360, 30), "media-frame")
            assertTrue(png.file().name.endsWith(".png"))
            for (kind in listOf(ImageKind.Jpeg, ImageKind.Webp, ImageKind.WebpLossless, ImageKind.Bmp)) {
                val r = export(ExportKind.FrameAs, styles, ExportSettings(360, 30, image = kind), "styles-${kind.name}")
                val p = streams(r.file()).first()
                report.append("  ${kind.name}: ${p.getString("codec_name")} ${p.getInt("width")}x${p.getInt("height")} ${r.file().length()} bytes\n")
            }
            val sequence = export(ExportKind.Sequence, scene("text.xml"), ExportSettings(360, 10), "text-sequence")
            assertEquals(20, sequence.uris.size)
            for (format in AudioFormat.entries) {
                val r = export(ExportKind.Audio, media, ExportSettings(360, 30, audio = sound, audioFormat = format), "media-audio-${format.name}")
                val a = streams(r.file()).first()
                report.append("  ${format.name}: ${a.getString("codec_name")} ${a.optString("sample_rate")} Hz ${a.optInt("channels")} ch ${probe(r.file()).getJSONObject("format").getString("duration")} s\n")
            }
            val cancelled = async(kotlinx.coroutines.Dispatchers.Default) { exporter.export(ExportKind.Video, scene("styles.xml", 9999), ExportSettings(2160, 60), 0, "cancelled") {} }
            kotlinx.coroutines.delay(1500)
            cancelled.cancel()
            val stopped = runCatching { cancelled.await() }.exceptionOrNull()
            report.append("cancel: ${stopped?.javaClass?.simpleName}, left: ${out.resolve("home/Movies/AbsentMotion").list()?.filter { it.startsWith("cancelled") || it.endsWith(".part") }}\n")
            assertTrue("cancel gave $stopped", stopped is kotlinx.coroutines.CancellationException)
            assertTrue(out.resolve("home/Movies/AbsentMotion").list()!!.none { it.startsWith("cancelled") || it.endsWith(".part") })
            val pack = export(ExportKind.Package, media, ExportSettings(360, 30), "media-package")
            java.util.zip.ZipFile(pack.file()).use { zip -> report.append("  package entries: ${zip.entries().toList().map { it.name }}\n"); assertNotNull(zip.getEntry("project.xml")) }
        } finally {
            out.resolve("report.txt").writeText(report.toString())
            engine.close()
        }
    }
}
