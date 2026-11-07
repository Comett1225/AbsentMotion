package app.absentmotion.platform

import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.Codec
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.tools.VideoJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

suspend fun compressVideoOnDesktop(uri: String, job: VideoJob, audio: Boolean, durationUs: Long, out: File, progress: (Float) -> Unit, startUs: Long = 0, endUs: Long = 0) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val settings = ExportSettings(min(job.width, job.height), job.fps)
    val encoder = DeskEncoders.video(Codec.H264).firstOrNull { DeskEncoders.works(it, settings, job.width, job.height) } ?: throw ExportError(txt(R.string.compress_failed, "no video encoder"))
    val args = DeskEncoders.videoArgs(encoder, settings, job.width, job.height).toMutableList()
    args[args.indexOf("-b:v") + 1] = "${job.bitrate}"
    val sound = when { !audio || job.audioKbps <= 0 -> listOf("-an"); job.copyAudio -> listOf("-c:a", "copy"); else -> listOf("-c:a", DeskEncoders.audio(app.absentmotion.export.AudioCodec.Aac, job.audioKbps).firstOrNull() ?: "aac", "-b:a", "${job.audioKbps}k") }
    val shownUs = if (startUs > 0 && endUs > startUs) shownFrom(input, startUs, endUs) else startUs
    val late = audio && shownUs < startUs
    val range = (if (startUs > 0) listOf("-ss", seconds(shownUs)) else emptyList()) + listOf("-noautorotate", "-i", input.path) +
        (if (late) listOf("-ss", seconds(startUs), "-i", input.path) else emptyList()) + (if (endUs > startUs) listOf("-t", seconds(endUs - startUs)) else emptyList())
    val upload = if (encoder.endsWith("_vaapi")) ",format=nv12,hwupload" else ""
    val command = listOf(DeskMedia.ffmpeg, "-y", "-v", "error", "-progress", "pipe:1", "-nostats") + DeskEncoders.device(encoder) + range + listOf("-map", "0:v:0") +
        (if (audio) listOf("-map", if (late) "1:a:0?" else "0:a:0?") else emptyList()) + listOf("-vf", "scale=${job.width}:${job.height},fps=${job.fps}$upload") + args + sound + listOf("-movflags", "+faststart", out.path)
    val context = currentCoroutineContext()
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        process.inputStream.bufferedReader().forEachLine { line ->
            if (!context.isActive) throw CancellationException()
            if (line.startsWith("out_time_us=") && durationUs > 0) line.substringAfter('=').toLongOrNull()?.let { progress((it.toFloat() / durationUs).coerceIn(0f, 1f)) }
        }
        if (process.waitFor() != 0) throw ExportError(txt(R.string.compress_failed, "ffmpeg"))
    } finally {
        process.destroyForcibly()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}

private fun shownFrom(input: File, startUs: Long, endUs: Long): Long = runCatching {
    val process = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-select_streams", "v:0", "-read_intervals", "${seconds(startUs)}%${seconds(endUs)}",
        "-show_entries", "packet=pts_time", "-of", "csv=p=0", input.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val times = process.inputStream.bufferedReader().readLines().mapNotNull { line -> line.trim().trimEnd(',').toDoubleOrNull()?.let { Math.round(it * 1_000_000) } }
    process.waitFor()
    app.absentmotion.media.firstShownUs(times.asSequence(), startUs, endUs)
}.getOrDefault(startUs)
