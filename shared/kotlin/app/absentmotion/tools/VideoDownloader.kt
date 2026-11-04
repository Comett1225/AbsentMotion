package app.absentmotion.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.absentmotion.R
import app.absentmotion.ai.CaptionModel
import app.absentmotion.ai.ModelFile
import app.absentmotion.ai.fetch
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.platform.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.exifinterface.media.ExifInterface
import kotlin.coroutines.cancellation.CancellationException

class DownloadProblem(@StringRes val text: Int, detail: String = "") : Exception(detail)

class SavedMedia(val uri: Uri, val mime: String, val name: String) {
    val video get() = mime.startsWith("video/")
}

internal const val PYTHON_RELEASE = "https://www.python.org/ftp/python/3.14.10/"
internal const val YT_DLP_RELEASES = "https://github.com/yt-dlp/yt-dlp/releases"
private const val PYTHON_LIB = "libpython3.14.so"
private const val STDLIB = "lib/python3.14"
private const val TAG = "AMDownloader"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val ENGINE_ESTIMATE = 3_100_000L
private val SystemCertificates = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

object VideoDownloader {
    private val python = ModelFile(
        "python-3.14.10-aarch64-linux-android.tar.gz", PYTHON_RELEASE + "python-3.14.10-aarch64-linux-android.tar.gz",
        23_027_305, "423c80b957330cf0ebb92b9b20f2294c292144a441bdb0ceb8c69d14928e569b",
    )
    private val lock = Mutex()
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    @Volatile private var running = false
    @Volatile private var restartNeeded = false

    val downloadBytes: Long get() = python.bytes + ENGINE_ESTIMATE

    val available: Boolean get() = Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"

    private fun folder(context: Context) = File(context.filesDir, "downloader")
    private fun home(context: Context) = File(folder(context), "python")
    private fun engine(context: Context) = File(folder(context), "yt-dlp.zip")
    private fun nextEngine(context: Context) = File(folder(context), "yt-dlp.next")
    private fun prefs(context: Context) = context.getSharedPreferences("downloader", Context.MODE_PRIVATE)

    fun installed(context: Context): Boolean =
        File(home(context), ".ok").let { it.isFile && it.readText() == python.sha256 } && engine(context).isFile

    fun storedBytes(context: Context): Long = folder(context).walk().filter { it.isFile }.sumOf { it.length() }

    fun remove(context: Context) {
        folder(context).deleteRecursively()
        prefs(context).edit().clear().apply()
        if (running) restartNeeded = true
        changes.value += 1
    }

    suspend fun install(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = folder(context)
        check(dir.isDirectory || dir.mkdirs()) { context.getString(R.string.downloader_engine_failed) }
        val home = home(context)
        if (!File(home, ".ok").let { it.isFile && it.readText() == python.sha256 }) {
            val archive = File(dir, python.name)
            try {
                if (archive.length() != python.bytes || archive.inputStream().use(CaptionModel::sha256) != python.sha256) {
                    archive.delete()
                    fetch(python, archive, { ensureActive() }) { progress(it * 0.85f) }
                }
            } catch (e: IOException) {
                throw DownloadProblem(R.string.downloader_engine_failed, e.toString())
            }
            ensureActive()
            unpackPython(archive, home)
            archive.delete()
        }
        progress(0.88f)
        if (!engine(context).isFile) {
            val (tag, sha) = latestEngine()
            downloadEngine(tag, sha, engine(context)) { progress(0.88f + it * 0.12f) }
            prefs(context).edit().putString("engine", tag).putLong("checked", System.currentTimeMillis()).apply()
        }
        progress(1f)
    }

    suspend fun updateIfDue(context: Context) = withContext(Dispatchers.IO) {
        if (!installed(context)) return@withContext
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked", 0L) in 0 until DAY_MS) return@withContext
        try {
            val (tag, sha) = latestEngine()
            prefs.edit().putLong("checked", now).apply()
            if (tag == prefs.getString("engine", null) || tag == prefs.getString("pending", null) || tag == prefs.getString("bad", null)) return@withContext
            downloadEngine(tag, sha, nextEngine(context)) {}
            prefs.edit().putString("pending", tag).apply()
            Log.i(TAG, "engine $tag is ready for the next start")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "engine update check failed", e)
        }
    }

    private fun latestEngine(): Pair<String, String> {
        val connection = URL("$YT_DLP_RELEASES/latest").openConnection() as HttpURLConnection
        val tag = try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "AbsentMotion")
            connection.responseCode
            connection.getHeaderField("Location")?.substringAfterLast("/tag/", "")
        } finally {
            connection.disconnect()
        }
        if (tag == null || !tag.matches(Regex("[0-9][0-9.]{3,30}"))) throw DownloadProblem(R.string.downloader_engine_failed, "no release tag")
        val sums = readSmall("$YT_DLP_RELEASES/download/$tag/SHA2-256SUMS")
        val sha = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1] == "yt-dlp" }?.get(0)
        if (sha == null || !sha.matches(Regex("[0-9a-f]{64}"))) throw DownloadProblem(R.string.downloader_engine_failed, "no checksum")
        return tag to sha
    }

    private fun readSmall(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "AbsentMotion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DownloadProblem(R.string.downloader_engine_failed, "HTTP ${connection.responseCode}")
            return connection.inputStream.use { String(it.readAtMost(1 shl 16), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadEngine(tag: String, sha: String, target: File, progress: (Float) -> Unit) {
        val job = currentCoroutineContext().job
        val connection = URL("$YT_DLP_RELEASES/download/$tag/yt-dlp").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AbsentMotion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DownloadProblem(R.string.downloader_engine_failed, "HTTP ${connection.responseCode}")
            val size = connection.contentLengthLong
            if (size !in 1..(64L shl 20)) throw DownloadProblem(R.string.downloader_engine_failed, "size $size")
            connection.inputStream.use {
                CaptionModel.receive(it, File(target.path + ".part"), target, size, sha, { job.ensureActive() }, progress, wrong = R.string.downloader_engine_failed)
            }
        } catch (e: IOException) {
            throw DownloadProblem(R.string.downloader_engine_failed, e.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun unpackPython(archive: File, home: File) {
        val fresh = File(home.path + ".new")
        fresh.deleteRecursively()
        check(fresh.mkdirs())
        val root = fresh.canonicalPath + File.separator
        GZIPInputStream(archive.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
            readTar(input) { name, type, body ->
                val relative = pythonEntry(name) ?: return@readTar
                val target = File(fresh, relative)
                check(target.canonicalPath.startsWith(root)) { "unsafe entry $name" }
                when (type) {
                    '5' -> target.mkdirs()
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { body.copyTo(it, 1 shl 16) }
                    }
                }
            }
        }
        check(File(fresh, "lib/$PYTHON_LIB").isFile && File(fresh, "$STDLIB/os.py").isFile) { "the Python package is incomplete" }
        File(fresh, ".ok").writeText(python.sha256)
        home.deleteRecursively()
        check(fresh.renameTo(home)) { "cannot finish the Python package" }
    }

    private fun certificates(target: File) {
        if (System.currentTimeMillis() - target.lastModified() in 0 until DAY_MS) return
        val folder = SystemCertificates.map(::File).firstOrNull { (it.list()?.size ?: 0) > 0 } ?: return
        target.writeText(folder.listFiles().orEmpty().sortedBy { it.name }.joinToString("") { it.readText() + "\n" })
    }

    private fun start(context: Context) {
        if (running) return
        val dir = folder(context)
        val prefs = prefs(context)
        val next = nextEngine(context)
        val pending = prefs.getString("pending", null)
        val previous = File(dir, "yt-dlp.prev")
        var swapped: String? = null
        if (pending != null) {
            val current = engine(context)
            previous.delete()
            val kept = !current.isFile || current.renameTo(previous)
            val moved = kept && next.isFile && next.renameTo(current)
            if (!moved && previous.isFile && !current.isFile) previous.renameTo(current)
            next.delete()
            prefs.edit().apply { if (moved) putString("previous", prefs.getString("engine", null)).putString("engine", pending) }.remove("pending").apply()
            if (moved) swapped = pending
        }
        val glue = context.assets.open("downloader/am_dl.py").use { it.readBytes() }
        File(dir, "am_dl.py").let { if (!it.isFile || !it.readBytes().contentEquals(glue)) it.writeBytes(glue) }
        val certificates = File(dir, "certificates.pem")
        certificates(certificates)
        val temp = File(context.cacheDir, "downloader-temp").apply { mkdirs() }
        Os.setenv("SSL_CERT_FILE", certificates.path, true)
        Os.setenv("TMPDIR", temp.path, true)
        Os.setenv("HOME", dir.path, true)
        val home = home(context)
        val lib = File(home, "lib")
        val paths = listOf(engine(context).path, dir.path).joinToString(", ") { path -> "bytes.fromhex('${path.toByteArray().joinToString("") { "%02x".format(it) }}').decode()" }
        val boot = "import sys\nsys.path[0:0] = [$paths]\nimport am_dl\n"
        try {
            PyEngine.start(listOf("libcrypto_python.so", "libssl_python.so", PYTHON_LIB).map { File(lib, it).path }, home.path, boot)
        } catch (e: Exception) {
            if (swapped == null || !previous.isFile) throw e
            Log.w(TAG, "engine $swapped did not start, going back to the previous one", e)
            engine(context).delete()
            previous.renameTo(engine(context))
            prefs.edit().putString("engine", prefs.getString("previous", null)).putString("bad", swapped).apply()
            restartNeeded = true
            throw DownloadProblem(R.string.downloader_restart)
        }
        previous.delete()
        running = true
    }

    private suspend fun python(context: Context, request: JSONObject, progress: ((Long, Long) -> Unit)? = null): JSONObject = lock.withLock {
        withContext(Dispatchers.IO) {
            if (restartNeeded) throw DownloadProblem(R.string.downloader_restart)
            if (!installed(context)) throw DownloadProblem(R.string.downloader_engine_missing)
            try {
                start(context)
            } catch (e: DownloadProblem) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the downloader did not start", e)
                throw DownloadProblem(R.string.downloader_engine_broken, e.message.orEmpty())
            }
            val job = currentCoroutineContext().job
            request.put("cache", File(context.cacheDir, "yt-dlp").path)
            var result: JSONObject? = null
            var lastError = ""
            val js = lazy { WebViewJs(context) }
            try {
                PyEngine.run("am_dl.serve()\n") { message ->
                    when (message.optString("op")) {
                        "request" -> request.toString()
                        "result" -> { result = message.optJSONObject("result"); "" }
                        "progress" -> { progress?.invoke(message.optLong("done"), message.optLong("total")); if (job.isActive) "go" else "stop" }
                        "js" -> try {
                            JSONObject().put("out", js.value.run(message.optString("code"))).toString()
                        } catch (e: Exception) {
                            Log.w(TAG, "JavaScript failed", e)
                            JSONObject().put("error", e.message ?: "failed").toString()
                        }
                        "log" -> {
                            val text = message.optString("text")
                            Log.i(TAG, text)
                            if (message.optString("level") == "error") lastError = text
                            ""
                        }
                        else -> ""
                    }
                }
            } finally {
                if (js.isInitialized()) js.value.close()
            }
            job.ensureActive()
            val answer = result ?: throw DownloadProblem(R.string.downloader_error_unknown, lastError)
            if (answer.optBoolean("cancelled")) throw CancellationException()
            if (!answer.optBoolean("ok")) {
                val raw = answer.optString("error").ifEmpty { lastError }
                Log.w(TAG, "failed: $raw")
                throw DownloadProblem(problemFor(raw), raw)
            }
            answer.getJSONObject("data")
        }
    }

    suspend fun warmUp(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) { if (installed(context)) runCatching { start(context) }.onFailure { Log.w(TAG, "the downloader did not start", it) } }
    }

    suspend fun check(context: Context, url: String): LinkInfo {
        val data = python(context, JSONObject().put("op", "check").put("url", url))
        return parseLinkInfo(data.toString())
    }

    suspend fun thumbnail(urls: List<String>): ImageBitmap? = withContext(Dispatchers.IO) { urls.asSequence().take(4).firstNotNullOfOrNull { thumbnail(it, 192) } }

    private fun thumbnail(url: String, side: Int): ImageBitmap? =
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val bytes = connection.inputStream.use { it.readAtMost(8 shl 20) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    val av1Hardware: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 && MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && MediaFormat.MIMETYPE_VIDEO_AV1 in info.supportedTypes && info.isHardwareAccelerated
        }
    }

    suspend fun download(context: Context, url: String, info: LinkInfo, mode: SaveMode, choice: Int, progress: (Float) -> Unit): List<SavedMedia> {
        val work = File(context.cacheDir, "downloader-work")
        work.deleteRecursively()
        check(work.mkdirs()) { context.getString(R.string.downloader_error_unknown) }
        try {
            val saved = ArrayList<SavedMedia>()
            val items = info.items
            val share = 1f / items.size.coerceAtLeast(1)
            val baseName = safeName(info.title)
            for ((index, item) in items.withIndex()) {
                val name = if (items.size > 1) "$baseName ${index + 1}" else safeName(item.title.ifBlank { info.title })
                val start = index * share
                if (picturesOnly(item)) {
                    item.pictures.forEachIndexed { n, picture ->
                        val part = share / item.pictures.size
                        saved += savePicture(context, url, picture, File(work, "picture$index-$n"), if (item.pictures.size > 1) "$name ${n + 1}" else name) {
                            progress(start + part * (n + it))
                        }
                    }
                    continue
                }
                val itemMode = if (items.size > 1) SaveMode.Both else mode
                val options = qualities(item, itemMode, av1Hardware)
                val quality = (if (items.size > 1) options.getOrNull(defaultQuality(options, itemMode)) else options.getOrNull(choice))
                    ?: if (items.size > 1) continue else throw DownloadProblem(R.string.downloader_error_novideo)
                saved += saveItem(context, url, index, quality.plan, itemMode, work, name) { progress(start + share * it) }
            }
            if (saved.isEmpty()) throw DownloadProblem(R.string.downloader_error_novideo)
            return saved
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun fetchFormat(context: Context, url: String, item: Int, format: RemoteFormat, target: File, progress: (Float) -> Unit): File {
        val data = python(context, JSONObject().put("op", "fetch").put("url", url).put("item", item).put("format", format.id).put("out", target.path)) { done, total ->
            val whole = if (total > 0) total else format.bytes
            if (whole > 0) progress((done.toFloat() / whole).coerceIn(0f, 1f))
        }
        return File(data.getString("path"))
    }

    private suspend fun saveItem(context: Context, url: String, index: Int, plan: Plan, mode: SaveMode, work: File, name: String, progress: (Float) -> Unit): SavedMedia {
        val pieces = listOfNotNull(plan.video, plan.audio?.takeIf { it !== plan.video })
        val weights = pieces.map { it.bytes.coerceAtLeast(1L).toFloat() }.let { w -> if (pieces.any { it.bytes <= 0 }) w.map { 1f } else w }
        val total = weights.sum()
        val files = ArrayList<File>()
        var before = 0f
        for ((i, piece) in pieces.withIndex()) {
            val weight = weights[i]
            files += fetchFormat(context, url, index, piece, File(work, "item$index-$i")) { progress(0.95f * (before + weight * it) / total) }
            before += weight
        }
        progress(0.95f)
        return withContext(Dispatchers.IO) {
            if (mode == SaveMode.AudioOnly) saveAudio(context, files.single(), name) else saveVideo(context, files, mode == SaveMode.Both, plan.webm, name)
        }.also { progress(1f) }
    }

    private suspend fun savePicture(context: Context, url: String, sources: List<String>, target: File, name: String, progress: (Float) -> Unit): SavedMedia {
        python(context, JSONObject().put("op", "picture").put("url", url).put("srcs", JSONArray(sources)).put("out", target.path)) { done, total ->
            if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        return withContext(Dispatchers.IO) {
            val (mime, extension) = pictureType(target.inputStream().use { it.readAtMost(16) })
            val file = "$name.$extension"
            freshDates(target)
            SavedMedia(MediaStoreExport(context).save(null, file, mime) { out -> target.inputStream().use { it.copyTo(out) } }, mime, file)
        }
    }

    private suspend fun saveVideo(context: Context, files: List<File>, sound: Boolean, webm: Boolean, name: String): SavedMedia {
        val mime = if (webm) "video/webm" else "video/mp4"
        val file = "$name.${if (webm) "webm" else "mp4"}"
        val open = MediaStoreExport(context).open(null, file, mime)
        val job = currentCoroutineContext().job
        try {
            remux(files, video = true, audio = sound, open.fd, webm) { job.ensureActive() }
            return SavedMedia(open.publish(), mime, file)
        } catch (t: Throwable) {
            open.discard()
            if (t is CancellationException || t is DownloadProblem) throw t
            Log.w(TAG, "cannot save the video", t)
            throw DownloadProblem(R.string.downloader_error_save, t.toString())
        }
    }

    private suspend fun saveAudio(context: Context, source: File, name: String): SavedMedia {
        val file = "$name.m4a"
        val mime = "audio/mp4"
        val format = trackFormat(source, "audio/") ?: throw DownloadProblem(R.string.downloader_error_save, "no sound")
        val store = MediaStoreExport(context)
        val job = currentCoroutineContext().job
        if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
            val open = store.open(null, file, mime)
            try {
                remux(listOf(source), video = false, audio = true, open.fd, webm = false) { job.ensureActive() }
                return SavedMedia(open.publish(), mime, file)
            } catch (t: Throwable) {
                open.discard()
                if (t is CancellationException) throw t
                Log.w(TAG, "cannot copy the sound, encoding it instead", t)
            }
        }
        val encoded = File(source.path + ".m4a")
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (duration <= 0) throw DownloadProblem(R.string.downloader_error_save, "unknown length")
        encodeSound(context, Uri.fromFile(source).toString(), duration, 192, encoded) {}
        return SavedMedia(store.save(null, file, mime) { out -> encoded.inputStream().use { it.copyTo(out) } }, mime, file)
    }

    private fun trackFormat(file: File, prefix: String): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
        } catch (e: IOException) {
            null
        } finally {
            extractor.release()
        }
    }
}

private class Track(val extractor: MediaExtractor, val index: Int, val target: Int, var buffer: ByteBuffer, val sound: Boolean) {
    var last = Long.MIN_VALUE
}

internal fun remux(inputs: List<File>, video: Boolean, audio: Boolean, out: FileDescriptor, webm: Boolean, active: () -> Unit) {
    val extractors = ArrayList<MediaExtractor>()
    val muxer = MediaMuxer(out, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        val tracks = ArrayList<Track>()
        var hasVideo = false
        var hasAudio = false
        var rotation = 0
        for (file in inputs) {
            val extractor = MediaExtractor().also { extractors += it }
            extractor.setDataSource(file.path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val wanted = mime.startsWith("video/") && video && !hasVideo || mime.startsWith("audio/") && audio && !hasAudio
                if (!wanted) continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                } else hasAudio = true
                extractor.selectTrack(i)
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                tracks += Track(extractor, i, muxer.addTrack(format), ByteBuffer.allocateDirect(maxOf(size, if (mime.startsWith("video/")) 4 shl 20 else 1 shl 20)), mime.startsWith("audio/"))
            }
        }
        if (video && !hasVideo || !video && audio && !hasAudio) throw DownloadProblem(R.string.downloader_error_save, "missing track")
        if (rotation != 0) muxer.setOrientationHint(rotation)
        muxer.start()
        val sources = extractors.filter { e -> tracks.any { it.extractor === e } }
        val shift = -minOf(0L, sources.minOf { it.sampleTime })
        val info = MediaCodec.BufferInfo()
        while (true) {
            active()
            val next = sources.filter { it.sampleTrackIndex >= 0 }.minByOrNull { it.sampleTime } ?: break
            val track = tracks.firstOrNull { it.extractor === next && it.index == next.sampleTrackIndex }
            val size = if (track == null) -1 else readSample(next, track)
            val time = (next.sampleTime + shift).coerceAtLeast(0L)
            if (track != null && size >= 0 && !(track.sound && time <= track.last)) {
                val flags = if (next.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, time, flags)
                muxer.writeSampleData(track.target, track.buffer, info)
                track.last = time
            }
            next.advance()
        }
        muxer.stop()
    } finally {
        runCatching { muxer.release() }
        extractors.forEach { runCatching { it.release() } }
    }
}

private fun readSample(extractor: MediaExtractor, track: Track): Int {
    while (true) {
        try {
            return extractor.readSampleData(track.buffer, 0)
        } catch (e: IllegalArgumentException) {
            if (track.buffer.capacity() >= 64 shl 20) throw e
            track.buffer = ByteBuffer.allocateDirect(track.buffer.capacity() * 2)
        }
    }
}

private val DateTags = listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)
private val ZoneTags = listOf(ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP)

internal fun freshDates(file: File, now: Date = Date()) {
    try {
        val exif = ExifInterface(file)
        if (DateTags.none { exif.getAttribute(it) != null } && ZoneTags.none { exif.getAttribute(it) != null }) return
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
        for (tag in DateTags) exif.setAttribute(tag, stamp)
        for (tag in ZoneTags) exif.setAttribute(tag, null)
        exif.saveAttributes()
    } catch (e: Exception) {
        Log.w(TAG, "cannot reset the picture's dates", e)
    }
}

internal fun pictureType(head: ByteArray): Pair<String, String> {
    fun at(offset: Int, text: String) = head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
    return when {
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg" to "jpg"
        at(1, "PNG") -> "image/png" to "png"
        at(0, "RIFF") && at(8, "WEBP") -> "image/webp" to "webp"
        at(0, "GIF8") -> "image/gif" to "gif"
        at(4, "ftypavif") -> "image/avif" to "avif"
        at(4, "ftyphei") || at(4, "ftypmif1") -> "image/heif" to "heic"
        else -> "image/jpeg" to "jpg"
    }
}

internal fun safeName(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
    return clean.take(80).trim().ifEmpty { "Download" }
}

@StringRes
internal fun problemFor(raw: String): Int {
    val text = raw.lowercase()
    return when {
        "unsupported url" in text -> R.string.downloader_error_unsupported
        "not a bot" in text -> R.string.downloader_error_bot
        "sign in" in text || "login" in text || "log in" in text || "authentication" in text || "cookies" in text || "age-restricted" in text || "confirm your age" in text -> R.string.downloader_error_login
        "private" in text -> R.string.downloader_error_private
        "tls fingerprint" in text || "impersonat" in text || "blocked" in text || "http error 403" in text || "rate-limit" in text || "too many requests" in text -> R.string.downloader_error_blocked
        "unavailable" in text || "removed" in text || "not available" in text || "does not exist" in text || "http error 404" in text || "has been deleted" in text -> R.string.downloader_error_gone
        "no video" in text || "no media" in text || "requested format is not available" in text -> R.string.downloader_error_novideo
        "unable to download" in text || "timed out" in text || "getaddrinfo" in text || "network is unreachable" in text || "connection" in text || "urlopen error" in text -> R.string.downloader_error_network
        else -> R.string.downloader_error_unknown
    }
}

internal fun pythonEntry(name: String): String? {
    val path = name.removePrefix("./")
    if (!path.startsWith("prefix/lib/")) return null
    val relative = path.removePrefix("prefix/")
    val file = relative.substringAfterLast('/')
    val skipped = listOf("lib/pkgconfig", "lib/engines-3", "lib/ossl-modules", "test", "idlelib", "tkinter", "turtledemo", "ensurepip", "pydoc_data", "sqlite3", "_pyrepl", "__phello__", "config-3.14-aarch64-linux-android")
        .map { if (it.startsWith("lib/")) it else "$STDLIB/$it" }
    if (skipped.any { relative == it || relative.startsWith("$it/") }) return null
    if (relative.startsWith("$STDLIB/lib-dynload/") && listOf("_test", "xx", "_xx", "_ctypes_test", "_sqlite3").any { file.startsWith(it) }) return null
    if (relative.count { it == '/' } == 1 && file.contains(".so") && file != PYTHON_LIB && file !in setOf("libcrypto_python.so", "libssl_python.so")) return null
    return relative
}

internal fun readTar(input: InputStream, each: (name: String, type: Char, body: InputStream) -> Unit) {
    val header = ByteArray(512)
    var longName: String? = null
    while (true) {
        if (!readBlock(input, header)) return
        if (header.all { it == 0.toByte() }) return
        val size = octal(header, 124, 12)
        val type = header[156].toInt().toChar()
        var name = field(header, 0, 100)
        if (field(header, 257, 5) == "ustar") field(header, 345, 155).takeIf { it.isNotEmpty() }?.let { name = "$it/$name" }
        val body = Bounded(input, size)
        when (type) {
            'x' -> longName = paxPath(body.readBytes()) ?: longName
            'L' -> longName = String(body.readBytes(), Charsets.UTF_8).trimEnd('\u0000')
            'g' -> Unit
            else -> {
                each(longName ?: name, type, body)
                longName = null
            }
        }
        body.finish()
        val padding = (512 - size % 512) % 512
        skipFully(input, padding)
    }
}

private fun readBlock(input: InputStream, buffer: ByteArray): Boolean {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count < 0) {
            if (read == 0) return false
            throw IOException("the archive ends early")
        }
        read += count
    }
    return true
}

private fun skipFully(input: InputStream, count: Long) {
    var left = count
    val scratch = ByteArray(8192)
    while (left > 0) {
        val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
        if (read < 0) throw IOException("the archive ends early")
        left -= read
    }
}

private fun field(header: ByteArray, offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && header[end] != 0.toByte()) end++
    return String(header, offset, end - offset, Charsets.UTF_8)
}

private fun octal(header: ByteArray, offset: Int, length: Int): Long {
    val text = field(header, offset, length).trim()
    return if (text.isEmpty()) 0L else text.toLong(8)
}

private fun paxPath(data: ByteArray): String? {
    var position = 0
    val text = String(data, Charsets.UTF_8)
    var found: String? = null
    while (position < text.length) {
        val space = text.indexOf(' ', position).takeIf { it > position } ?: break
        val length = text.substring(position, space).toIntOrNull() ?: break
        val record = text.substring(space + 1, (position + length - 1).coerceAtMost(text.length))
        if (record.startsWith("path=")) found = record.removePrefix("path=")
        position += length
    }
    return found
}

private class Bounded(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val value = input.read()
        if (value >= 0) left--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val count = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (count > 0) left -= count
        return count
    }

    fun finish() = skipFully(input, left).also { left = 0 }
}
