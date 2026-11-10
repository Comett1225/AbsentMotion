package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.R
import app.absentmotion.library.openableUri
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.fpsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal suspend fun desktopReverse(context: Context, layer: Layer, media: MediaDeclaration, progress: AiProgress): ReversedClip {
    val input = Uri.parse(openableUri(checkNotNull(layer.mediaFill).uri)).toFile() ?: error(AppText.get(R.string.ai_unable_to_process))
    val probe = DeskProbe.of(input)
    val video = probe.streams.firstOrNull { it.type == "video" } ?: error(AppText.get(R.string.ai_select_video_layer))
    val end = video.durationUs.takeIf { it > 0 } ?: probe.durationUs
    check(end > 0 && video.width > 0 && video.height > 0) { AppText.get(R.string.ai_unable_to_process) }
    val used = layer.usedMediaMs()
    val first = if (end <= 60_000_000) 0L else (used.start * 1000).toLong().coerceIn(0, end - 1)
    val last = if (end <= 60_000_000) end else (used.endInclusive * 1000).toLong().coerceIn(first + 1, end)
    val fps = video.fps.takeIf { it > 0 } ?: 30.0
    val frameBytes = video.width.toLong() * video.height * 4
    val total = Math.round((last - first) / 1_000_000.0 * fps).toInt().coerceAtLeast(1)
    val perPart = (128_000_000.0 / frameBytes).toInt().coerceIn(1, (2.0 * fps).toInt().coerceAtLeast(1))
    val count = ceil(total.toDouble() / perPart).toInt()
    check(count in 1..2000) { AppText.get(R.string.ai_unable_to_process) }
    val folder = File(context.cacheDir, "reverse-${UUID.randomUUID()}")
    check(folder.mkdirs()) { AppText.get(R.string.ai_unable_to_process) }
    val output = aiFile(context, "mp4")
    var success = false
    try {
        val list = File(folder, "list.txt")
        list.bufferedWriter().use { writer ->
            for (i in count - 1 downTo 0) {
                currentCoroutineContext().ensureActive()
                val from = i.toLong() * perPart
                val frames = minOf(perPart, total - i * perPart)
                val start = maxOf(first, first + Math.round((from - 0.5) * 1_000_000 / fps))
                val part = File(folder, "$i.mkv")
                val args = mutableListOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-noautorotate", "-ss", time(start), "-t", time(Math.round(frames * 1_000_000 / fps)), "-i", input.path,
                    "-map", "0:v:0", "-map", "0:a:0?", "-frames:v", "$frames", "-vf", "reverse,setpts=PTS-STARTPTS,pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p")
                if (probe.audio != null) args += listOf("-af", "areverse,asetpts=PTS-STARTPTS", "-c:a", "pcm_s16le")
                args += part.path
                reverseProcess(args)
                writer.write("file '$i.mkv'\n")
                progress((count - i).toFloat() / (count + 1), AppText.get(R.string.speed_reversing))
            }
        }
        reverseProcess(listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list.path, "-c:v", "copy", "-c:a", "aac", output.path))
        val width = (video.width + 1) and 1.inv()
        val height = (video.height + 1) and 1.inv()
        val result = aiMedia(context, output, "video/mp4", mapOf("width" to "$width", "height" to "$height", "orientation" to "${video.rotation}", "duration" to "${last - first}", "fps" to fpsText(fps.toFloat())))
        success = true
        return ReversedClip(result, last)
    } finally {
        folder.deleteRecursively()
        if (!success) output.delete()
    }
}

private fun time(us: Long): String = String.format(Locale.US, "%.6f", us / 1_000_000.0)

private suspend fun reverseProcess(args: List<String>) {
    val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    try {
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
        check(process.exitValue() == 0) { AppText.get(R.string.ai_unable_to_process) }
    } finally {
        if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
    }
}
