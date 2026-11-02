package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Context
import app.absentmotion.platform.Bitmap
import app.absentmotion.platform.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.CrashLogs
import app.absentmotion.library.CrashReason
import app.absentmotion.library.crashReason
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.modelFolder
import app.absentmotion.model.Scene
import app.absentmotion.model.fontNames
import app.absentmotion.model.mediaName
import app.absentmotion.model.storedMediaUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun export(kind: ExportKind, scene: Scene, s: ExportSettings, frame: Int, name: String, note: (UiText) -> Unit = {}, progress: (Float) -> Unit): ExportResult {
        val saved = ArrayList<Uri>()
        try {
            return CrashLogs.during("exporting ${kind.name.lowercase()}") { withContext(Dispatchers.Default) {
                val xml = scene.toXml()
                when (kind) {
                    ExportKind.Video -> s.fixedFormat().let { f ->
                        formatProblem(f, false, android.os.Build.VERSION.SDK_INT)?.let { throw ExportError(it) }
                        val started = android.os.SystemClock.elapsedRealtime()
                        var paused = 0L
                        var mix: AudioMix? = null
                        var looks: FrameLooks? = null
                        val written = retryingEncoder(note) {
                            progress(0f)
                            mix = ExportAudio.mixFor?.invoke(scene, f.audio.effectiveRate)
                            try {
                                direct(saved, name, f.container.ext, f.container.mime, "Movies/$AppFolder") { fd ->
                                    watched { alive ->
                                        cool { thermal ->
                                            exportVideo(engine, scene, xml, f, fd, mix, thermal.hot, { thermal.pauseMs().also { paused += it } }, note, { looks = it }) { alive(); progress(it) }
                                        }
                                    }
                                }
                            } finally {
                                (mix as? java.io.Closeable)?.close()
                            }
                        }
                        note(txt(R.string.export_note_checking))
                        written.verified(kind, scene, f, mix != null, looks).unreadable(mix, scene).also {
                            val (w, h) = exportSize(scene, f.shortSide, even = true)
                            val ms = android.os.SystemClock.elapsedRealtime() - started - paused
                            ExportTimes(context).record(ExportRecord(f.codec, w, h, f.fps, frameCount(scene, f.fps), ms.coerceAtLeast(1), f.interpolation))
                        }
                    }
                    ExportKind.Audio -> {
                        val mix = ExportAudio.mixFor?.invoke(scene, audioRate(s)) ?: throw ExportError(txt(R.string.export_error_no_sound))
                        try {
                            direct(saved, name, s.audioFormat.ext, s.audioFormat.mime, "Music/$AppFolder") { fd ->
                                watched { alive -> exportAudio(s, (scene.totalTime + 1) * 1000L, mix, fd) { alive(); progress(it) } }
                            }.verified(kind, scene, s, true).unreadable(mix, scene)
                        } finally {
                            (mix as? java.io.Closeable)?.close()
                        }
                    }
                    ExportKind.Gif -> {
                        exportSize(scene, s.shortSide, even = false).let { (w, h) -> checkPicture(w, h) }
                        direct(saved, name, "gif", "image/gif", "Pictures/$AppFolder") { fd ->
                            watched { alive -> cool { gif(scene, xml, s, fdStream(fd), it::pauseMs) { p -> alive(); progress(p) } } }
                        }.verified(kind, scene, s, false)
                    }
                    ExportKind.Frame, ExportKind.FrameAs -> {
                        val shape = if (kind == ExportKind.Frame) s.copy(image = ImageKind.Png) else s
                        imageProblem(shape.image, android.os.Build.VERSION.SDK_INT)?.let { throw ExportError(it) }
                        checkPicture(scene.width, scene.height)
                        val pixels = engine.exportSession(xml) { still(scene.frameStart(frame), scene.width, scene.height) } ?: throw ExportError(txt(R.string.export_error_draw_still))
                        save(saved, null, "$name.${shape.image.ext}", shape.image.mime) { encode(pixels, scene.width, scene.height, shape, null, it) }
                        ExportResult(saved, shape.image.mime, store.location("Pictures/$AppFolder")).verified(kind, scene, shape, false)
                    }
                    ExportKind.Xml -> {
                        save(saved, null, "$name.xml", "text/xml") { it.write(xml.toByteArray()) }
                        ExportResult(saved, "text/xml", store.location("Documents/$AppFolder"))
                    }
                    ExportKind.Package -> direct(saved, name, "absproj", "application/octet-stream", "Documents/$AppFolder") { fd ->
                        watched { alive -> pack(scene, xml, fdStream(fd)) { alive(); progress(it) } }
                    }
                    ExportKind.Sequence -> watched { alive -> sequence(saved, scene, xml, s, name) { alive(); progress(it) } }
                }
            } }
        } catch (t: Throwable) {
            val full = storageFull(t)
            withContext(NonCancellable + Dispatchers.IO) { saved.forEach { runCatching { store.delete(it) } } }
            throw if (full) StorageFullException() else plainCodecError(t)
        }
    }

    private suspend fun <T> retryingEncoder(note: (UiText) -> Unit, attempt: suspend () -> T): T = try {
        attempt()
    } catch (t: Throwable) {
        if (!encoderTrouble(t)) throw t
        EncoderTrouble.blameLast()
        android.util.Log.w("AMExport", "the encoder failed, trying once more with another one", t)
        note(txt(R.string.export_note_retry))
        try {
            attempt()
        } catch (again: Throwable) {
            if (again is kotlinx.coroutines.CancellationException) throw again
            t.addSuppressed(again)
            throw t
        }
    }

    private fun plainCodecError(t: Throwable): Throwable {
        if (t is ExportError || t is kotlinx.coroutines.CancellationException) return t
        if (generateSequence(t) { it.cause }.none { it is android.media.MediaCodec.CodecException }) return t
        android.util.Log.w("AMExport", "a media codec failed", t)
        return ExportError(txt(R.string.export_error_codec_busy), t)
    }

    private fun storageFull(t: Throwable): Boolean =
        t !is kotlinx.coroutines.CancellationException && (crashReason(t, "") == CrashReason.Storage || runCatching { freeSpace() < MinFreeBytes }.getOrDefault(false))

    private suspend fun save(saved: MutableList<Uri>, folder: String?, name: String, mime: String, write: (java.io.OutputStream) -> Unit) =
        withContext(Dispatchers.IO) { saved.add(store.save(folder, name, mime, write)) }

    private suspend fun checkPicture(width: Int, height: Int) {
        val side = engine.maxPictureSize()
        exportCheck(width <= side && height <= side && stillFits(width, height)) { txt(R.string.export_error_too_large, "${width}x$height") }
    }

    private suspend fun direct(saved: MutableList<Uri>, name: String, ext: String, mime: String, where: String, make: suspend (java.io.FileDescriptor) -> Unit): ExportResult {
        val file = withContext(Dispatchers.IO) { store.open(null, "$name.$ext", mime) }
        val uri = try {
            make(file.fd)
            withContext(Dispatchers.IO) { file.publish() }
        } catch (t: Throwable) {
            val full = storageFull(t)
            withContext(NonCancellable + Dispatchers.IO) { file.discard() }
            throw if (full) StorageFullException() else t
        }
        saved.add(uri)
        return ExportResult(saved, mime, store.location(where))
    }

    private suspend fun ExportResult.verified(kind: ExportKind, scene: Scene, s: ExportSettings, hasAudio: Boolean, looks: FrameLooks? = null): ExportResult {
        val check = expectationFor(kind, scene, s, hasAudio, looks)?.let { verifyExport(context, uris.first(), it) }
            ?: pictureExpectationFor(kind, scene, s)?.let { verifyPicture(context, uris.first(), it) }
        return if (check == null) this else ExportResult(uris, mime, where, check)
    }

    private fun ExportResult.unreadable(mix: AudioMix?, scene: Scene): ExportResult {
        val names = (mix as? app.absentmotion.audio.OfflineMix)?.unreadable?.toList().orEmpty().map { scene.mediaName(it) }
        if (names.isEmpty()) return this
        val checked = verification ?: Verification(emptyList(), 0, null, null, 0)
        return ExportResult(uris, mime, where, checked.copy(problems = checked.problems + txt(R.string.export_sound_unreadable, names.joinToString(", "))))
    }

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
        engine.exportSession(xml, s.smoothing, s.fps) {
            out.buffered().use { stream ->
                val writer = GifWriter(stream, width, height)
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: throw ExportError(txt(R.string.export_error_draw_frame, k.toString()))
                    withContext(Dispatchers.Default) { writer.frame(ImageEncode.flatten(pixels, s.background, pixels), (k + 1) * 1000L / s.fps) }
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
        imageProblem(s.image, android.os.Build.VERSION.SDK_INT)?.let { throw ExportError(it) }
        checkPicture(width, height)
        val bitmap = app.absentmotion.platform.makeBitmap(width, height)
        try {
            engine.exportSession(xml, s.smoothing, s.fps) {
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    val pixels = still(frameTimeMs(scene, s.fps, k), width, height) ?: throw ExportError(txt(R.string.export_error_draw_frame, k.toString()))
                    save(saved, name, sequenceFrameName(k, s.image.ext), s.image.mime) { encode(pixels, width, height, s, bitmap, it) }
                    progress((k + 1f) / frames)
                }
            }
        } finally {
            app.absentmotion.platform.recycleBitmap(bitmap)
        }
        return ExportResult(saved, s.image.mime, store.location("Pictures/$AppFolder/$name"))
    }

    private suspend fun pack(scene: Scene, xml: String, out: OutputStream, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        writeProjectPackage(scene, xml, out, access, { uri -> runCatching { context.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() }) { ensureStorage(); progress(it) }
    }

    private fun encode(pixels: ByteArray, width: Int, height: Int, settings: ExportSettings, reuse: Bitmap?, out: OutputStream) {
        val buffered = out.buffered(1 shl 16)
        app.absentmotion.platform.encodeRgba(pixels, width, height, settings, reuse, buffered)
        buffered.flush()
    }
}

internal const val StallMs = 60_000L
private const val StallGraceMs = 5_000L

internal class ExportStalled : Exception()

internal fun encoderTrouble(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.take(16).any { it is ExportStalled || it is android.media.MediaCodec.CodecException }

internal suspend fun <T> watched(stallMs: Long = StallMs, block: suspend (alive: () -> Unit) -> T): T {
    val last = java.util.concurrent.atomic.AtomicLong(System.nanoTime() + 2 * stallMs * 1_000_000L)
    val work = CoroutineScope(Dispatchers.Default).async { block { last.set(System.nanoTime()) } }
    try {
        while (!work.isCompleted) {
            withTimeoutOrNull(minOf(1_000L, stallMs)) { work.join() }
            if (!work.isCompleted && System.nanoTime() - last.get() > stallMs * 1_000_000L) {
                work.cancel()
                withTimeoutOrNull(StallGraceMs) { work.join() }
                throw ExportError(txt(R.string.export_error_stalled, (stallMs / 1000).toString()), ExportStalled())
            }
        }
        return work.await()
    } catch (e: kotlinx.coroutines.CancellationException) {
        work.cancel()
        withContext(NonCancellable) { withTimeoutOrNull(StallGraceMs) { work.join() } }
        throw e
    } finally {
        work.cancel()
    }
}

internal suspend fun writeProjectPackage(
    scene: Scene, xml: String, out: OutputStream, access: MediaAccess, open: (String) -> InputStream?, progress: (Float) -> Unit,
) {
    val uris = scene.node.storedMediaUris().toList()
    val manifest = JSONObject().put("format", "absentmotion-package").put("version", 1)
        .put("title", scene.title).put("project", "project.xml")
    val media = JSONArray()
    ZipOutputStream(out.buffered()).use { zip ->
        zip.setLevel(0)
        zip.putNextEntry(ZipEntry("project.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        uris.forEachIndexed { i, uri ->
            currentCoroutineContext().ensureActive()
            val file = scene.mediaName(uri)
            val folder = if (access.allows(uri)) modelFolder(uri) else null
            val record = JSONObject().put("uri", uri).put("file", file)
            fun step(fraction: Float) = progress((i + fraction.coerceIn(0f, 1f)) / uris.size * 0.95f)
            if (folder != null) {
                val main = File(URI(uri)).canonicalFile
                val parts = folder.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
                val total = parts.sumOf { it.length() }.coerceAtLeast(1)
                var copied = 0L
                for (part in parts) {
                    zip.putNextEntry(ZipEntry("media/${i + 1}_model/${part.name}"))
                    copied = part.inputStream().use { it.copyChunks(zip, copied) { done -> step(done.toFloat() / total) } }
                    zip.closeEntry()
                }
                record.put("entry", "media/${i + 1}_model/${main.name}")
                    .put("companions", JSONArray(parts.filter { it != main }.map { "media/${i + 1}_model/${it.name}" }))
            } else {
                val entry = "media/${i + 1}_${safeName(file)}"
                val input = if (access.allows(uri)) open(uri) else null
                if (input != null) {
                    zip.putNextEntry(ZipEntry(entry))
                    val total = scene.media(uri)?.size?.takeIf { it > 0 }
                    input.use { it.copyChunks(zip, 0) { done -> step(if (total == null) 0f else done.toFloat() / total) } }
                    zip.closeEntry()
                    record.put("entry", entry)
                }
            }
            media.put(record)
            progress((i + 1f) / uris.size * 0.95f)
        }
        val fonts = JSONArray()
        val packed = HashMap<File, String>()
        for (font in scene.fontNames()) {
            val file = access.importedFont(font) ?: continue
            val entry = packed.getOrPut(file) {
                "fonts/${packed.size + 1}_${safeName(file.name)}".also { entry ->
                    zip.putNextEntry(ZipEntry(entry)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            fonts.put(JSONObject().put("font", font).put("entry", entry))
        }
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.put("media", media).put("fonts", fonts).toString(1).toByteArray()); zip.closeEntry()
    }
    progress(1f)
}

private suspend fun InputStream.copyChunks(out: OutputStream, start: Long, copied: (Long) -> Unit): Long {
    val buffer = ByteArray(1 shl 20)
    var done = start
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) return done
        out.write(buffer, 0, count)
        done += count
        copied(done)
    }
}
