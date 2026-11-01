package app.absentmotion.pc.media

import app.absentmotion.model.*
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.project.ProjectState
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.Image as SkiaImage

fun inspectMedia(file: File): MediaDeclaration? {
    val type = Files.probeContentType(file.toPath()) ?: when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4", "mov", "mkv", "webm" -> "video/${file.extension.lowercase()}"
        "mp3", "wav", "m4a", "ogg" -> "audio/${file.extension.lowercase()}"
        else -> return null
    }
    if (!type.startsWith("image/") && !type.startsWith("video/") && !type.startsWith("audio/")) return null
    var width = 0
    var height = 0
    var duration = 0L
    if (type.startsWith("image/")) {
        val image = SkiaImage.makeFromEncoded(file.readBytes())
        width = image.width
        height = image.height
    } else {
        val process = ProcessBuilder(DesktopPlatform.executable("ffprobe"), "-v", "error", "-show_entries", "stream=width,height:format=duration", "-of", "json", file.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val result = JSONObject(process.inputStream.bufferedReader().readText())
        if (process.waitFor() != 0) return null
        val streams = result.optJSONArray("streams")
        val video = (0 until (streams?.length() ?: 0)).mapNotNull { streams?.optJSONObject(it) }.firstOrNull { it.optInt("width") > 0 }
        width = video?.optInt("width") ?: 0
        height = video?.optInt("height") ?: 0
        duration = ((result.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        if (duration <= 0 || (type.startsWith("video/") && (width <= 0 || height <= 0))) return null
    }
    return MediaDeclaration(XmlNode("media", mapOf("uri" to file.toURI().toString(), "filename" to file.name, "title" to file.nameWithoutExtension, "type" to type, "width" to "$width", "height" to "$height", "duration" to "$duration", "size" to "${file.length()}")))
}

fun addMedia(state: ProjectState, media: MediaDeclaration) {
    val scene = state.scene
    val frame = scene.frameAt(state.time)
    val duration = if (media.type.startsWith("image/")) 5000 else (media.duration / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (duration <= 0) return
    val attrs = linkedMapOf("id" to "${Layer.newId()}", "label" to media.title, "startTime" to "${scene.frameStart(frame)}", "endTime" to "${scene.endTimeFor(frame, duration)}", "mediaFillMode" to "fill")
    val layer = if (media.type.startsWith("audio/")) {
        Layer(XmlNode("audio", attrs + mapOf("src" to media.uri, "outTime" to "$duration")))
    } else {
        if (media.width <= 0 || media.height <= 0) return
        val fit = minOf(scene.width.toFloat() / media.width, scene.height.toFloat() / media.height)
        val video = media.type.startsWith("video/")
        attrs["fillType"] = "media"
        attrs[if (video) "fillVideo" else "fillImage"] = media.uri
        if (video) attrs["outTime"] = "$duration"
        attrs["s"] = ".rect"
        Layer(XmlNode("shape", attrs)).withTransform("location", scene.width / 2f, scene.height / 2f, 0f).withProp("size", "vec2", media.width * fit / 2f, media.height * fit / 2f)
    }
    state.edit { it.add(layer, listOf(media)) }
    state.selected = layer.id
}
