package app.absentmotion.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.absentmotion.ai.aiFile
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.Codec
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportSettings
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal suspend fun convertDesktopGif(context: Context, uri: Uri): GifVideo? {
    val source = requireNotNull(uri.toFile())
    val task = coroutineContext
    val summary = summarizeGif({ task.ensureActive() }) { source.inputStream() }
    if (summary.frames < 2) return null
    val fps = gifFrameRate(summary)
    val (width, height) = checkNotNull(gifEncodeSize(summary.width, summary.height) { w, h -> w <= 8192 && h <= 8192 })
    val colour = aiFile(context, "mp4")
    val matte = File(colour.parentFile, colour.nameWithoutExtension + "-mask.mp4").takeIf { summary.transparent }
    val files = listOfNotNull(colour, matte)
    val encoder = if ("libx264" in DeskEncoders.available) listOf("-c:v", "libx264")
        else DeskEncoders.video(Codec.H264).firstOrNull { DeskEncoders.works(it, ExportSettings(minOf(width, height), 30), width, height) }?.let { listOf("-c:v", it, "-b:v", "20M") } ?: listOf("-c:v", "libx264")
    var success = false
    try {
        suspend fun encode(file: File, mask: Boolean) {
            coroutineContext.ensureActive()
            val filter = "scale=$width:$height:flags=neighbor,format=rgba," +
                if (mask) "alphaextract,format=yuv420p" else "premultiply=inplace=1,format=yuv420p"
            val process = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-ignore_loop", "1", "-min_delay", "2", "-default_delay", "10",
                "-i", source.path, "-vf", filter, "-fps_mode", "vfr") + encoder + listOf("-pix_fmt", "yuv420p", "-an", file.path))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) coroutineContext.ensureActive()
                check(process.exitValue() == 0) { "GIF conversion failed" }
            } finally { if (process.isAlive) process.destroyForcibly() }
            coroutineContext.ensureActive()
        }
        encode(colour, false)
        matte?.let { encode(it, true) }
        fun declare(file: File) = MediaDeclaration(XmlNode("media", linkedMapOf(
            "uri" to FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString(), "filename" to file.name,
            "title" to source.name, "type" to "video/mp4", "size" to "${file.length()}", "orientation" to "0",
            "duration" to "${summary.durationMs * 1000}", "fps" to fpsText(fps.toFloat()), "width" to "$width", "height" to "$height",
            "infoUpdated" to "${System.currentTimeMillis()}", "audio" to "none",
        )))
        return GifVideo(declare(colour), matte?.let(::declare), width, height, files).also { success = true }
    } finally { if (!success) files.forEach { it.delete() } }
}
