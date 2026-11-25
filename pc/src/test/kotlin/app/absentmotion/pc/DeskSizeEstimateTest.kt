package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.ImageKind
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.estimateBytes
import app.absentmotion.export.measureGif
import app.absentmotion.export.measureSequence
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskSizeEstimateTest {
    private val repo = File("..").canonicalFile

    private fun scene(ms: Int): Scene = Scene.parse(repo.resolve("testdata/scenes/media.xml").readText()
        .replace(Regex("totalTime=\"\\d+\""), "totalTime=\"$ms\"")
        .replace(Regex("endTime=\"4999\""), "endTime=\"$ms\"")
        .replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/"))

    @Test fun everyOtherKindIsEstimatedCloseToTheExportedSize() = runBlocking {
        val home = File("build/size-estimate").canonicalFile.also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.exports", home.path)
        val scene = scene(14999)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val exporter = Exporter(Desk.context, engine, MediaStoreExport(Desk.context))
        val lines = ArrayList<String>()
        val off = ArrayList<String>()
        suspend fun check(name: String, kind: ExportKind, s: ExportSettings, tolerance: Double, estimate: suspend () -> Long) {
            val measured = estimate()
            val result = exporter.export(kind, scene, s, 200, name) {}
            val files = result.uris.map { it.toFile()!! }
            val real = files.sumOf { it.length() }
            files.forEach { it.delete() }
            val ratio = measured.toDouble() / real
            val formula = estimateBytes(kind, scene, s, true).toDouble() / real
            lines += "ESTIMATE $name: estimate $measured real $real ratio ${"%.3f".format(ratio)} formula ${"%.3f".format(formula)}"
            if (ratio !in 1 - tolerance..1 + tolerance) off += "$name ${"%.3f".format(ratio)}"
        }
        try {
            check("xml", ExportKind.Xml, ExportSettings(360, 30), 0.0) { exporter.measureBytes(ExportKind.Xml, scene, ExportSettings(360, 30), 200) }
            check("package", ExportKind.Package, ExportSettings(360, 30), 0.001) { exporter.measureBytes(ExportKind.Package, scene, ExportSettings(360, 30), 200) }
            check("frame", ExportKind.Frame, ExportSettings(360, 30), 0.0) { exporter.measureBytes(ExportKind.Frame, scene, ExportSettings(360, 30), 200) }
            for (image in ImageKind.entries) for (quality in if (image.lossy) listOf(50, 95) else listOf(95)) {
                val s = ExportSettings(360, 30, image = image, imageQuality = quality)
                check("frame-${image.name}-$quality", ExportKind.FrameAs, s, 0.0) { exporter.measureBytes(ExportKind.FrameAs, scene, s, 200) }
            }
            for (image in ImageKind.entries) {
                val s = ExportSettings(360, 10, image = image, imageQuality = 85)
                check("sequence-${image.name}", ExportKind.Sequence, s, 0.15) { measureSequence(engine, scene, s).bytes!! }
            }
            val gif = ExportSettings(360, 15)
            check("gif", ExportKind.Gif, gif, 0.15) { measureGif(engine, scene, gif).bytes }
            for (format in AudioFormat.entries) for (kbps in if (format.lossless) listOf(128) else listOf(96, 256)) {
                val s = ExportSettings(360, 30, audioFormat = format).let { it.copy(audio = it.audio.copy(bitrateKbps = kbps)) }
                check("audio-${format.name}-$kbps", ExportKind.Audio, s, if (format.lossless && format != AudioFormat.Flac) 0.001 else 0.1) { exporter.measureBytes(ExportKind.Audio, scene, s, 200) }
            }
        } finally {
            lines.forEach(::println)
            File("build/size-estimate-ratios.txt").writeText(lines.joinToString("\n"))
            home.deleteRecursively()
            engine.close()
        }
        assertTrue("estimates off: $off", off.isEmpty())
    }
}
