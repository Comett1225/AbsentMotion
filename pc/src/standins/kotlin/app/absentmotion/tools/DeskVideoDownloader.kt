package app.absentmotion.tools

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.absentmotion.R
import app.absentmotion.ai.CaptionModel
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.platform.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MAX_BINARY = 128L shl 20

internal class RunResult(val code: Int, val error: String)

object DeskVideoDownloader : VideoFetcher {
    private val changes = MutableStateFlow(0)
    override val revision = changes.asStateFlow()
    private val busy = AtomicInteger()

    private val asset: String? = run {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        when {
            DeskOs.windows -> "yt-dlp.exe"
            DeskOs.mac -> "yt-dlp_macos"
            arch == "aarch64" || arch == "arm64" -> "yt-dlp_linux_aarch64"
            arch == "amd64" || arch == "x86_64" -> "yt-dlp_linux"
            else -> null
        }
    }

    override val available: Boolean get() = asset != null
    override val downloadBytes: Long get() = 38_000_000L
    override val av1Hardware: Boolean get() = true

    private fun folder(context: Context) = File(context.filesDir, "downloader")
    private fun binary(context: Context) = File(folder(context), asset ?: "yt-dlp")
    private fun stamp(context: Context) = File(folder(context), "release.txt")

    override fun installed(context: Context): Boolean = asset != null && binary(context).isFile && stamp(context).isFile

    override fun storedBytes(context: Context): Long = listOf(binary(context), stamp(context)).filter { it.isFile }.sumOf { it.length() }

    override fun remove(context: Context) {
        val dir = folder(context)
        for (name in listOf(binary(context).name, binary(context).name + ".part", stamp(context).name)) File(dir, name).delete()
        if (dir.list()?.isEmpty() == true) dir.delete()
        changes.value += 1
    }

    override suspend fun install(context: Context, progress: (Float) -> Unit) {
        val name = asset ?: throw DownloadProblem(R.string.downloader_engine_unavailable)
        withContext(Dispatchers.IO) {
            val dir = folder(context)
            check(dir.isDirectory || dir.mkdirs()) { context.getString(R.string.downloader_engine_failed) }
            val (tag, sha) = try { latestRelease(name) } catch (e: IOException) { throw DownloadProblem(R.string.downloader_engine_failed, e.toString()) }
            download(name, tag, sha, binary(context)) { progress(it) }
            stamp(context).writeText(tag)
            progress(1f)
        }
        changes.value += 1
    }

    override suspend fun updateIfDue(context: Context) {
        val name = asset ?: return
        if (!installed(context) || busy.get() > 0) return
        withContext(Dispatchers.IO) {
            val mark = stamp(context)
            if (System.currentTimeMillis() - mark.lastModified() in 0 until DAY_MS) return@withContext
            try {
                val (tag, sha) = latestRelease(name)
                if (tag != mark.readText().trim() && busy.get() == 0) {
                    download(name, tag, sha, binary(context)) {}
                    mark.writeText(tag)
                } else mark.setLastModified(System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("downloader update check failed: $e")
            }
        }
    }

    private suspend fun download(name: String, tag: String, sha: String, target: File, progress: (Float) -> Unit) {
        val job = currentCoroutineContext().job
        val connection = URL("$YT_DLP_RELEASES/download/$tag/$name").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AbsentMotion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DownloadProblem(R.string.downloader_engine_failed, "HTTP ${connection.responseCode}")
            val size = connection.contentLengthLong
            if (size !in 1..MAX_BINARY) throw DownloadProblem(R.string.downloader_engine_failed, "size $size")
            connection.inputStream.use {
                CaptionModel.receive(it, File(target.path + ".part"), target, size, sha, { job.ensureActive() }, progress, wrong = R.string.downloader_engine_failed)
            }
        } catch (e: IOException) {
            throw DownloadProblem(R.string.downloader_engine_failed, e.toString())
        } finally {
            connection.disconnect()
        }
        if (!DeskOs.windows) {
            target.setExecutable(true)
            if (DeskOs.mac) runCatching { ProcessBuilder("xattr", "-d", "com.apple.quarantine", target.path).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() }
        }
        if (!target.isFile) throw DownloadProblem(R.string.downloader_engine_blocked)
    }

    override suspend fun warmUp(context: Context) = Unit

    override suspend fun check(context: Context, url: String): LinkInfo = readInfo(binary(context), url)

    override suspend fun thumbnail(urls: List<String>): ImageBitmap? = withContext(Dispatchers.IO) {
        urls.asSequence().take(4).firstNotNullOfOrNull { picture ->
            runCatching {
                val connection = URL(picture).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                    org.jetbrains.skia.Image.makeFromEncoded(connection.inputStream.use { it.readAtMost(8 shl 20) }).toComposeImageBitmap()
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }

    override suspend fun download(context: Context, url: String, info: LinkInfo, mode: SaveMode, choice: Int, progress: (Float) -> Unit): List<SavedMedia> {
        val exe = binary(context)
        val work = File(context.cacheDir, "downloader-work")
        clear(work)
        check(work.mkdirs()) { context.getString(R.string.downloader_error_unknown) }
        try {
            val saved = ArrayList<SavedMedia>()
            val items = info.items
            val share = 1f / items.size.coerceAtLeast(1)
            val baseName = safeName(info.title)
            for ((index, item) in items.withIndex()) {
                if (picturesOnly(item)) continue
                val name = if (items.size > 1) "$baseName ${index + 1}" else safeName(item.title.ifBlank { info.title })
                val itemMode = if (items.size > 1) SaveMode.Both else mode
                val options = qualities(item, itemMode, av1Hardware)
                val quality = (if (items.size > 1) options.getOrNull(defaultQuality(options, itemMode)) else options.getOrNull(choice))
                    ?: if (items.size > 1) continue else throw DownloadProblem(R.string.downloader_error_novideo)
                val start = index * share
                val file = fetchTo(exe, url, if (items.size > 1) index + 1 else 0, quality.plan, itemMode, File(work, "item$index"), DeskMedia.ffmpeg) { progress(start + share * 0.95f * it) }
                val (mime, extension) = typeOf(file)
                val saveName = "$name.$extension"
                val uri: Uri = withContext(Dispatchers.IO) { MediaStoreExport(context).save(null, saveName, mime) { out -> file.inputStream().use { it.copyTo(out) } } }
                saved += SavedMedia(uri, mime, saveName)
                progress(start + share)
            }
            if (saved.isEmpty()) throw DownloadProblem(R.string.downloader_error_novideo)
            return saved
        } finally {
            clear(work)
        }
    }

    internal suspend fun <T> counted(block: suspend () -> T): T {
        busy.incrementAndGet()
        try { return block() } finally { busy.decrementAndGet() }
    }

    internal suspend fun fetchTo(exe: File, url: String, entry: Int, plan: Plan, mode: SaveMode, dir: File, ffmpeg: String, progress: (Float) -> Unit): File = counted {
        if (!exe.isFile) throw DownloadProblem(R.string.downloader_engine_blocked)
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val pieces = listOfNotNull(plan.video, plan.audio?.takeIf { it !== plan.video }).size.coerceAtLeast(1)
        val chosen = listOfNotNull(plan.video?.id, plan.audio?.id).distinct().joinToString("+")
        val args = ArrayList<String>()
        args += listOf("--no-playlist", "--no-warnings", "--newline", "--no-mtime", "--ffmpeg-location", ffmpeg, "-f", chosen, "-o", File(dir, "item.%(ext)s").path)
        if (entry > 0) args += listOf("--playlist-items", entry.toString())
        if (mode == SaveMode.AudioOnly) args += listOf("-x", "--audio-format", "m4a")
        else if (plan.video != null) args += listOf("--merge-output-format", if (plan.webm) "webm" else "mp4")
        var stream = 0
        val result = execute(exe, args + url) { line ->
            if (line.startsWith("[download] Destination:")) stream += 1
            Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""").find(line)?.let {
                val whole = ((stream - 1).coerceAtLeast(0) + it.groupValues[1].toFloat() / 100f) / pieces
                progress(whole.coerceIn(0f, 1f))
            }
        }
        failIf(result)
        val file = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("item.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.maxByOrNull { it.length() }
            ?: throw DownloadProblem(R.string.downloader_error_save, "no file")
        progress(1f)
        file
    }

    internal suspend fun readInfo(exe: File, url: String): LinkInfo = counted {
        if (!exe.isFile) throw DownloadProblem(R.string.downloader_engine_blocked)
        val out = StringBuilder()
        val result = execute(exe, listOf("--dump-single-json", "--no-warnings", "--no-playlist", url)) { out.append(it) }
        failIf(result)
        val json = try { JSONObject(out.toString()) } catch (e: Exception) { throw DownloadProblem(R.string.downloader_error_unknown, "bad json") }
        parseLinkInfo(toInfoJson(json).toString())
    }

    private fun failIf(result: RunResult) {
        if (result.code == 0) return
        val strange = result.error.isBlank() && (result.code < 0 || result.code > 255)
        if (strange || result.code == 126 || result.code == 127) throw DownloadProblem(R.string.downloader_engine_blocked, "exit ${result.code}")
        throw DownloadProblem(problemFor(result.error), result.error.take(400))
    }

    internal suspend fun execute(exe: File, args: List<String>, onLine: (String) -> Unit): RunResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf(exe.path) + args).start()
        } catch (e: IOException) {
            throw DownloadProblem(R.string.downloader_engine_blocked, e.toString())
        }
        val errors = StringBuilder()
        val reader = thread(isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { synchronized(errors) { if (errors.length < 20_000) errors.appendLine(it) } }
        }
        coroutineScope {
            val guard = launch { try { awaitCancellation() } finally { if (process.isAlive) kill(process) } }
            try {
                process.outputStream.close()
                process.inputStream.bufferedReader().forEachLine(onLine)
                val code = process.waitFor()
                reader.join(2000)
                ensureActive()
                RunResult(code, synchronized(errors) { errors.toString() })
            } finally {
                guard.cancel()
                if (process.isAlive) kill(process)
            }
        }
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}

internal fun typeOf(file: File): Pair<String, String> {
    val extension = file.extension.lowercase()
    val mime = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        else -> "video/mp4"
    }
    return mime to extension
}

private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun toItemJson(entry: JSONObject): JSONObject {
    val formats = JSONArray()
    val source = entry.optJSONArray("formats") ?: JSONArray()
    for (i in 0 until source.length()) {
        val f = source.optJSONObject(i) ?: continue
        if (f.str("ext") == "mhtml" || f.str("vcodec") == "images" || f.str("format_id") == null) continue
        formats.put(
            JSONObject().put("id", f.str("format_id")).put("ext", f.str("ext")).put("v", f.str("vcodec")).put("a", f.str("acodec"))
                .put("w", f.opt("width") ?: 0).put("h", f.opt("height") ?: 0).put("fps", f.opt("fps") ?: 0).put("abr", f.opt("abr") ?: 0).put("tbr", f.opt("tbr") ?: 0)
                .put("size", f.opt("filesize")?.takeIf { it != JSONObject.NULL } ?: f.opt("filesize_approx") ?: 0)
                .put("proto", f.str("protocol")).put("note", f.str("format_note")).put("ch", f.opt("audio_channels") ?: 0),
        )
    }
    val thumbs = JSONArray().also { list -> entry.str("thumbnail")?.let(list::put) }
    return JSONObject().put("title", entry.str("title") ?: "").put("duration", entry.opt("duration") ?: 0).put("thumbnails", thumbs).put("formats", formats).put("pictures", JSONArray())
}

internal fun toInfoJson(root: JSONObject): JSONObject {
    val entries = root.optJSONArray("entries")
    val items = JSONArray()
    if (entries != null) for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { items.put(toItemJson(it)) } else items.put(toItemJson(root))
    val thumbs = JSONArray().also { list -> root.str("thumbnail")?.let(list::put) }
    return JSONObject().put("title", root.str("title") ?: "").put("thumbnails", thumbs).put("site", root.str("extractor_key") ?: "").put("items", items)
}

private fun clear(dir: File) {
    if (!java.nio.file.Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    runCatching {
        java.nio.file.Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(folder: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(folder)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
