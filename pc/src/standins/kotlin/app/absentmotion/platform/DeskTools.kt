package app.absentmotion.platform

import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportError
import app.absentmotion.i18n.txt
import app.absentmotion.tools.AudioJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

internal fun seconds(us: Long): String = String.format(Locale.ROOT, "%.6f", us / 1_000_000.0)

private suspend fun runFfmpeg(args: List<String>, spanUs: Long, progress: (Float) -> Unit) {
    val context = currentCoroutineContext()
    val process = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-y", "-v", "error", "-progress", "pipe:1", "-nostats") + args)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        process.inputStream.bufferedReader().forEachLine { line ->
            if (!context.isActive) throw CancellationException()
            if (line.startsWith("out_time_us=") && spanUs > 0) line.substringAfter('=').toLongOrNull()?.let { progress((it.toFloat() / spanUs).coerceIn(0f, 0.99f)) }
        }
        if (process.waitFor() != 0) throw ExportError(txt(R.string.tools_engine_stopped))
    } finally {
        process.destroyForcibly()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}

suspend fun extractAudioOnDesktop(uri: String, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val coder = when {
        job.copy -> listOf("-c:a", "copy")
        job.mp3 -> listOf("-c:a", "libmp3lame".takeIf { it in DeskEncoders.available } ?: throw ExportError(txt(R.string.export_support_no_encoder, "MP3")), "-b:a", "${job.kbps}k")
        else -> listOf("-c:a", DeskEncoders.audio(AudioCodec.Aac, job.kbps).firstOrNull() ?: "aac", "-b:a", "${job.kbps}k")
    }
    runFfmpeg(listOf("-i", input.path, "-vn", "-map", "0:a:0") + coder + (if (job.mp3) emptyList() else listOf("-movflags", "+faststart")) + out.path, durationUs, progress)
}

suspend fun trimCopyOnDesktop(uri: String, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val input = android.net.Uri.parse(uri).toFile() ?: throw ExportError(txt(R.string.compress_unreadable))
    val range = (if (startUs > 0) listOf("-ss", seconds(startUs)) else emptyList()) + listOf("-i", input.path, "-t", seconds(endUs - startUs))
    runFfmpeg(range + listOf("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", out.path), endUs - startUs, progress)
}
