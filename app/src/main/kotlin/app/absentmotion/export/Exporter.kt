package app.absentmotion.export

import app.absentmotion.platform.Context
import app.absentmotion.platform.Bitmap
import app.absentmotion.platform.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.MediaAccess
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportResult(val uris: List<Uri>, val mime: String, val where: String)

class Exporter(context: Context, private val engine: EngineThread, private val store: ExportStore, private val access: MediaAccess = MediaAccess.forApp(context)) {
    private val context = context.applicationContext

    suspend fun export(kind: ExportKind, scene: Scene, s: ExportSettings, frame: Int, name: String, progress: (Float) -> Unit): ExportResult {
        val saved = ArrayList<Uri>()
        try {
            return withContext(Dispatchers.Default) {
                val xml = scene.toXml()
                when (kind) {
                    ExportKind.Video -> viaCache(saved, name, "mp4", "video/mp4", "Movies/$AppFolder") { out ->
                        exportVideo(engine, scene, xml, s, out, ExportAudio.mixFor?.invoke(scene), progress)
                    }
                    ExportKind.Gif -> viaCache(saved, name, "gif", "image/gif", "Pictures/$AppFolder") { out -> gif(scene, xml, s, out, progress) }
                    ExportKind.Frame, ExportKind.FrameAs -> {
                        val shape = if (kind == ExportKind.Frame) s.copy(image = ImageKind.Png) else s
                        single(saved, name, shape.image.ext, shape.image.mime, "Pictures/$AppFolder") { out ->
                            engine.exportSession(xml) {
                                val bytes = still(scene.frameStart(frame), scene.width, scene.height) ?: error("The engine could not draw the frame")
                                out.write(encode(bytes, scene.width, scene.height, shape, reuse = null))
                            }
                        }
                    }
                    ExportKind.Xml -> single(saved, name, "xml", "text/xml", "Documents/$AppFolder") { it.write(xml.toByteArray()) }
                    ExportKind.Package -> viaCache(saved, name, "absproj", "application/octet-stream", "Documents/$AppFolder") { out -> pack(scene, xml, out, progress) }
                    ExportKind.Sequence -> sequence(saved, scene, xml, s, name, progress)
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { saved.forEach { runCatching { store.delete(it) } } }
            throw t
        }
    }

    private suspend fun save(saved: MutableList<Uri>, folder: String?, name: String, mime: String, write: (java.io.OutputStream) -> Unit) =
        withContext(Dispatchers.IO) { saved.add(store.save(folder, name, mime, write)) }

    private suspend fun single(saved: MutableList<Uri>, name: String, ext: String, mime: String, where: String, write: suspend (java.io.OutputStream) -> Unit): ExportResult {
        val buffer = ByteArrayOutputStream()
        write(buffer)
        save(saved, null, "$name.$ext", mime) { it.write(buffer.toByteArray()) }
        return ExportResult(saved, mime, store.location(where))
    }

    private suspend fun viaCache(saved: MutableList<Uri>, name: String, ext: String, mime: String, where: String, make: suspend (File) -> Unit): ExportResult {
        val temp = File(context.cacheDir, "export-${System.nanoTime()}.$ext")
        try {
            make(temp)
            save(saved, null, "$name.$ext", mime) { out -> temp.inputStream().use { it.copyTo(out) } }
            return ExportResult(saved, mime, store.location(where))
        } finally {
            withContext(NonCancellable) { temp.delete() }
        }
    }

    private suspend fun gif(scene: Scene, xml: String, s: ExportSettings, out: File, progress: (Float) -> Unit) {
        val (width, height) = exportSize(scene, s.shortSide, even = false)
        val frames = frameCount(scene, s.fps)
        engine.exportSession(xml, s.interpolation, s.fps) {
            out.outputStream().buffered().use { stream ->
                val writer = GifWriter(stream, width, height)
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: error("The engine could not draw frame $k")
                    withContext(Dispatchers.Default) { writer.frame(ImageEncode.flatten(pixels, s.background), (k + 1) * 1000L / s.fps) }
                    progress((k + 1f) / frames)
                }
                writer.finish()
            }
        }
    }

    private suspend fun sequence(saved: MutableList<Uri>, scene: Scene, xml: String, s: ExportSettings, name: String, progress: (Float) -> Unit): ExportResult {
        val (width, height) = exportSize(scene, s.shortSide, even = false)
        val frames = frameCount(scene, s.fps)
        val bitmap = app.absentmotion.platform.makeBitmap(width, height)
        try {
            engine.exportSession(xml, s.interpolation, s.fps) {
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: error("The engine could not draw frame $k")
                    val bytes = withContext(Dispatchers.Default) { encode(pixels, width, height, s, bitmap) }
                    save(saved, name, sequenceFrameName(k, s.image.ext), s.image.mime) { it.write(bytes) }
                    progress((k + 1f) / frames)
                }
            }
        } finally {
            app.absentmotion.platform.recycleBitmap(bitmap)
        }
        return ExportResult(saved, s.image.mime, store.location("Pictures/$AppFolder/$name"))
    }

    private suspend fun pack(scene: Scene, xml: String, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        writeProjectPackage(scene, xml, out.outputStream(), access, { uri -> runCatching { context.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() }, progress)
    }

    private fun encode(pixels: ByteArray, width: Int, height: Int, settings: ExportSettings, reuse: Bitmap?): ByteArray {
        return app.absentmotion.platform.encodeRgba(pixels, width, height, settings, reuse)
    }
}

internal suspend fun writeProjectPackage(
    scene: Scene, xml: String, out: OutputStream, access: MediaAccess, open: (String) -> InputStream?, progress: (Float) -> Unit,
) {
    val uris = scene.node.mediaUris().toList()
    val manifest = JSONObject().put("format", "absentmotion-package").put("version", 1)
        .put("title", scene.title).put("project", "project.xml")
    val media = JSONArray()
    ZipOutputStream(out.buffered()).use { zip ->
        zip.putNextEntry(ZipEntry("project.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        zip.setLevel(0)
        uris.forEachIndexed { i, uri ->
            currentCoroutineContext().ensureActive()
            val declared = scene.media(uri)
            val file = (declared?.filename?.takeIf { it.isNotEmpty() } ?: Uri.parse(uri).lastPathSegment ?: "media").substringAfterLast('/')
            val entry = "media/${i + 1}_${safeName(file)}"
            val input = if (access.allows(uri)) open(uri) else null
            if (input != null) {
                zip.putNextEntry(ZipEntry(entry))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
            }
            media.put(JSONObject().put("uri", uri).put("file", file).apply { if (input != null) put("entry", entry) })
            progress((i + 1f) / uris.size * 0.95f)
        }
        zip.setLevel(6)
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.put("media", media).toString(1).toByteArray()); zip.closeEntry()
    }
    progress(1f)
}
