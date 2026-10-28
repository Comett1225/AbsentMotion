package app.absentmotion.ui.home

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

class UpdateDownloadTest {
    private val payload = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private lateinit var foreign: HttpServer
    private lateinit var root: File
    private val requests = AtomicInteger()
    private val ranges = mutableListOf<String?>()
    private var handler: (HttpExchange) -> Unit = { send(it, payload) }

    private inner class Local : UpdateSource {
        override val releases get() = base("/releases")
        override val https = false
        override fun asset(tag: String, name: String) = base("/releases/download/$tag/$name")
        override fun allows(url: URI, first: Boolean) = url.scheme == "http" && url.host == "127.0.0.1" && url.port == server.address.port
    }

    private fun base(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private fun release(size: Long = payload.size.toLong()) =
        Release("9.9.9", HOME_PAGE, ApkAsset("AbsentMotion-9.9.9-foss.apk", base("/releases/download/v9.9.9/AbsentMotion-9.9.9-foss.apk"), size, sha, null))
    private fun files() = updateFiles(File(root, "cache"), File(root, "no_backup"))

    private fun send(exchange: HttpExchange, bytes: ByteArray, code: Int = 200, length: Long = bytes.size.toLong()) {
        exchange.sendResponseHeaders(code, length)
        exchange.responseBody.use { it.write(bytes) }
    }

    @Before fun start() {
        root = Files.createTempDirectory("update-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            synchronized(ranges) { ranges += exchange.requestHeaders.getFirst("Range") }
            try { handler(exchange) } catch (_: IOException) { } finally { exchange.close() }
        }
        server.start()
        foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        foreign.createContext("/") { send(it, payload) }
        foreign.start()
    }

    @After fun stop() {
        server.stop(0)
        foreign.stop(0)
        root.deleteRecursively()
    }

    private fun download(release: Release = release(), digest: String = sha, active: () -> Unit = {}) =
        downloadApk(TrustedHttp(Local(), 2_000, 2_000), release, digest, files(), active) {}

    private fun failure(block: () -> Unit): UpdateFailure {
        try {
            block()
        } catch (e: UpdateProblem) {
            return e.failure
        }
        fail("expected the download to fail")
        error("unreachable")
    }

    @Test fun aCompleteDownloadIsVerifiedAndMovedIntoPlace() {
        val file = download()
        assertArrayEquals(payload, file.readBytes())
        assertEquals(files().apk, file)
        assertFalse(files().part.exists())
    }

    @Test fun aWrongChecksumDeletesTheDownload() {
        assertEquals(UpdateFailure.Checksum, failure { download(digest = "0".repeat(64)) })
        assertFalse(files().apk.exists())
        assertFalse(files().part.exists())
    }

    @Test fun aServerThatSendsMoreThanTheReleaseSizeIsRefused() {
        handler = { send(it, payload + ByteArray(10)) }
        assertEquals(UpdateFailure.Checksum, failure { download() })
        handler = { send(it, payload + ByteArray(10), length = 0) }
        assertEquals(UpdateFailure.Checksum, failure { download() })
        assertFalse(files().part.exists())
        assertFalse(files().apk.exists())
    }

    @Test fun anInterruptedDownloadKeepsItsPartAndResumesWithARange() {
        handler = { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload, 0, 100_000)
            exchange.responseBody.flush()
            throw IOException("connection dropped")
        }
        assertEquals(UpdateFailure.Connection, failureOf(runCatching { download() }.exceptionOrNull()!!, 0))
        val kept = files().part.length()
        assertTrue("kept $kept bytes", kept in 1..100_000L)
        handler = { exchange ->
            val from = exchange.requestHeaders.getFirst("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
            exchange.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
            send(exchange, payload.copyOfRange(from, payload.size), 206)
        }
        assertArrayEquals(payload, download().readBytes())
        assertEquals("bytes=$kept-", ranges.last())
    }

    @Test fun aServerThatIgnoresTheRangeRestartsFromTheBeginning() {
        files().dir.mkdirs()
        files().stamp.writeText(files().stampOf(release(), sha))
        files().part.writeBytes(payload.copyOfRange(0, 5_000))
        assertArrayEquals(payload, download().readBytes())
        assertEquals("bytes=5000-", ranges.single())
    }

    @Test fun aPartFromAnotherReleaseIsNeverReused() {
        files().dir.mkdirs()
        files().stamp.writeText("1.0.0\n5\n${"0".repeat(64)}\nx\ny\nz")
        files().part.writeBytes(ByteArray(5_000))
        assertArrayEquals(payload, download().readBytes())
        assertEquals(null, ranges.single())
    }

    @Test fun aVerifiedDownloadIsReusedWithoutAskingAgain() {
        download()
        val before = requests.get()
        assertArrayEquals(payload, download().readBytes())
        assertEquals(before, requests.get())
        files().apk.appendBytes(byteArrayOf(1))
        assertArrayEquals(payload, download().readBytes())
        assertEquals(before + 1, requests.get())
    }

    @Test fun redirectsAreFollowedOnlyInsideTheAllowedSource() {
        handler = { exchange ->
            if (exchange.requestURI.path.startsWith("/cdn/")) send(exchange, payload)
            else {
                exchange.responseHeaders.add("Location", "/cdn/file")
                exchange.sendResponseHeaders(302, -1)
            }
        }
        assertArrayEquals(payload, download().readBytes())
        files().clear()
        handler = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreign.address.port}/a.apk")
            exchange.sendResponseHeaders(302, -1)
        }
        assertEquals(UpdateFailure.Untrusted, failure { download() })
        handler = { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(307, -1)
        }
        assertEquals(UpdateFailure.Untrusted, failure { download() })
        handler = { exchange -> exchange.sendResponseHeaders(302, -1) }
        assertEquals(UpdateFailure.Untrusted, failure { download() })
        assertFalse(files().apk.exists())
    }

    @Test fun aStartAddressOutsideTheSourceIsNeverOpened() {
        val outside = release().copy(apk = release().apk!!.copy(url = "http://127.0.0.1:${foreign.address.port}/a.apk"))
        assertEquals(UpdateFailure.Untrusted, failure { download(outside) })
        assertEquals(0, requests.get())
    }

    @Test fun serverErrorsKeepTheirCode() {
        handler = { send(it, "missing".toByteArray(), 404) }
        assertEquals(UpdateFailure.Server(404), failure { download() })
    }

    @Test fun cancellingStopsTheDownload() {
        var chunks = 0
        try {
            download { if (++chunks > 1) throw CancellationException("cancelled") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertFalse(files().apk.exists())
    }

    @Test fun missingSizesOrChecksumsAreRefusedBeforeAnyRequest() {
        assertEquals(UpdateFailure.NoChecksum, failure { download(digest = "") })
        assertEquals(UpdateFailure.NoChecksum, failure { download(release(0)) })
        assertEquals(UpdateFailure.NoChecksum, failure { download(release(MAX_APK_BYTES + 1)) })
        assertEquals(0, requests.get())
    }

    @Test fun checksumListsAreReadThroughTheSameSourceAndBounded() {
        val name = "AbsentMotion-9.9.9-foss.apk"
        handler = { send(it, "$sha  $name\n".toByteArray()) }
        assertEquals(sha, fetchSums(TrustedHttp(Local()), base("/SHA256SUMS.txt"), name))
        handler = { send(it, ByteArray(MAX_SUMS_BYTES + 10) { 'a'.code.toByte() }) }
        assertEquals(null, fetchSums(TrustedHttp(Local()), base("/SHA256SUMS.txt"), name))
        handler = { send(it, ByteArray(0), 404) }
        assertEquals(null, fetchSums(TrustedHttp(Local()), base("/SHA256SUMS.txt"), name))
    }

    @Test fun errorsAreSortedIntoClearReasons() {
        assertEquals(UpdateFailure.Offline, failureOf(UnknownHostException("api.github.com"), 5))
        assertEquals(UpdateFailure.Offline, failureOf(ConnectException("failed to connect (ENETUNREACH)"), 5))
        assertEquals(UpdateFailure.Connection, failureOf(ConnectException("Connection refused"), 5))
        assertEquals(UpdateFailure.Offline, failureOf(ConnectException("Failed to connect to /10.0.2.2:18413").apply { initCause(ConnectException("connect failed: ENETUNREACH (Network is unreachable)")) }, 5))
        assertEquals(UpdateFailure.Offline, failureOf(IOException("Failed").apply { addSuppressed(UnknownHostException("api.github.com")) }, 5))
        assertEquals(UpdateFailure.Insecure, failureOf(SSLHandshakeException("bad certificate"), 5))
        assertEquals(UpdateFailure.Insecure, failureOf(SSLPeerUnverifiedException("peer not authenticated"), 5))
        assertEquals(UpdateFailure.Connection, failureOf(SSLException("Read error: ssl=0x7: I/O error during system call, Connection reset by peer"), 5))
        assertEquals(UpdateFailure.Storage(5), failureOf(IOException("write failed: ENOSPC (No space left on device)"), 5))
        assertEquals(UpdateFailure.Connection, failureOf(IOException("unexpected end of stream"), 5))
        assertEquals(UpdateFailure.Signature, failureOf(UpdateProblem(UpdateFailure.Signature), 5))
        assertEquals(UpdateFailure.Save, failureOf(IllegalStateException(), 5))
    }

    @Test fun theSpaceCheckCountsTheDownloadAndTheInstallCopy() {
        assertEquals(100L + 100L + (32L shl 20), spaceNeeded(100, 0))
        assertEquals(40L + 100L + (32L shl 20), spaceNeeded(100, 60))
        assertEquals(100L + (32L shl 20), spaceNeeded(100, 500))
    }

    @Test fun contentRangesAreReadStrictly() {
        assertEquals(10L, contentRangeStart("bytes 10-99/100"))
        assertEquals(10L, contentRangeStart("bytes 10-99/*"))
        assertEquals(null, contentRangeStart("bytes */100"))
        assertEquals(null, contentRangeStart("items 10-99/100"))
        assertEquals(null, contentRangeStart(null))
    }

    private val spare = 32L shl 20
    private val size get() = payload.size.toLong()
    private val apkName = "AbsentMotion-9.9.9-foss.apk"
    private fun listed(digest: String? = null) = release().apk!!.copy(sha256 = digest, sums = base("/releases/download/v9.9.9/SHA256SUMS.txt"))
    private fun stamped(release: Release = release(), digest: String = sha) {
        files().dir.mkdirs()
        files().stamp.writeText(files().stampOf(release, digest))
    }

    @Test fun aCompleteCachedDownloadOfThisReleaseOnlyNeedsRoomForTheInstallCopy() {
        download()
        assertEquals(size, reusableBytes(files(), release(), sha))
        checkSpace(files(), release(), sha, size + spare)
        assertEquals(UpdateFailure.Storage(size + spare), failure { checkSpace(files(), release(), sha, size + spare - 1) })
        val before = requests.get()
        assertArrayEquals(payload, download().readBytes())
        assertEquals(before, requests.get())
    }

    @Test fun aCachedFileOfTheRightLengthIsStillHashedBeforeItIsUsed() {
        download()
        files().apk.writeBytes(payload.copyOf().also { it[1234] = (it[1234] + 1).toByte() })
        assertEquals(size, reusableBytes(files(), release(), sha))
        val before = requests.get()
        assertArrayEquals(payload, download().readBytes())
        assertEquals(before + 1, requests.get())
        assertEquals(null, ranges.last())
    }

    @Test fun aPartialDownloadCountsOnlyTheBytesItHas() {
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 100_000))
        assertEquals(100_000L, reusableBytes(files(), release(), sha))
        val needed = (size - 100_000) + size + spare
        checkSpace(files(), release(), sha, needed)
        assertEquals(UpdateFailure.Storage(needed), failure { checkSpace(files(), release(), sha, needed - 1) })
    }

    @Test fun filesFromAnotherReleaseOrOfTheWrongSizeAreNotCounted() {
        val full = 2 * size + spare
        files().dir.mkdirs()
        files().apk.writeBytes(payload)
        files().part.writeBytes(payload.copyOfRange(0, 50_000))
        val asset = release().apk!!
        for (other in listOf(
            files().stampOf(release().copy(version = "9.9.8"), sha),
            files().stampOf(release(), "0".repeat(64)),
            files().stampOf(release().copy(apk = asset.copy(size = size + 1)), sha),
            files().stampOf(release().copy(apk = asset.copy(url = base("/releases/download/v9.9.9/other.apk"))), sha),
        )) {
            files().stamp.writeText(other)
            assertEquals(other, 0L, reusableBytes(files(), release(), sha))
            assertEquals(UpdateFailure.Storage(full), failure { checkSpace(files(), release(), sha, full - 1) })
        }
        files().stamp.delete()
        assertEquals(0L, reusableBytes(files(), release(), sha))
        stamped()
        files().apk.appendBytes(byteArrayOf(1))
        assertEquals(50_000L, reusableBytes(files(), release(), sha))
        files().part.writeBytes(payload + byteArrayOf(1))
        assertEquals(0L, reusableBytes(files(), release(), sha))
        checkSpace(files(), release(), sha, full)
        checkSpace(files(), release(), sha, -1)
    }

    @Test fun aTemporaryServerErrorOnTheChecksumListCanBeRetried() {
        for (code in listOf(500, 502, 503, 504, 408, 429)) {
            handler = { send(it, "busy".toByteArray(), code) }
            val failure = failure { checksumFor(TrustedHttp(Local()), listed()) }
            assertEquals(UpdateFailure.Server(code), failure)
            assertTrue(failure.retry)
        }
        handler = { send(it, "$sha  $apkName\n".toByteArray()) }
        assertEquals(sha, checksumFor(TrustedHttp(Local()), listed()))
    }

    @Test fun aMissingOrAmbiguousChecksumIsStillRefusedWithoutRetry() {
        val other = "1".repeat(64)
        for (answer in listOf<(HttpExchange) -> Unit>(
            { send(it, "gone".toByteArray(), 404) },
            { send(it, "gone".toByteArray(), 410) },
            { send(it, "$sha  $apkName\n$other  $apkName\n".toByteArray()) },
            { send(it, "$sha  AbsentMotion-9.9.9-play.apk\n".toByteArray()) },
            { send(it, "<html>error</html>".toByteArray()) },
        )) {
            handler = answer
            val failure = failure { checksumFor(TrustedHttp(Local()), listed()) }
            assertEquals(UpdateFailure.NoChecksum, failure)
            assertFalse(failure.retry)
        }
        assertEquals(UpdateFailure.NoChecksum, failure { checksumFor(TrustedHttp(Local()), release().apk!!.copy(sha256 = null)) })
    }

    @Test fun aChecksumListCutOffByTheNetworkCanBeRetried() {
        val full = "${"2".repeat(64)}  $apkName.idsig\n$sha  $apkName\n".toByteArray()
        handler = { exchange ->
            exchange.sendResponseHeaders(200, full.size.toLong())
            exchange.responseBody.write(full, 0, 64 + 2 + apkName.length)
            exchange.responseBody.flush()
            throw IOException("connection dropped")
        }
        val failure = failureOf(runCatching { checksumFor(TrustedHttp(Local(), 2_000, 2_000), listed()) }.exceptionOrNull()!!, 1)
        assertEquals(UpdateFailure.Connection, failure)
        assertTrue(failure.retry)
    }

    @Test fun aPublishedDigestNeedsNoChecksumList() {
        handler = { send(it, "busy".toByteArray(), 503) }
        assertEquals(sha, checksumFor(TrustedHttp(Local()), listed(digest = sha)))
        assertEquals(0, requests.get())
    }

    @Test fun theSettingsMarkerIsKeptOutsideTheCacheAndExpires() {
        val cache = File(root, "cache")
        assertFalse(files().resume.toPath().startsWith(cache.toPath()))
        assertTrue(files().dir.toPath().startsWith(cache.toPath()))
        download()
        val stamp = files().stamp.readText()
        writeResume(files(), 1_000_000, stamp)
        assertEquals(UpdateResume(release(), stamp), readResume(files(), 1_000_000 + RESUME_MS))
        assertFalse(files().resume.exists())
        assertNull(readResume(files(), 1_000_000))
        for (now in listOf(1_000_000 + RESUME_MS + 1, 999_999L)) {
            writeResume(files(), 1_000_000, stamp)
            assertNull(readResume(files(), now))
            assertFalse(files().resume.exists())
        }
        files().resume.writeText("1000000")
        assertNull(readResume(files(), 1_000_000))
        writeResume(files(), 1_000_000, "9.9.9\n5\nnot-a-hash\nx\ny\nz")
        assertNull(readResume(files(), 1_000_000))
    }

    @Test fun theQuestionBeforeDownloadSurvivesARestartOnceAndExpires() {
        val cache = File(root, "cache")
        assertFalse(files().ask.toPath().startsWith(cache.toPath()))
        assertNotEquals(files().resume, files().ask)
        val listed = release()
        val withSums = listed.copy(apk = listed.apk!!.copy(sha256 = null, sums = base("/releases/download/v9.9.9/SHA256SUMS.txt")))
        for (kept in listOf(listed, withSums)) {
            writeAsk(files(), 1_000_000, kept)
            assertFalse(files().resume.exists())
            files().clear()
            assertEquals(kept, readAsk(files(), 1_000_000 + RESUME_MS))
            assertFalse(files().ask.exists())
            assertNull(readAsk(files(), 1_000_000))
        }
        for (now in listOf(1_000_000 + RESUME_MS + 1, 999_999L)) {
            writeAsk(files(), 1_000_000, listed)
            assertNull(readAsk(files(), now))
            assertFalse(files().ask.exists())
        }
        val good = askText(listed)!!.split('\n')
        for ((index, bad) in listOf(0 to "nine", 4 to "0", 4 to "${MAX_APK_BYTES + 1}", 5 to "not-a-hash", 2 to "", 3 to "")) {
            files().ask.writeText("1000000\n" + good.toMutableList().also { it[index] = bad }.joinToString("\n"))
            assertNull(readAsk(files(), 1_000_000))
        }
        files().ask.writeText("1000000\n" + good.joinToString("\n") + "\nextra")
        assertNull(readAsk(files(), 1_000_000))
        assertNull(askText(listed.copy(apk = null)))
        val broken = listed.copy(link = "$HOME_PAGE\nhttps://example.invalid")
        assertNull(askText(broken))
        assertThrows(IOException::class.java) { writeAsk(files(), 1_000_000, broken) }
        assertFalse(files().ask.exists())
    }

    @Test fun aCacheReclaimedWhileInSettingsIsNoticedAndNeverInstallsAStaleFile() {
        download()
        writeResume(files(), 5, files().stamp.readText())
        val resume = readResume(files(), 6)!!
        assertTrue(cachedApkReady(files(), resume))
        files().apk.delete()
        assertFalse(cachedApkReady(files(), resume))
        download()
        files().apk.writeBytes(payload.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertFalse(cachedApkReady(files(), resume))
        files().clear()
        download()
        files().stamp.writeText(files().stampOf(release().copy(version = "9.9.8"), sha))
        assertFalse(cachedApkReady(files(), resume))
        files().clear()
        download()
        writeResume(files(), 5, files().stamp.readText())
        File(root, "cache").deleteRecursively()
        val kept = readResume(files(), 6)
        assertEquals(release(), kept?.release)
        assertFalse(cachedApkReady(files(), kept!!))
    }

    private fun serveRange(exchange: HttpExchange, stopAfter: Int = Int.MAX_VALUE) {
        val range = exchange.requestHeaders.getFirst("Range")
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
        exchange.responseHeaders.add("Connection", "close")
        exchange.sendResponseHeaders(if (range != null) 206 else 200, (payload.size - from).toLong())
        val end = if (stopAfter >= payload.size - from) payload.size else from + stopAfter
        exchange.responseBody.write(payload, from, end - from)
        exchange.responseBody.flush()
        if (end < payload.size) throw IOException("connection dropped")
    }

    private val waits = mutableListOf<Long>()

    private fun patientDownload(release: Release = release(), active: () -> Unit = {}) = runBlocking {
        patiently(release.apk!!.size, { files().part.length() }, { waits += it }) { download(release, active = active) }
    }

    private fun patientFailure(block: () -> Unit): UpdateFailure {
        val error = runCatching(block).exceptionOrNull() ?: error("expected the download to fail")
        return failureOf(error, size)
    }

    @Test fun aDroppedConnectionResumesByItselfFromTheBytesItHas() {
        var first = true
        handler = { exchange -> val cut = first; first = false; serveRange(exchange, if (cut) 100_000 else Int.MAX_VALUE) }
        assertArrayEquals(payload, patientDownload().readBytes())
        assertEquals(2, requests.get())
        assertNull(ranges.first())
        assertTrue(ranges.last()!!.startsWith("bytes="))
        assertTrue(ranges.last()!!.removePrefix("bytes=").removeSuffix("-").toLong() in 1..100_000L)
        assertEquals(listOf(RETRY_WAITS[0]), waits)
    }

    @Test fun aFlakyConnectionThatKeepsMovingIsFollowedToTheEnd() {
        handler = { serveRange(it, 60_000) }
        assertArrayEquals(payload, patientDownload().readBytes())
        assertTrue("${requests.get()} requests", requests.get() in 5..RETRY_LIMIT)
        assertEquals(List(requests.get() - 1) { RETRY_WAITS[0] }, waits)
    }

    @Test fun retriesStopWhenNothingMovesOrAfterTheHardLimit() {
        handler = { send(it, "busy".toByteArray(), 503) }
        assertEquals(UpdateFailure.Server(503), patientFailure { patientDownload() })
        assertEquals(1 + RETRY_WAITS.size, requests.get())
        assertEquals(RETRY_WAITS, waits)
        assertFalse(files().apk.exists())
        files().clear()
        requests.set(0)
        waits.clear()
        handler = { serveRange(it, 10_000) }
        assertEquals(UpdateFailure.Connection, patientFailure { patientDownload() })
        assertEquals(RETRY_LIMIT, requests.get())
        assertEquals(RETRY_LIMIT - 1, waits.size)
        assertTrue(files().part.length() in 1..RETRY_LIMIT * 10_000L)
    }

    @Test fun failuresThatCannotHealAreNeverRetried() {
        handler = { send(it, "missing".toByteArray(), 404) }
        assertEquals(UpdateFailure.Server(404), patientFailure { patientDownload() })
        assertEquals(1, requests.get())
        handler = { send(it, payload.copyOf().also { bytes -> bytes[9] = (bytes[9] + 1).toByte() }) }
        assertEquals(UpdateFailure.Checksum, patientFailure { patientDownload() })
        assertEquals(2, requests.get())
        handler = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreign.address.port}/a.apk")
            exchange.sendResponseHeaders(302, -1)
        }
        assertEquals(UpdateFailure.Untrusted, patientFailure { patientDownload() })
        assertEquals(3, requests.get())
        var tries = 0
        assertEquals(UpdateFailure.Storage(7), patientFailure {
            runBlocking { patiently(size, { 0L }, { waits += it }) { tries++; claimSpace(7, { 6 }, { 6 }) { } } }
        })
        assertEquals(1, tries)
        assertTrue(waits.isEmpty())
        assertFalse(retryable(UpdateFailure.Storage(1)) || retryable(UpdateFailure.Checksum) || retryable(UpdateFailure.Insecure) || retryable(UpdateFailure.Server(404)))
        assertTrue(retryable(UpdateFailure.Connection) && retryable(UpdateFailure.Offline) && retryable(UpdateFailure.Server(502)) && retryable(UpdateFailure.Server(429)))
    }

    @Test fun cancellingDuringARetryIsNeverSwallowed() {
        handler = { serveRange(it, 10_000) }
        try {
            patientDownload { throw CancellationException("cancelled") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, requests.get())
        assertTrue(waits.isEmpty())
        handler = { send(it, "busy".toByteArray(), 503) }
        try {
            runBlocking { patiently(size, { 0L }, { throw CancellationException("cancelled while waiting") }) { download() } }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(2, requests.get())
    }

    @Test fun aCorruptPartIsReplacedByOneFreshDownload() {
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 5_000).also { it[100] = (it[100] + 1).toByte() })
        handler = { serveRange(it) }
        assertArrayEquals(payload, patientDownload().readBytes())
        assertEquals(listOf("bytes=5000-", null), ranges)
        assertTrue(waits.isEmpty())
    }

    @Test fun aRangeThatTheServerCannotServeStartsAgainFromTheBeginning() {
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 5_000))
        handler = { exchange ->
            if (exchange.requestHeaders.getFirst("Range") != null) exchange.sendResponseHeaders(416, -1) else send(exchange, payload)
        }
        assertEquals(UpdateFailure.Connection, failure { download() })
        assertFalse(files().part.exists())
        assertArrayEquals(payload, download().readBytes())
        files().clear()
        ranges.clear()
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 5_000))
        assertArrayEquals(payload, patientDownload().readBytes())
        assertEquals(listOf("bytes=5000-", null), ranges)
        handler = { exchange -> exchange.sendResponseHeaders(416, -1) }
        files().clear()
        assertEquals(UpdateFailure.Server(416), failure { download() })
    }

    @Test fun aPartRemovedByAndroidDuringTheDownloadIsFetchedAgain() {
        var chunks = 0
        assertEquals(UpdateFailure.Removed, failure { download { if (++chunks == 2) files().part.delete() } })
        assertFalse(files().apk.exists())
        chunks = 0
        requests.set(0)
        assertArrayEquals(payload, patientDownload { if (++chunks == 2) files().part.delete() }.readBytes())
        assertEquals(2, requests.get())
        assertTrue(waits.isEmpty())
    }

    @Test fun spaceIsClaimedFromAndroidBeforeItIsUsed() {
        val asked = mutableListOf<Long>()
        claimSpace(100, { 100 }, { 0 }) { asked += it }
        assertEquals(listOf(100L), asked)
        assertEquals(UpdateFailure.Storage(100), failure { claimSpace(100, { 99 }, { 1_000 }) { asked += it } })
        assertEquals(listOf(100L), asked)
        claimSpace(100, { -1 }, { -1 }) { asked += it }
        assertEquals(listOf(100L, 100L), asked)
        claimSpace(100, { 500 }, { 100 }) { throw IOException("allocation is not supported") }
        claimSpace(100, { 500 }, { -1 }) { throw IOException("allocation is not supported") }
        val refused = runCatching { claimSpace(100, { 500 }, { 99 }) { throw IOException("Failed to allocate 100") } }.exceptionOrNull()
        assertEquals(UpdateFailure.Storage(100), (refused as UpdateProblem).failure)
        assertTrue(refused.cause is IOException)
        assertEquals(size + spare, installSpace(size))
    }

    @Test fun anUnfinishedUpdateIsRememberedOutsideTheCacheUntilItEnds() {
        val cache = File(root, "cache")
        assertFalse(files().flow.toPath().startsWith(cache.toPath()))
        assertNotEquals(files().flow, files().ask)
        assertNotEquals(files().flow, files().resume)
        writeFlow(files(), 1_000_000, release())
        files().clear()
        assertEquals(release(), readFlow(files(), 1_000_000 + FLOW_MS))
        assertEquals(release(), readFlow(files(), 1_000_001))
        assertTrue(files().flow.exists())
        for (now in listOf(1_000_000 + FLOW_MS + 1, 999_999L)) {
            writeFlow(files(), 1_000_000, release())
            assertNull(readFlow(files(), now))
            assertFalse(files().flow.exists())
        }
        files().flow.writeText("1000000\nbroken")
        assertNull(readFlow(files(), 1_000_000))
        assertFalse(files().flow.exists())
        assertNull(readFlow(files(), 1_000_000))
        writeFlow(files(), 1_000_000, release().copy(apk = null))
        assertFalse(files().flow.exists())
    }

    @Test fun filesOfAnotherReleaseAreDeletedBeforeTheFreeSpaceIsMeasured() {
        files().dir.mkdirs()
        files().stamp.writeText(files().stampOf(release().copy(version = "9.9.8"), sha))
        files().part.writeBytes(payload.copyOfRange(0, 50_000))
        files().apk.writeBytes(payload)
        assertEquals(2 * size + spare, spaceFor(files(), release(), sha))
        assertFalse(files().part.exists())
        assertFalse(files().apk.exists())
        assertFalse(files().stamp.exists())
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 50_000))
        assertEquals((size - 50_000) + size + spare, spaceFor(files(), release(), sha))
        assertEquals(50_000L, files().part.length())
        assertEquals(0L, spaceFor(files(), release().copy(apk = null), sha))
        assertTrue(files().part.exists())
    }

    @Test fun downloadFilesUntouchedForLongerThanAnUnfinishedUpdateLastsAreStale() {
        val now = 10 * FLOW_MS
        assertFalse(files().stale(now))
        stamped()
        files().part.writeBytes(payload.copyOfRange(0, 1_000))
        for (file in listOf(files().stamp, files().part)) assertTrue(file.setLastModified(now - FLOW_MS - 1_000))
        assertTrue(files().stale(now))
        assertTrue(files().part.setLastModified(now - FLOW_MS + 1_000))
        assertFalse(files().stale(now))
        files().part.delete()
        assertTrue(files().stale(now))
        files().apk.writeBytes(payload)
        assertTrue(files().apk.setLastModified(now))
        assertFalse(files().stale(now))
    }

    @Test fun onlyBytesOfTheSameReleaseCountAsSomethingToContinue() {
        assertFalse(keptFor(files(), release()))
        stamped()
        assertFalse(keptFor(files(), release()))
        files().part.writeBytes(payload.copyOfRange(0, 1_000))
        assertTrue(keptFor(files(), release()))
        assertFalse(keptFor(files(), release().copy(version = "9.9.10")))
        assertFalse(keptFor(files(), release().copy(apk = null)))
        files().part.writeBytes(payload + byteArrayOf(1))
        assertFalse(keptFor(files(), release()))
        files().part.delete()
        files().apk.writeBytes(payload)
        assertTrue(keptFor(files(), release()))
        files().apk.writeBytes(payload.copyOfRange(0, 10))
        assertFalse(keptFor(files(), release()))
    }
}
