package app.absentmotion.pc.media

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.platform.exportEncoders
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

class Exporter(private val assets: File, private val repo: File?) {
    enum class Format { MP4, GIF, PNG }
    data class Options(val format: Format, val width: Int, val height: Int, val fps: Int, val bitrate: Int, val output: File, val hevc: Boolean = false)
    data class Progress(val frame: Int, val total: Int, val secondsLeft: Long?)
    data class Result(val frames: Int, val duration: Double, val width: Int, val height: Int)

    @Volatile private var cancelled = false
    @Volatile private var process: Process? = null

    fun cancel() { cancelled = true; process?.destroyForcibly() }
    private fun checkCancelled() { if (cancelled) throw CancellationException("Export cancelled") }

    fun export(scene: Scene, base: File?, options: Options, progress: (Progress) -> Unit = {}): Result {
        require(options.width in 1..8192 && options.height in 1..8192 && options.fps in 1..120 && options.bitrate > 0)
        require(options.format != Format.MP4 || options.width % 2 == 0 && options.height % 2 == 0) { "Video size must be even" }
        require(!options.output.exists()) { "Output already exists" }
        require(options.output.parentFile?.isDirectory == true) { "Output folder does not exist" }
        val count = if (options.fps == scene.fps) scene.lastFrame + 1 else (((scene.totalTime + 1L) * options.fps + 999) / 1000).toInt().coerceAtLeast(1)
        val temp = java.nio.file.Files.createTempDirectory(options.output.parentFile.toPath(), ".export-").toFile()
        val result = try {
            DesktopEngine(assets, repo).use { engine ->
                engine.setScene(scene, base)
                if (options.format == Format.PNG) {
                    val folder = temp.resolve("frames").also { it.mkdir() }
                    val started = System.nanoTime()
                    for (i in 0 until count) {
                        checkCancelled()
                        val image = engine.render((i * 1000L / options.fps).toInt(), options.width, options.height)
                        check(ImageIO.write(image, "png", folder.resolve(String.format(Locale.ROOT, "frame_%05d.png", i + 1))))
                        report(i + 1, count, started, progress)
                    }
                    check(folder.renameTo(options.output)) { "Could not save frames" }
                    Result(count, count.toDouble() / options.fps, options.width, options.height)
                } else {
                    val encoders = if (options.format == Format.MP4) exportEncoders(options.hevc) else listOf("gif")
                    var last: Throwable? = null
                    var verified: Result? = null
                    for (encoder in encoders) {
                        checkCancelled()
                        val output = temp.resolve(if (options.format == Format.MP4) "out.mp4" else "out.gif")
                        output.delete()
                        try {
                            val args = mutableListOf(DesktopPlatform.executable("ffmpeg"), "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${options.width}x${options.height}", "-r", "${options.fps}", "-i", "-")
                            if (options.format == Format.MP4) args.addAll(listOf("-an", "-c:v", encoder, "-b:v", "${options.bitrate}", "-pix_fmt", "yuv420p"))
                            args.addAll(listOf("-y", output.absolutePath))
                            val log = temp.resolve("ffmpeg.log")
                            val child = ProcessBuilder(args).redirectError(log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                            process = child
                            val pixels = IntArray(options.width * options.height)
                            val bytes = ByteArray(pixels.size * 4)
                            val started = System.nanoTime()
                            child.outputStream.buffered().use { stream ->
                                for (i in 0 until count) {
                                    checkCancelled()
                                    val image = engine.render((i * 1000L / options.fps).toInt(), options.width, options.height)
                                    image.getRGB(0, 0, options.width, options.height, pixels, 0, options.width)
                                    for (p in pixels.indices) {
                                        val v = pixels[p]; val at = p * 4
                                        bytes[at] = v.toByte(); bytes[at + 1] = (v shr 8).toByte(); bytes[at + 2] = (v shr 16).toByte(); bytes[at + 3] = (v shr 24).toByte()
                                    }
                                    stream.write(bytes)
                                    report(i + 1, count, started, progress)
                                }
                            }
                            check(child.waitFor() == 0) { log.readText().take(2000) }
                            checkCancelled()
                            val checked = verify(output, options, count)
                            check(output.renameTo(options.output)) { "Could not save export" }
                            verified = checked
                            break
                        } catch (error: Exception) {
                            process?.destroyForcibly()
                            checkCancelled()
                            last = error
                        } finally { process = null }
                    }
                    verified ?: throw IllegalStateException("Could not encode export", last)
                }
            }
        } finally { temp.deleteRecursively() }
        return result
    }

    private fun report(done: Int, total: Int, start: Long, progress: (Progress) -> Unit) {
        val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
        progress(Progress(done, total, (elapsed * (total - done) / done).toLong()))
    }

    private fun verify(file: File, options: Options, count: Int): Result {
        val child = ProcessBuilder(DesktopPlatform.executable("ffprobe"), "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=width,height,nb_read_frames:format=duration", "-of", "json", file.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = child
        val json = JSONObject(child.inputStream.bufferedReader().readText())
        check(child.waitFor() == 0) { "Could not verify export" }
        val video = json.getJSONArray("streams").getJSONObject(0)
        val width = video.getInt("width"); val height = video.getInt("height"); val frames = video.getString("nb_read_frames").toInt()
        val duration = json.getJSONObject("format").getString("duration").toDouble()
        check(width == options.width && height == options.height && frames == count && kotlin.math.abs(duration - count.toDouble() / options.fps) <= 1.0 / options.fps + .02) { "Export verification failed" }
        return Result(frames, duration, width, height)
    }
}
