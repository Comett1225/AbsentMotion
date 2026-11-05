package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.desk.DeskMedia
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.BackgroundBlack
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportDebug
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportRecord
import app.absentmotion.export.ExportTimes
import app.absentmotion.export.measureVideoDraw
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.ImageEncode
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.exportSize
import app.absentmotion.export.frameCount
import app.absentmotion.export.frameTimeMs
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ExportTimingTest {
    private val repo = File("..").canonicalFile
    private val out = File("build/export-timing").canonicalFile

    private fun scene(): Scene {
        val end = (System.getenv("AM_TIMING_SECONDS")?.toIntOrNull() ?: 10) * 1000 - 1
        val xml = repo.resolve("testdata/scenes/media.xml").readText()
            .replace("totalTime=\"4999\"", "totalTime=\"$end\"").replace("endTime=\"4999\"", "endTime=\"$end\"")
            .replace("speed=\"1.000000\"", "speed=\"0.500000\"")
            .replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        val catalog = EffectCatalog.read(Desk.context)
        val fx = (System.getenv("AM_TIMING_EFFECTS") ?: "blur,glow,vignette,rgbsplit").split(',').filter { it.isNotBlank() }
            .map { key -> catalog.definitions.first { it.key == key }.instance() }
        val parsed = Scene.parse(xml)
        return Scene(parsed.node.copy(children = parsed.node.children.map { if (it["id"] == "8004" || it["id"] == "8003") it.copy(children = it.children + fx) else it }))
    }

    private fun fps(frames: Int, ns: Long) = "%.1f fps (%.1f ms/frame)".format(frames * 1e9 / ns, ns / 1e6 / frames)

    private fun ffmpeg(args: List<String>, frames: List<ByteArray>, count: Int): Long {
        val log = out.resolve("ffmpeg.log")
        val started = System.nanoTime()
        val process = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-hide_banner", "-v", "error") + args).redirectError(log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.buffered(1 shl 20).use { stdin -> for (k in 0 until count) stdin.write(frames[k % frames.size]) }
        check(process.waitFor() == 0) { log.readText() }
        return System.nanoTime() - started
    }

    @Test fun exportStages() = runBlocking {
        assumeTrue("set AM_TIMING=1 to run", System.getenv("AM_TIMING") != null)
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.exports", out.resolve("home").path)
        System.setProperty("am.data", out.resolve("data").path)
        val scene = scene()
        val s = ExportSettings(1080, 30)
        val (w, h) = exportSize(scene, s.shortSide, even = true)
        val frames = frameCount(scene, s.fps)
        val report = StringBuilder("scene ${w}x$h, $frames frames, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cpus\n")
        fun line(text: String) { report.append(text).append('\n'); println(text) }
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            val times = ExportTimes(Desk.context)
            suspend fun estimate(label: String) {
                val draw = measureVideoDraw(engine, scene, s)
                line("time estimate $label: drawing sample %.1f ms/frame, estimate %d ms".format(draw, times.estimateMs(s, scene, null, drawMs = draw) ?: -1L))
            }
            estimate("before anything was drawn")
            val samples = ArrayList<ByteArray>()
            var render = 0L
            engine.exportSession(scene.toXml(), s.smoothing, s.fps) {
                still(0, w, h)
                for (k in 0 until frames) {
                    val t = System.nanoTime()
                    val pixels = checkNotNull(still(frameTimeMs(scene, s.fps, k), w, h))
                    render += System.nanoTime() - t
                    if (k % 30 == 0) samples += pixels
                }
            }
            line("old path, render + unpremultiplied readback into a new array: ${fps(frames, render)}")
            val buffer = ByteArray(w * h * 4)
            var into = 0L
            engine.exportSession(scene.toXml(), s.smoothing, s.fps) {
                check(stillInto(0, w, h, buffer))
                for (k in 0 until frames) {
                    val t = System.nanoTime()
                    check(stillInto(frameTimeMs(scene, s.fps, k), w, h, buffer))
                    into += System.nanoTime() - t
                }
            }
            line("export path, render + premultiplied readback into a reused array: ${fps(frames, into)}")
            val empty = Scene.create("Empty", scene.width, scene.height, 30, 0xff000000.toInt())
            var blank = 0L
            var blankInto = 0L
            engine.exportSession(empty.toXml(), s.smoothing, s.fps) {
                still(0, w, h)
                for (k in 0 until 60) { val t = System.nanoTime(); checkNotNull(still(k * 33, w, h)); blank += System.nanoTime() - t }
                for (k in 0 until 60) { val t = System.nanoTime(); check(stillInto(k * 33, w, h, buffer)); blankInto += System.nanoTime() - t }
            }
            line("readback alone (empty scene), old path: ${fps(60, blank)}; export path: ${fps(60, blankInto)}")
            val scratch = samples.map { it.copyOf() }
            val flat = System.nanoTime().let { t -> for (k in 0 until frames) scratch[k % scratch.size].let { ImageEncode.flatten(it, BackgroundBlack, it) }; System.nanoTime() - t }
            line("old path only, flatten on black in Kotlin (writer thread): ${fps(frames, flat)}")
            val raw = listOf("-f", "rawvideo", "-pix_fmt", "rgb0", "-s", "${w}x$h", "-r", "${s.fps}", "-i", "-")
            line("pipe write only: ${fps(frames, ffmpeg(raw + listOf("-f", "null", "-"), samples, frames))}")
            val convert = listOf("-vf", "scale=out_color_matrix=bt709:out_range=tv", "-pix_fmt", "yuv420p")
            line("pipe + rgb0->yuv420p: ${fps(frames, ffmpeg(raw + convert + listOf("-f", "null", "-"), samples, frames))}")
            for (name in DeskEncoders.video(s.codec).filter { DeskEncoders.works(it, s, w, h) }.let { listOfNotNull(it.firstOrNull(), it.lastOrNull()) }.distinct()) {
                val args = raw + listOf("-vf", "scale=out_color_matrix=bt709:out_range=tv") + DeskEncoders.videoArgs(name, s, w, h) + listOf("-f", "mp4", "-y", out.resolve("$name.mp4").path)
                line("pipe + convert + $name: ${fps(frames, ffmpeg(args, samples, frames))}")
            }
            samples.clear()
            val exporter = Exporter(Desk.context, engine, MediaStoreExport(Desk.context))
            for (software in listOf(false, true)) {
                ExportDebug.refuseHardware = software
                val t = System.nanoTime()
                var first = 0L
                var last = 0L
                exporter.export(ExportKind.Video, scene, s, 0, "timing-${if (software) "software" else "default"}") { p ->
                    val now = System.nanoTime() - t
                    if (first == 0L && p > 0f) first = now
                    if (p >= 0.98f && last == 0L) last = now
                }
                val total = System.nanoTime() - t
                times.record(ExportRecord(s.codec, w, h, s.fps, frames, total / 1_000_000))
                line("whole export (${if (software) "software encoder" else "first working encoder"}): ${fps(frames, total)}; first frame after %d ms, frames done after %d ms, finished after %d ms".format(first / 1_000_000, last / 1_000_000, total / 1_000_000))
            }
            estimate("after the exports")
        } finally {
            ExportDebug.refuseHardware = false
            engine.close()
            out.resolve("report.txt").writeText(report.toString())
        }
    }
}
