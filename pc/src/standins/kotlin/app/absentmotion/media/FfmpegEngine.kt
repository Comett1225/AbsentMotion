package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.desk.SpeechCommands
import app.absentmotion.desk.SpeechRun
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Ffmpeg
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.txt
import app.absentmotion.tools.AudioJob
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoJob
import app.absentmotion.ui.camera.CameraDevices
import app.absentmotion.ui.camera.CameraFeed
import app.absentmotion.ui.camera.CameraInput
import app.absentmotion.ui.camera.DeskCameraSession
import app.absentmotion.ui.camera.DeskCameraSource
import app.absentmotion.pc.engine.FfmpegVideoSource
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.min
import javax.imageio.ImageIO

object FfmpegEngine : MediaEngine {
    val ffmpeg: String get() = DesktopPlatform.executable("ffmpeg")
    val ffprobe: String get() = DesktopPlatform.executable("ffprobe")

    private fun start(command: List<String>) = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    override fun probe(file: File, entries: String?): String {
        val shown = entries?.let { listOf("-show_entries", it) } ?: listOf("-show_streams", "-show_format")
        val process = start(listOf(ffprobe, "-v", "error") + shown + listOf("-of", "json", file.path))
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text
    }

    override fun packets(file: File, stream: Int): List<MediaPacket> {
        val list = ArrayList<MediaPacket>()
        val process = start(listOf(ffprobe, "-v", "error", "-select_streams", "$stream",
            "-show_entries", "packet=pts_time,dts_time,size,pos,flags", "-of", "compact=p=0", file.path))
        process.inputStream.bufferedReader().forEachLine { line ->
            val kv = line.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
            val time = (kv["pts_time"]?.toDoubleOrNull() ?: kv["dts_time"]?.toDoubleOrNull() ?: return@forEachLine)
            list += MediaPacket(time, kv["size"]?.toIntOrNull() ?: 0, kv["flags"]?.startsWith("K") == true)
        }
        process.waitFor()
        return list
    }

    override fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage? {
        val process = start(listOf(ffmpeg, "-v", "error", "-ss", "%.3f".format(Locale.ROOT, timeMs / 1000.0), "-i", file.path, "-frames:v", "1",
            "-vf", "scale='min($maxSide,iw)':'min($maxSide,ih)':force_original_aspect_ratio=decrease", "-f", "image2pipe", "-vcodec", "png", "-"))
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        return ImageIO.read(bytes.inputStream())
    }

    override fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream {
        val command = mutableListOf(ffmpeg, "-nostdin", "-v", "error")
        if (beforeUs > 0) command += listOf("-ss", seconds(beforeUs))
        command += listOf("-i", file.path)
        if (afterUs > 0) command += listOf("-ss", seconds(afterUs))
        command += listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-af", "aresample=async=1:first_pts=0", "-ar", "$rate", "-ac", "$channels", "-f", "f32le", "-")
        val started = start(command)
        return MediaStream(started.inputStream.buffered(1 shl 16), started, true)
    }

    override fun playback(file: File, fromMs: Long, rate: Int): MediaStream {
        val started = start(listOf(ffmpeg, "-nostdin", "-v", "error", "-ss", "%.3f".format(Locale.ROOT, fromMs / 1000.0), "-i", file.path,
            "-map", "0:a:0", "-vn", "-ar", "$rate", "-ac", "2", "-f", "s16le", "-"))
        return MediaStream(started.inputStream.buffered(1 shl 16), started, true)
    }

    override fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream {
        val started = start(listOf(ffmpeg, "-nostdin", "-v", "error", "-ss", seconds, "-noautorotate", "-i", file.path, "-map", "0:v:0", "-frames:v", "$count") +
            (if (batch) listOf("-fps_mode", "passthrough") else emptyList()) + listOf("-pix_fmt", "yuv420p", "-f", "rawvideo", "-"))
        return MediaStream(started.inputStream, started)
    }

    override fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream {
        val from = if (loop) listOf("-re", "-stream_loop", "-1") else listOf("-ss", "%.3f".format(Locale.ROOT, fromMs / 1000.0), "-re")
        val started = start(listOf(ffmpeg, "-v", "error") + from + listOf("-i", file.path, "-an", "-vf", "scale=$width:$height", "-f", "rawvideo", "-pix_fmt", "rgba", "-"))
        return MediaStream(started.inputStream, started)
    }

    override fun videoSource(file: File, maxSide: Int, maxPixels: Int): VideoSource = FfmpegVideoSource(file, ffmpeg, ffprobe, maxSide, maxPixels)

    private fun seconds(us: Long) = "%.6f".format(Locale.ROOT, us / 1e6)

    private val listed: Set<String> by lazy {
        runCatching {
            val process = start(listOf(ffmpeg, "-hide_banner", "-encoders"))
            val names = process.inputStream.bufferedReader().readLines().mapNotNull { line ->
                line.trim().split(Regex("\\s+")).takeIf { it.size >= 2 && it[0].length == 6 && it[0][0] in "VAS" }?.get(1)
            }.toSet()
            process.waitFor()
            names
        }.getOrDefault(emptySet())
    }

    override fun encoders(): Set<String> = listed

    override fun encoderWorks(name: String, s: ExportSettings, width: Int, height: Int): Boolean = runCatching {
        val args = listOf(ffmpeg, "-v", "error") + DeskEncoders.device(name) + listOf("-f", "lavfi", "-i", "color=c=black:s=${width}x$height:r=${s.fps}:d=0.2") +
            DeskEncoders.filter(name) + DeskEncoders.videoArgs(name, s, width, height) + listOf("-frames:v", "2", "-f", "null", "-")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try { process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0 } finally { process.destroyForcibly() }
    }.getOrDefault(false)

    override fun videoExport(encoder: String, s: ExportSettings, width: Int, height: Int, pcm: File?, result: File, log: File): MediaSink {
        val sound = if (pcm == null) emptyList() else {
            val coder = DeskEncoders.audio(s.audio.codec, s.audio.bitrateKbps).firstOrNull() ?: throw ExportError(txt(R.string.export_support_no_encoder, s.audio.codec.label))
            listOf("-map", "1:a", "-c:a", coder) + DeskEncoders.audioExtra(coder) + listOf("-b:a", "${s.audio.bitrateKbps}k", "-ar", "${s.audio.effectiveRate}", "-ac", "${s.audio.channels}")
        }
        val inputs = listOf("-f", "rawvideo", "-pix_fmt", "rgb0", "-s", "${width}x$height", "-r", "${s.fps}", "-i", "-") +
            (if (pcm == null) emptyList() else listOf("-f", "s16le", "-ar", "${s.audio.effectiveRate}", "-ac", "2", "-i", pcm.path))
        val tag = if (s.codec == Codec.Hevc && (s.container == Container.Mp4 || s.container == Container.Mov)) listOf("-tag:v", "hvc1") else emptyList()
        val format = when (s.container) {
            Container.Mp4 -> listOf("-f", "mp4", "-movflags", "+faststart")
            Container.Mov -> listOf("-f", "mov", "-movflags", "+faststart")
            Container.WebM -> listOf("-f", "webm")
            Container.Mkv -> listOf("-f", "matroska")
        }
        return Ffmpeg(DeskEncoders.device(encoder) + inputs + listOf("-map", "0:v") + DeskEncoders.filter(encoder) +
            DeskEncoders.videoArgs(encoder, s, width, height) + tag + sound + format + listOf("-y", result.path), log)
    }

    override fun audioExport(s: ExportSettings, rate: Int, floats: Boolean, result: File, log: File): MediaSink {
        val bitrate = listOf("-b:a", "${s.audio.bitrateKbps}k")
        fun coder(name: String, label: String) = name.takeIf { it in DeskEncoders.available } ?: throw ExportError(txt(R.string.export_support_no_encoder, label))
        val encode = when (s.audioFormat) {
            AudioFormat.Wav16 -> listOf("-c:a", "pcm_s16le", "-f", "wav")
            AudioFormat.Wav24 -> listOf("-c:a", "pcm_s24le", "-f", "wav")
            AudioFormat.Flac -> listOf("-c:a", coder("flac", "FLAC"), "-compression_level", "5", "-f", "flac")
            AudioFormat.M4a -> listOf("-c:a", DeskEncoders.audio(AudioCodec.Aac, s.audio.bitrateKbps).firstOrNull() ?: coder("aac", AudioCodec.Aac.label)) + bitrate + listOf("-f", "ipod")
            AudioFormat.Ogg -> (DeskEncoders.audio(AudioCodec.Opus).firstOrNull() ?: coder("libopus", AudioCodec.Opus.label)).let { listOf("-c:a", it) + DeskEncoders.audioExtra(it) } + bitrate + listOf("-f", "ogg")
            AudioFormat.Mp3 -> listOf("-c:a", coder("libmp3lame", "MP3")) + bitrate + listOf("-f", "mp3")
        }
        return Ffmpeg(listOf("-f", if (floats) "f32le" else "s16le", "-ar", "$rate", "-ac", "2", "-i", "-", "-ar", "$rate", "-ac", "${s.audio.channels}") + encode + listOf("-y", result.path), log)
    }

    override fun frameTimes(file: File, decoded: Boolean, from: String): List<Long> {
        val process = start(listOf(ffprobe, "-v", "error", "-select_streams", "v:0", "-read_intervals", from, "-show_entries", "${if (decoded) "frame" else "packet"}=pts_time", "-of", "csv=p=0", file.path))
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }.lines().mapNotNull { it.trim().trimEnd(',').toDoubleOrNull()?.let { t -> (t * 1e6).toLong() } }
    }

    private fun watched(command: List<String>, spanUs: Long, most: Float, failed: () -> ExportError, progress: (Float) -> Unit, alive: () -> Boolean) {
        val process = try { start(command) } catch (_: java.io.IOException) { throw failed() }
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (!alive()) throw CancellationException()
                if (line.startsWith("out_time_us=") && spanUs > 0) line.substringAfter('=').toLongOrNull()?.let { progress((it.toFloat() / spanUs).coerceIn(0f, most)) }
            }
            if (process.waitFor() != 0) throw failed()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    override fun compress(input: File, job: VideoJob, audio: Boolean, durationUs: Long, out: File, startUs: Long, endUs: Long, progress: (Float) -> Unit, alive: () -> Boolean) {
        val settings = ExportSettings(min(job.width, job.height), job.fps)
        val encoder = DeskEncoders.video(Codec.H264).firstOrNull { DeskEncoders.works(it, settings, job.width, job.height) } ?: throw ExportError(txt(R.string.library_text_value, "no video encoder"))
        val args = DeskEncoders.videoArgs(encoder, settings, job.width, job.height).toMutableList()
        args[args.indexOf("-b:v") + 1] = "${job.bitrate}"
        val color = runCatching { app.absentmotion.desk.DeskProbe.of(input).video }.getOrNull()
        for ((flag, value) in listOf("-colorspace" to color?.space, "-color_primaries" to color?.primaries, "-color_trc" to color?.transfer, "-color_range" to color?.range)) {
            if (!value.isNullOrBlank() && value != "unknown" && value != "gbr" && !value.startsWith("reserved") && flag in args) args[args.indexOf(flag) + 1] = value
        }
        val sound = when { !audio || job.audioKbps <= 0 -> listOf("-an"); job.copyAudio -> listOf("-c:a", "copy"); else -> listOf("-c:a", DeskEncoders.audio(AudioCodec.Aac, job.audioKbps).firstOrNull() ?: "aac", "-b:a", "${job.audioKbps}k") }
        val shownUs = if (startUs > 0 && endUs > startUs) shownFrom(input, startUs, endUs) else startUs
        val late = audio && shownUs < startUs
        val range = (if (startUs > 0) listOf("-ss", seconds(shownUs)) else emptyList()) + listOf("-noautorotate", "-i", input.path) +
            (if (late) listOf("-ss", seconds(startUs), "-i", input.path) else emptyList()) + (if (endUs > startUs) listOf("-t", seconds(endUs - startUs)) else emptyList())
        val upload = if (encoder.endsWith("_vaapi")) ",format=nv12,hwupload" else ""
        val command = listOf(ffmpeg, "-y", "-v", "error", "-progress", "pipe:1", "-nostats") + DeskEncoders.device(encoder) + range + listOf("-map", "0:v:0") +
            (if (audio) listOf("-map", if (late) "1:a:0?" else "0:a:0?") else emptyList()) + listOf("-vf", "scale=${job.width}:${job.height},fps=${job.fps}$upload") + args + sound + listOf("-movflags", "+faststart", out.path)
        watched(command, durationUs, 1f, { ExportError(txt(R.string.library_text_value, "ffmpeg")) }, progress, alive)
    }

    private fun shownFrom(input: File, startUs: Long, endUs: Long): Long = runCatching {
        val process = start(listOf(ffprobe, "-v", "error", "-select_streams", "v:0", "-read_intervals", "${seconds(startUs)}%${seconds(endUs)}",
            "-show_entries", "packet=pts_time", "-of", "csv=p=0", input.path))
        val times = process.inputStream.bufferedReader().readLines().mapNotNull { line -> line.trim().trimEnd(',').toDoubleOrNull()?.let { Math.round(it * 1_000_000) } }
        process.waitFor()
        firstShownUs(times.asSequence(), startUs, endUs)
    }.getOrDefault(startUs)

    private fun tool(args: List<String>, spanUs: Long, progress: (Float) -> Unit, alive: () -> Boolean) =
        watched(listOf(ffmpeg, "-y", "-v", "error", "-progress", "pipe:1", "-nostats") + args, spanUs, 0.99f, { ExportError(txt(R.string.tools_engine_stopped)) }, progress, alive)

    override fun extractAudio(input: File, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) {
        val coder = when {
            job.copy -> listOf("-c:a", "copy")
            job.mp3 -> listOf("-c:a", "libmp3lame".takeIf { it in DeskEncoders.available } ?: throw ExportError(txt(R.string.export_support_no_encoder, "MP3")), "-b:a", "${job.kbps}k")
            else -> listOf("-c:a", DeskEncoders.audio(AudioCodec.Aac, job.kbps).firstOrNull() ?: "aac", "-b:a", "${job.kbps}k")
        }
        tool(listOf("-i", input.path, "-vn", "-map", "0:a:0") + coder + (if (job.mp3) emptyList() else listOf("-movflags", "+faststart")) + out.path, durationUs, progress, alive)
    }

    override fun trim(input: File, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) {
        val range = (if (startUs > 0) listOf("-ss", seconds(startUs)) else emptyList()) + listOf("-i", input.path, "-t", seconds(endUs - startUs))
        tool(range + listOf("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", out.path), endUs - startUs, progress, alive)
    }

    private fun waited(args: List<String>, alive: () -> Boolean, problem: String) {
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) if (!alive()) throw CancellationException()
            check(process.exitValue() == 0) { problem }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
        }
    }

    override fun reverse(parts: ReverseParts, progress: (Float) -> Unit, alive: () -> Boolean): Pair<Int, Int>? {
        val count = ceil(parts.total.toDouble() / parts.perPart).toInt()
        val problem = AppText.get(R.string.ai_unable_to_process)
        fun time(us: Long) = String.format(Locale.US, "%.6f", us / 1_000_000.0)
        val list = File(parts.folder, "list.txt")
        list.bufferedWriter().use { writer ->
            for (i in count - 1 downTo 0) {
                if (!alive()) throw CancellationException()
                val from = i.toLong() * parts.perPart
                val frames = minOf(parts.perPart, parts.total - i * parts.perPart)
                val start = maxOf(parts.firstUs, parts.firstUs + Math.round((from - 0.5) * 1_000_000 / parts.fps))
                val part = File(parts.folder, "$i.mkv")
                val args = mutableListOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-noautorotate", "-ss", time(start), "-t", time(Math.round(frames * 1_000_000 / parts.fps)), "-i", parts.input.path,
                    "-map", "0:v:0", "-map", "0:a:0?", "-frames:v", "$frames", "-vf", "reverse,setpts=PTS-STARTPTS,pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p")
                if (parts.sound) args += listOf("-af", "areverse,asetpts=PTS-STARTPTS", "-c:a", "pcm_s16le")
                args += part.path
                waited(args, alive, problem)
                writer.write("file '$i.mkv'\n")
                progress((count - i).toFloat() / (count + 1))
            }
        }
        waited(listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list.path, "-c:v", "copy", "-c:a", "aac", parts.output.path), alive, problem)
        return null
    }

    override fun gifVideo(plan: GifPlan, alive: () -> Boolean) {
        val encoder = if ("libx264" in DeskEncoders.available) listOf("-c:v", "libx264")
            else DeskEncoders.video(Codec.H264).firstOrNull { DeskEncoders.works(it, ExportSettings(minOf(plan.width, plan.height), 30), plan.width, plan.height) }?.let { listOf("-c:v", it, "-b:v", "20M") } ?: listOf("-c:v", "libx264")
        fun encode(file: File, mask: Boolean) {
            if (!alive()) throw CancellationException()
            val filter = "scale=${plan.width}:${plan.height}:flags=neighbor,format=rgba," +
                if (mask) "alphaextract,format=yuv420p" else "premultiply=inplace=1,format=yuv420p"
            val process = start(listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-ignore_loop", "1", "-min_delay", "2", "-default_delay", "10",
                "-i", plan.source.path, "-vf", filter, "-fps_mode", "vfr") + encoder + listOf("-pix_fmt", "yuv420p", "-an", file.path))
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) if (!alive()) throw CancellationException()
                check(process.exitValue() == 0) { "GIF conversion failed" }
            } finally { if (process.isAlive) process.destroyForcibly() }
            if (!alive()) throw CancellationException()
        }
        encode(plan.colour, false)
        plan.matte?.let { encode(it, true) }
    }

    override fun steadyFrames(file: File, start: String, length: String, rate: String, width: Int, height: Int): MediaStream {
        val started = start(listOf(ffmpeg, "-nostdin", "-v", "error", "-ss", start, "-noautorotate", "-i", file.path, "-t", length,
            "-vf", "fps=$rate" + (if (width > 0) ",scale=$width:$height" else ""), "-pix_fmt", "rgb24", "-f", "rawvideo", "-"))
        return MediaStream(started.inputStream, started)
    }

    override fun frameSink(out: File, spec: FrameSinkSpec): MediaSink {
        val args = mutableListOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", spec.format, "-s", "${spec.width}x${spec.height}", "-r", spec.rate, "-i", "-")
        if (spec.audioFrom != null) {
            if (spec.audioStart != null) args += listOf("-ss", spec.audioStart, "-t", spec.audioLength.orEmpty())
            args += listOf("-i", spec.audioFrom.path, "-map", "0:v:0", "-map", "1:a:0?")
        }
        if (spec.start != null) args += listOf("-vf", "setpts=if(eq(N\\,0)\\,0\\,(N-1)/${spec.rate}/TB+${spec.start}/TB)", "-fps_mode", "vfr")
        if (spec.audioFrom == null) args += "-an"
        args += listOf("-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p")
        if (spec.audioFrom != null) args += listOf("-c:a", "aac")
        if (spec.limit != null) args += listOf("-t", spec.limit)
        args += out.path
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return object : MediaSink() {
            override val output = process.outputStream
            override fun finish(): Boolean { runCatching { output.close() }; return process.waitFor() == 0 }
            override fun problem() = "ffmpeg stopped"
            override fun close() { if (process.isAlive) process.destroyForcibly() }
        }
    }

    override fun speech(input: File, out: File, rate: Int, tempo: Float, pitch: Float, timeoutMs: Long): Boolean {
        val process = ProcessBuilder(SpeechCommands.convert(ffmpeg, input, SpeechRun(emptyList(), tempo = tempo, pitch = pitch), out))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        try { return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0 } finally { if (process.isAlive) process.destroyForcibly() }
    }

    override fun cameraDevices(): CameraDevices = DeskCameraSource.list(ffmpeg)

    override fun cameraEncoder(): List<String> = DeskCameraSource.videoEncoder(DeskCameraSource.encoders(ffmpeg))

    override fun camera(input: CameraInput, record: File?, encoder: List<String>, onFrame: (ByteArray) -> Unit, onEnd: (Int, String) -> Unit): CameraFeed =
        DeskCameraSession(DeskCameraSource.command(ffmpeg, input, record, encoder), onFrame, onEnd)

    override fun downloadOptions(plan: Plan, mode: SaveMode, dir: File): List<String> {
        val chosen = listOfNotNull(plan.video?.id, plan.audio?.id).distinct().joinToString("+")
        return listOf("--ffmpeg-location", ffmpeg, "-f", chosen, "-o", File(dir, "item.%(ext)s").path) + when {
            mode == SaveMode.AudioOnly -> listOf("-x", "--audio-format", "m4a")
            plan.video != null -> listOf("--merge-output-format", if (plan.webm) "webm" else "mp4")
            else -> emptyList()
        }
    }

    override fun downloaded(plan: Plan, mode: SaveMode, dir: File): File? =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("item.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.maxByOrNull { it.length() }

    fun merge(video: File, audio: File, out: File): Boolean = runCatching {
        ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", video.path, "-i", audio.path, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", out.path).redirectErrorStream(true).start()
            .let { it.inputStream.readBytes(); it.waitFor() }
    }.getOrDefault(-1) == 0 && out.length() > 0

    override fun withoutAudio(file: File): File {
        val out = File(file.parentFile, "silent." + file.extension)
        val code = runCatching {
            ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", file.path, "-map", "0:v", "-c", "copy", out.path).redirectErrorStream(true).start()
                .let { it.inputStream.readBytes(); it.waitFor() }
        }.getOrDefault(-1)
        return if (code == 0 && out.length() > 0) out else file
    }
}
