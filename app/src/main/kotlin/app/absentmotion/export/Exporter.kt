package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Context
import app.absentmotion.platform.Bitmap
import app.absentmotion.platform.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.modelFolder
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaName
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
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportResult(val uris: List<Uri>, val mime: String, val where: String, val verification: Verification? = null)

class Exporter(context: Context, private val engine: EngineThread, private val store: ExportStore, private val access: MediaAccess = MediaAccess.forApp(context)) {
    private val context = context.applicationContext

    suspend fun export(kind: ExportKind, scene: Scene, s: ExportSettings, frame: Int, name: String, progress: (Float) -> Unit): ExportResult {
        val saved = ArrayList<Uri>()
        try {
            return withContext(Dispatchers.Default) {
                val xml = scene.toXml()
                when (kind) {
                    ExportKind.Video -> s.fixedFormat().let { f ->
                        val started = android.os.SystemClock.elapsedRealtime()
                        var paused = 0L
                        val mix = ExportAudio.mixFor?.invoke(scene, f.audio.effectiveRate)
                        direct(saved, name, f.container.ext, f.container.mime, "Movies/$AppFolder") { fd ->
                            cool { thermal ->
                                exportVideo(engine, scene, xml, f, fd, mix, thermal.hot, { thermal.pauseMs().also { paused += it } }, progress)
                            }
                        }.also {
                            val (w, h) = exportSize(scene, f.shortSide, even = true)
                            val ms = android.os.SystemClock.elapsedRealtime() - started - paused
                            ExportTimes(context).record(ExportRecord(f.codec, w, h, f.fps, frameCount(scene, f.fps), ms.coerceAtLeast(1), f.interpolation))
                        }.verified(kind, scene, f, mix != null)
                    }
                    ExportKind.Audio -> {
                        val mix = ExportAudio.mixFor?.invoke(scene, audioRate(s)) ?: throw ExportError(txt(R.string.export_error_no_sound))
                        try {
                            direct(saved, name, s.audioFormat.ext, s.audioFormat.mime, "Music/$AppFolder") { fd ->
                                exportAudio(s, (scene.totalTime + 1) * 1000L, mix, fd, progress)
                            }.verified(kind, scene, s, true)
                        } finally {
                            (mix as? java.io.Closeable)?.close()
                        }
                    }
                    ExportKind.Gif -> direct(saved, name, "gif", "image/gif", "Pictures/$AppFolder") { fd -> cool { gif(scene, xml, s, fdStream(fd), it::pauseMs, progress) } }
                    ExportKind.Frame, ExportKind.FrameAs -> {
                        val shape = if (kind == ExportKind.Frame) s.copy(image = ImageKind.Png) else s
                        single(saved, name, shape.image.ext, shape.image.mime, "Pictures/$AppFolder") { out ->
                            engine.exportSession(xml) {
                                val bytes = still(scene.frameStart(frame), scene.width, scene.height) ?: throw ExportError(txt(R.string.export_error_draw_still))
                                out.write(encode(bytes, scene.width, scene.height, shape, reuse = null))
                            }
                        }
                    }
                    ExportKind.Xml -> single(saved, name, "xml", "text/xml", "Documents/$AppFolder") { it.write(xml.toByteArray()) }
                    ExportKind.Package -> direct(saved, name, "absproj", "application/octet-stream", "Documents/$AppFolder") { fd -> pack(scene, xml, fdStream(fd), progress) }
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

    private suspend fun direct(saved: MutableList<Uri>, name: String, ext: String, mime: String, where: String, make: suspend (java.io.FileDescriptor) -> Unit): ExportResult {
        val file = withContext(Dispatchers.IO) { store.open(null, "$name.$ext", mime) }
        try {
            make(file.fd)
            withContext(Dispatchers.IO) { file.publish() }
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { file.discard() }
            throw if (t !is kotlinx.coroutines.CancellationException && freeSpace() < MinFreeBytes) StorageFullException() else t
        }
        saved.add(file.uri)
        return ExportResult(saved, mime, store.location(where))
    }

    private suspend fun ExportResult.verified(kind: ExportKind, scene: Scene, s: ExportSettings, hasAudio: Boolean): ExportResult =
        expectationFor(kind, scene, s, hasAudio)?.let { ExportResult(uris, mime, where, verifyExport(context, uris.first(), it)) } ?: this

    private suspend fun <T> cool(block: suspend (ThermalMonitor) -> T): T {
        val thermal = ThermalMonitor.of(context)
        thermal.start()
        try {
            android.util.Log.i("AMExport", "thermal ${thermal.refresh()} pause=${thermal.pauseMs()}")
            return block(thermal)
        } finally {
            thermal.stop()
        }
    }

    private suspend fun gif(scene: Scene, xml: String, s: ExportSettings, out: OutputStream, pauseMs: () -> Long, progress: (Float) -> Unit) {
        val (width, height) = exportSize(scene, s.shortSide, even = false)
        val frames = frameCount(scene, s.fps)
        engine.exportSession(xml, s.interpolation, s.fps) {
            out.buffered().use { stream ->
                val writer = GifWriter(stream, width, height)
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: throw ExportError(txt(R.string.export_error_draw_frame, k.toString()))
                    withContext(Dispatchers.Default) { writer.frame(ImageEncode.flatten(pixels, s.background), (k + 1) * 1000L / s.fps) }
                    progress((k + 1f) / frames)
                    if (k % 30 == 0) ensureStorage()
                    pauseMs().takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it) }
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
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: throw ExportError(txt(R.string.export_error_draw_frame, k.toString()))
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

    private suspend fun pack(scene: Scene, xml: String, out: OutputStream, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        writeProjectPackage(scene, xml, out, access, { uri -> runCatching { context.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() }, progress)
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
            val file = scene.mediaName(uri)
            val folder = if (access.allows(uri)) modelFolder(uri) else null
            val record = JSONObject().put("uri", uri).put("file", file)
            if (folder != null) {
                val main = File(URI(uri)).canonicalFile
                val parts = folder.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
                for (part in parts) {
                    zip.putNextEntry(ZipEntry("media/${i + 1}_model/${part.name}"))
                    part.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                record.put("entry", "media/${i + 1}_model/${main.name}")
                    .put("companions", JSONArray(parts.filter { it != main }.map { "media/${i + 1}_model/${it.name}" }))
            } else {
                val entry = "media/${i + 1}_${safeName(file)}"
                val input = if (access.allows(uri)) open(uri) else null
                if (input != null) {
                    zip.putNextEntry(ZipEntry(entry))
                    input.use { it.copyTo(zip) }
                    zip.closeEntry()
                    record.put("entry", entry)
                }
            }
            media.put(record)
            progress((i + 1f) / uris.size * 0.95f)
        }
        zip.setLevel(6)
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.put("media", media).toString(1).toByteArray()); zip.closeEntry()
    }
    progress(1f)
}
