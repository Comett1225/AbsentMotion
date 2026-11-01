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

fun importMedia(files: List<File>, project: File?): List<MediaDeclaration> = files.mapNotNull { source ->
    runCatching {
        val checked = inspectMedia(source) ?: return@runCatching null
        if (project == null) checked else {
            val folder = project.parentFile.resolve("${project.nameWithoutExtension}_media").also { it.mkdirs() }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().take(8).joinToString("") { "%02x".format(it) }
            val target = folder.resolve("$hash-${source.name}")
            if (!target.exists()) source.copyTo(target)
            MediaDeclaration(checked.node.with("uri", target.toURI().toString()))
        }
    }.getOrNull()
}

fun mediaThumbnail(media: MediaDeclaration, base: File?, cacheDir: File = DesktopPlatform.dataDir.resolve("thumbnails")): File? {
    val source = runCatching {
        val uri = java.net.URI(media.uri)
        if (uri.scheme == "file") File(uri) else base?.resolve(media.uri) ?: File(media.uri)
    }.getOrNull()?.takeIf { it.isFile } ?: return null
    if (media.type.startsWith("image/")) return source
    if (!media.type.startsWith("video/")) return null
    val id = "${source.canonicalPath}:${source.length()}:${source.lastModified()}"
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    val cache = cacheDir.also { it.mkdirs() }.resolve("$hash.png")
    if (cache.isFile) return cache
    val temp = File.createTempFile("thumbnail-", ".png", cache.parentFile)
    return try {
        val process = ProcessBuilder(DesktopPlatform.executable("ffmpeg"), "-v", "error", "-i", source.absolutePath, "-frames:v", "1", "-vf", "scale=256:256:force_original_aspect_ratio=decrease", "-y", temp.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (process.waitFor() == 0 && temp.length() > 0) { temp.copyTo(cache, overwrite = true); cache } else null
    } finally { temp.delete() }
}

fun installFileDrop(window: java.awt.Window, receive: (List<File>) -> Unit): () -> Unit {
    val target = java.awt.dnd.DropTarget(window, java.awt.dnd.DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
        override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
            event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
            val files = runCatching { (event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>().orEmpty() }.getOrDefault(emptyList())
            event.dropComplete(files.isNotEmpty())
            if (files.isNotEmpty()) receive(files)
        }
    }, true)
    return { target.isActive = false; window.dropTarget = null }
}
