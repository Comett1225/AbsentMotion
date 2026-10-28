package app.absentmotion.ui.home

import app.absentmotion.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface UpdateFailure {
    val message: Int
    val retry: Boolean get() = true

    data object Offline : UpdateFailure { override val message = R.string.update_err_offline }
    data object Connection : UpdateFailure { override val message = R.string.update_err_connection }
    data object Insecure : UpdateFailure { override val message = R.string.update_err_secure }
    data class Server(val code: Int) : UpdateFailure { override val message = R.string.update_err_server }
    data class Storage(val needed: Long) : UpdateFailure { override val message = R.string.update_err_storage }
    data object Checksum : UpdateFailure { override val message = R.string.update_err_checksum }
    data object Save : UpdateFailure { override val message = R.string.update_err_file }
    data object Removed : UpdateFailure { override val message = R.string.update_err_removed }
    data object Interrupted : UpdateFailure { override val message = R.string.update_err_interrupted }
    data object InstallFailed : UpdateFailure { override val message = R.string.update_err_install }
    data object NoChecksum : UpdateFailure { override val message = R.string.update_err_no_checksum; override val retry = false }
    data object Untrusted : UpdateFailure { override val message = R.string.update_err_untrusted; override val retry = false }
    data object Signature : UpdateFailure { override val message = R.string.update_err_signature; override val retry = false }
    data object WrongApp : UpdateFailure { override val message = R.string.update_err_wrong_app; override val retry = false }
    data object NotNewer : UpdateFailure { override val message = R.string.update_err_not_newer; override val retry = false }
    data object NoInstaller : UpdateFailure { override val message = R.string.update_err_no_installer; override val retry = false }
    data object NoSettings : UpdateFailure { override val message = R.string.update_err_settings; override val retry = false }
}

internal fun failureOf(error: Throwable, size: Long): UpdateFailure = when (error) {
    is UpdateProblem -> error.failure
    is UnknownHostException, is NoRouteToHostException -> UpdateFailure.Offline
    is IOException if unreachable(error) -> UpdateFailure.Offline
    is ConnectException -> UpdateFailure.Connection
    is SSLHandshakeException, is SSLPeerUnverifiedException, is SSLKeyException -> UpdateFailure.Insecure
    is SSLException -> UpdateFailure.Connection
    is IOException -> if (noSpace(error)) UpdateFailure.Storage(size) else UpdateFailure.Connection
    else -> UpdateFailure.Save
}

private fun unreachable(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(6).flatMap { sequenceOf(it) + it.suppressed.asSequence() }
        .any { it is UnknownHostException || it.message.orEmpty().let { text -> "ENETUNREACH" in text || "Network is unreachable" in text } }

private fun noSpace(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(4).any { it.message.orEmpty().let { text -> "ENOSPC" in text || "No space left" in text } }

internal const val MAX_APK_BYTES = 400L shl 20
internal const val MAX_SUMS_BYTES = 64 shl 10
private const val SPARE_BYTES = 32L shl 20

internal fun spaceNeeded(size: Long, partial: Long): Long = (size - partial).coerceAtLeast(0) + size + SPARE_BYTES

internal fun reusableBytes(files: UpdateFiles, release: Release, sha256: String): Long {
    val size = release.apk?.size ?: return 0
    if (!files.holds(files.stampOf(release, sha256))) return 0
    if (files.apk.isFile && files.apk.length() == size) return size
    return files.part.length().takeIf { it <= size } ?: 0
}

internal fun spaceFor(files: UpdateFiles, release: Release, sha256: String): Long {
    val size = release.apk?.size ?: return 0
    if (!files.holds(files.stampOf(release, sha256))) files.clear()
    return spaceNeeded(size, reusableBytes(files, release, sha256))
}

internal fun checkSpace(files: UpdateFiles, release: Release, sha256: String, free: Long) {
    val size = release.apk?.size ?: return
    val needed = spaceNeeded(size, reusableBytes(files, release, sha256))
    if (free in 0 until needed) throw UpdateProblem(UpdateFailure.Storage(needed))
}

internal fun claimSpace(needed: Long, free: () -> Long, usable: () -> Long, allocate: (Long) -> Unit) {
    if (free() in 0 until needed) throw UpdateProblem(UpdateFailure.Storage(needed))
    try {
        allocate(needed)
    } catch (e: IOException) {
        if (usable() in 0 until needed) throw UpdateProblem(UpdateFailure.Storage(needed), e)
    }
}

internal fun installSpace(size: Long) = size + SPARE_BYTES

internal fun updateFiles(cache: File, kept: File) =
    UpdateFiles(File(cache, "updates"), File(kept, "update-resume.txt"), File(kept, "update-ask.txt"), File(kept, "update-flow.txt"))

internal class UpdateFiles(val dir: File, val resume: File, val ask: File, val flow: File) {
    val apk = File(dir, "update.apk")
    val part = File(dir, "update.apk.part")
    val stamp = File(dir, "update.txt")

    fun stampOf(release: Release, sha256: String) =
        listOf(release.version, release.apk?.size ?: 0, sha256, release.apk?.name.orEmpty(), release.apk?.url.orEmpty(), release.link).joinToString("\n")
    fun version(): String? = runCatching { stamp.readText().lineSequence().firstOrNull() }.getOrNull()
    fun stale(now: Long): Boolean {
        val times = listOf(apk, part, stamp).filter { it.isFile }.map { it.lastModified() }
        return times.isNotEmpty() && now - times.max() > FLOW_MS
    }
    fun holds(text: String) = runCatching { stamp.readText() }.getOrNull() == text

    fun clear() {
        apk.delete()
        part.delete()
        stamp.delete()
    }
}

internal fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }

internal fun sha256Of(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).use(::drain)
    return hex(digest.digest())
}

private fun drain(input: InputStream) {
    val buffer = ByteArray(64 * 1024)
    do { val count = input.read(buffer) } while (count >= 0)
}

internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

internal fun contentRangeStart(header: String?): Long? =
    header?.let { Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$").find(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()

internal fun downloadApk(http: TrustedHttp, release: Release, sha256: String, files: UpdateFiles, active: () -> Unit, progress: (Long) -> Unit): File {
    val apk = release.apk ?: throw UpdateProblem(UpdateFailure.WrongApp)
    val size = apk.size
    val url = apk.url
    if (size !in 1..MAX_APK_BYTES || !isSha256(sha256)) throw UpdateProblem(UpdateFailure.NoChecksum)
    if (!files.dir.isDirectory && !files.dir.mkdirs()) throw UpdateProblem(UpdateFailure.Save)
    val stamp = files.stampOf(release, sha256)
    if (!files.holds(stamp)) {
        files.clear()
        try { files.stamp.writeText(stamp) } catch (e: IOException) { throw UpdateProblem(failureOf(e, spaceNeeded(size, 0)).takeIf { it is UpdateFailure.Storage } ?: UpdateFailure.Save, e) }
    }
    if (files.apk.isFile) {
        if (files.apk.length() == size && sha256Of(files.apk) == sha256) return files.apk
        files.apk.delete()
    }
    if (files.part.length() > size) files.part.delete()
    if (files.part.length() == size) {
        if (sha256Of(files.part) == sha256 && files.part.renameTo(files.apk)) return files.apk
        files.part.delete()
    }
    val have = files.part.length()
    progress(have)
    http.get(url, if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()) { connection ->
        val code = connection.responseCode
        if (code == 416 && have > 0) {
            files.part.delete()
            throw UpdateProblem(UpdateFailure.Connection)
        }
        val resume = have > 0 && code == 206 && contentRangeStart(connection.getHeaderField("Content-Range")) == have
        if (code == 206 && !resume) {
            files.part.delete()
            throw UpdateProblem(UpdateFailure.Connection)
        }
        if (code != 200 && !resume) throw UpdateProblem(UpdateFailure.Server(code))
        val length = connection.contentLengthLong
        if (length >= 0 && (if (resume) have + length else length) != size) {
            files.part.delete()
            throw UpdateProblem(UpdateFailure.Checksum)
        }
        connection.inputStream.use { receive(it, files, resume, size, sha256, active, progress) }
    }
    return files.apk
}

private fun receive(input: InputStream, files: UpdateFiles, append: Boolean, size: Long, sha256: String, active: () -> Unit, progress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    if (append) files.part.inputStream().use { DigestInputStream(it, digest).use(::drain) }
    else files.part.delete()
    var done = if (append) files.part.length() else 0L
    val output = try { java.io.FileOutputStream(files.part, append) } catch (e: IOException) { throw UpdateProblem(UpdateFailure.Save, e) }
    output.use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            active()
            val count = input.read(buffer)
            if (count < 0) break
            if (done + count > size) {
                out.close()
                files.part.delete()
                throw UpdateProblem(UpdateFailure.Checksum)
            }
            digest.update(buffer, 0, count)
            try { out.write(buffer, 0, count) } catch (e: IOException) { throw UpdateProblem(if (noSpace(e)) UpdateFailure.Storage(spaceNeeded(size, done)) else UpdateFailure.Save, e) }
            done += count
            progress(done)
        }
        try { out.fd.sync() } catch (e: IOException) { throw UpdateProblem(if (noSpace(e)) UpdateFailure.Storage(spaceNeeded(size, done)) else UpdateFailure.Save, e) }
    }
    if (done < size) throw UpdateProblem(UpdateFailure.Connection)
    if (hex(digest.digest()) != sha256) {
        files.part.delete()
        throw UpdateProblem(UpdateFailure.Checksum)
    }
    if (!files.part.isFile || files.part.length() != size) throw UpdateProblem(UpdateFailure.Removed)
    files.apk.delete()
    if (!files.part.renameTo(files.apk)) throw UpdateProblem(UpdateFailure.Save)
}

internal fun checksumFor(http: TrustedHttp, apk: ApkAsset): String =
    apk.sha256 ?: apk.sums?.let { fetchSums(http, it, apk.name) } ?: throw UpdateProblem(UpdateFailure.NoChecksum)

private fun transient(code: Int) = code in 500..599 || code == 408 || code == 429

internal fun retryable(failure: UpdateFailure) =
    failure == UpdateFailure.Connection || failure == UpdateFailure.Offline || (failure is UpdateFailure.Server && transient(failure.code))

internal const val RETRY_LIMIT = 8
internal val RETRY_WAITS = listOf(2_000L, 5_000L, 10_000L)

internal suspend fun <T> patiently(size: Long, have: () -> Long, wait: suspend (Long) -> Unit, attempt: () -> T): T {
    var tries = 0
    var stalled = 0
    var restarted = false
    while (true) {
        val before = have()
        try {
            return attempt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = failureOf(e, size)
            tries++
            if (!restarted && (failure == UpdateFailure.Removed || (failure == UpdateFailure.Checksum && before > 0))) {
                restarted = true
                continue
            }
            stalled = if (have() > before) 0 else stalled + 1
            if (!retryable(failure) || tries >= RETRY_LIMIT || stalled > RETRY_WAITS.size) throw e
            wait(RETRY_WAITS[(stalled - 1).coerceAtLeast(0)])
        }
    }
}

internal fun fetchSums(http: TrustedHttp, url: String, name: String): String? = http.get(url) { connection ->
    val code = connection.responseCode
    if (transient(code)) throw UpdateProblem(UpdateFailure.Server(code))
    if (code != 200) return@get null
    val bytes = connection.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_SUMS_BYTES) return@get null
        }
        out.toByteArray()
    }
    val expected = connection.contentLengthLong
    if (expected >= 0 && bytes.size.toLong() != expected) throw java.io.IOException("The checksum list was cut short")
    parseSums(bytes.decodeToString(), name)
}

internal const val RESUME_MS = 30L * 60 * 1000

internal data class UpdateResume(val release: Release, val stamp: String)

internal fun releaseOfStamp(text: String): Release? {
    val lines = text.lines()
    if (lines.size < 6) return null
    val version = lines[0]
    val bytes = lines[1].toLongOrNull()?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
    val sha = lines[2]
    if (!isSha256(sha) || Version.parse(version) == null) return null
    return Release(version, lines[5], ApkAsset(lines[3], lines[4], bytes, sha, null))
}

internal fun writeResume(files: UpdateFiles, now: Long, stamp: String) {
    files.resume.parentFile?.mkdirs()
    files.resume.writeText("$now\n$stamp")
}

internal fun readResume(files: UpdateFiles, now: Long): UpdateResume? {
    val text = runCatching { files.resume.readText() }.getOrNull()
    files.resume.delete()
    text ?: return null
    val at = text.substringBefore('\n').trim().toLongOrNull() ?: return null
    if (now - at !in 0..RESUME_MS) return null
    val stamp = text.substringAfter('\n', "")
    return releaseOfStamp(stamp)?.let { UpdateResume(it, stamp) }
}

internal fun askText(release: Release): String? {
    val apk = release.apk ?: return null
    val text = listOf(release.version, release.link, apk.name, apk.url, apk.size.toString(), apk.sha256.orEmpty(), apk.sums.orEmpty())
    return text.takeIf { parts -> parts.none { '\n' in it || '\r' in it } }?.joinToString("\n")
}

internal fun writeAsk(files: UpdateFiles, now: Long, release: Release) {
    val text = askText(release) ?: throw IOException("release cannot be kept")
    files.ask.parentFile?.mkdirs()
    files.ask.writeText("$now\n$text")
}

internal fun readAsk(files: UpdateFiles, now: Long): Release? {
    val text = runCatching { files.ask.readText() }.getOrNull()
    files.ask.delete()
    return text?.let { releaseOfAsk(it, now, RESUME_MS) }
}

private fun releaseOfAsk(text: String, now: Long, lifetime: Long): Release? {
    val lines = text.split('\n').takeIf { it.size == 8 } ?: return null
    val at = lines[0].trim().toLongOrNull() ?: return null
    if (now - at !in 0..lifetime) return null
    val version = lines[1].takeIf { Version.parse(it) != null } ?: return null
    val size = lines[5].toLongOrNull()?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
    val sha = lines[6].ifEmpty { null }
    if (sha != null && !isSha256(sha)) return null
    if (lines[3].isEmpty() || lines[4].isEmpty()) return null
    return Release(version, lines[2], ApkAsset(lines[3], lines[4], size, sha, lines[7].ifEmpty { null }))
}

internal const val FLOW_MS = 3L * 24 * 60 * 60 * 1000

internal fun writeFlow(files: UpdateFiles, now: Long, release: Release) {
    val text = askText(release) ?: return
    files.flow.parentFile?.mkdirs()
    files.flow.writeText("$now\n$text")
}

internal fun readFlow(files: UpdateFiles, now: Long): Release? {
    val text = runCatching { files.flow.readText() }.getOrNull() ?: return null
    val release = releaseOfAsk(text, now, FLOW_MS)
    if (release == null) files.flow.delete()
    return release
}

internal fun keptFor(files: UpdateFiles, release: Release): Boolean {
    val size = release.apk?.size ?: return false
    if (files.version() != release.version) return false
    return files.part.length() in 1..size || (files.apk.isFile && files.apk.length() == size)
}

internal fun cachedApkReady(files: UpdateFiles, resume: UpdateResume): Boolean {
    val apk = resume.release.apk ?: return false
    if (!files.holds(resume.stamp) || !files.apk.isFile || files.apk.length() != apk.size) return false
    return runCatching { sha256Of(files.apk) }.getOrNull() == apk.sha256
}
