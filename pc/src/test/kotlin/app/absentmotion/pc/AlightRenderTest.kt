package app.absentmotion.pc

import android.content.Desk
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.ExportAudio
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.FolderExport
import app.absentmotion.export.ImageEncode
import app.absentmotion.export.exportSize
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.Importer
import app.absentmotion.library.ProjectStore
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class AlightRenderTest {
    @Test fun rendersImportedProject() {
        val inputPath = System.getProperty("am.alightRender")
        assumeTrue("Set -Dam.alightRender to a downloaded share package or project file", !inputPath.isNullOrBlank())
        val input = File(requireNotNull(inputPath)).canonicalFile
        require(input.isFile && input.canRead()) { "Cannot read project file $input" }
        val out = File(property("am.out")).canonicalFile
        val size = property("am.size")
        val dimensions = requireNotNull(Regex("([1-9][0-9]*)[xX]([1-9][0-9]*)").matchEntire(size)) { "am.size must be WxH" }
        val (width, height) = dimensions.destructured.toList().map(String::toInt)
        val fps = requireNotNull(property("am.fps").toIntOrNull()?.takeIf { it > 0 }) { "am.fps must be a positive integer" }
        val times = System.getProperty("am.pngTimes")?.takeIf { it.isNotBlank() }?.split(',').orEmpty().map { value ->
            requireNotNull(value.trim().toIntOrNull()?.takeIf { it >= 0 }) { "am.pngTimes must contain comma-separated milliseconds" }
        }.distinct()
        check(out.isDirectory || out.mkdirs()) { "Cannot create $out" }

        val working = Files.createTempDirectory(out.toPath(), ".alight-render-").toFile()
        val previousData = System.setProperty("am.data", working.resolve("data").path)
        val previousMix = ExportAudio.mixFor
        try {
            runBlocking { render(input, out, working, width, height, fps, times) }
        } finally {
            ExportAudio.mixFor = previousMix
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
            working.deleteRecursively()
        }
    }

    private suspend fun render(input: File, out: File, working: File, width: Int, height: Int, fps: Int, times: List<Int>) {
        val context = Desk.context
        val store = ProjectStore(context)
        val reportFile = out.resolve("import-report.txt")
        val videoFile = out.resolve("ours.mp4")
        require(input != reportFile.canonicalFile && input != videoFile.canonicalFile) { "Choose an output directory that will preserve the input file" }
        val reports = try {
            Importer.forApp(context, store).import(input::inputStream)
        } catch (failure: ImportFailed) {
            val detail = failure.text.resolve(context)
            reportFile.writeText("$detail\n")
            throw IllegalStateException(detail, failure)
        }
        val selected = reports.firstOrNull { !it.element } ?: reports.first()
        reportFile.writeText(buildString {
            appendLine("Input $input")
            appendLine("Rendering ${selected.title}")
            for (report in reports) {
                appendLine()
                appendLine(report.title)
                if (report.sections.isEmpty()) appendLine("The importer reported no issues.")
                for ((heading, lines) in report.sections) {
                    appendLine(heading.resolve(context))
                    for (line in lines) appendLine("  - ${line.resolve(context)}")
                }
            }
        })
        val scene = checkNotNull(store.load(selected.id)) { "Could not reopen ${selected.title}" }
        val missing = app.absentmotion.fonts.FontLibrary(context).fetchMissing(scene)
        if (missing.isNotEmpty()) reportFile.appendText("\nFonts not downloaded\n" + missing.joinToString("\n") { "  - $it" } + "\n")
        val settings = ExportSettings(minOf(width, height), fps, quality = 1f)
        val (actualWidth, actualHeight) = exportSize(scene, settings.shortSide, even = true)
        require(width == actualWidth && height == actualHeight) {
            "The desktop exporter gives ${actualWidth}x$actualHeight for this project at short side ${settings.shortSide}; requested ${width}x$height"
        }
        require(times.all { it <= scene.totalTime }) { "PNG times must be within 0..${scene.totalTime} ms" }

        val engine = EngineThread(Am.PreviewBg.toArgb(), context)
        try {
            val exporter = Exporter(context, engine, FolderExport(working))
            val result = exporter.export(ExportKind.Video, scene, settings, 0, "ours") {}
            val verification = result.verification
            if (verification != null && !verification.ok) {
                val problems = verification.problems.joinToString("\n") { it.resolve(context) }
                reportFile.appendText("\nExport verification\n$problems\n")
            }
            val video = checkNotNull(result.uris.single().toFile())
            check(video.isFile && video.length() > 0) { "The exporter did not produce an MP4" }
            if (times.isNotEmpty()) {
                val pngs = engine.exportSession(scene.toXml(), settings.smoothing, fps) {
                    times.map { time ->
                        val pixels = checkNotNull(still(time, width, height)) { "Could not render PNG at $time ms" }
                        ImageEncode.flatten(pixels, settings.background, pixels)
                        val bitmap = Bitmap.createBitmap(width, height)
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                        working.resolve("frame-${time}ms.png").also { png ->
                            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        }
                    }
                }
                val frames = out.resolve("frames")
                check(frames.isDirectory || frames.mkdirs()) { "Cannot create $frames" }
                for (png in pngs) Files.move(png.toPath(), frames.resolve(png.name).toPath(), REPLACE_EXISTING)
            }
            Files.move(video.toPath(), videoFile.toPath(), REPLACE_EXISTING)
        } finally {
            engine.close()
        }
    }

    private fun property(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf { it.isNotBlank() }) { "Set -D$name" }
}
