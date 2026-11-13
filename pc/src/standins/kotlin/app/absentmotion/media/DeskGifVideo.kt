package app.absentmotion.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.absentmotion.ai.aiFile
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    var success = false
    try {
        MediaEngine.current.gifVideo(GifPlan(source, colour, matte, width, height)) { task.isActive }
        task.ensureActive()
        fun declare(file: File) = MediaDeclaration(XmlNode("media", linkedMapOf(
            "uri" to FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString(), "filename" to file.name,
            "title" to source.name, "type" to "video/mp4", "size" to "${file.length()}", "orientation" to "0",
            "duration" to "${summary.durationMs * 1000}", "fps" to fpsText(fps.toFloat()), "width" to "$width", "height" to "$height",
            "infoUpdated" to "${System.currentTimeMillis()}", "audio" to "none",
        )))
        return GifVideo(declare(colour), matte?.let(::declare), width, height, files).also { success = true }
    } finally { if (!success) files.forEach { it.delete() } }
}
