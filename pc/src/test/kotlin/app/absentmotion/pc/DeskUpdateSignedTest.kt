package app.absentmotion.pc

import app.absentmotion.ui.home.ApkAsset
import app.absentmotion.ui.home.FeedSource
import app.absentmotion.ui.home.Release
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateFailure
import app.absentmotion.ui.home.UpdateProblem
import app.absentmotion.ui.home.signedChecksum
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class DeskUpdateSignedTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val sha = "b".repeat(64)
    private val name = "AbsentMotion-5.0.3-windows-x64.msi"

    @After fun stop() = server.stop(0)

    private fun sign(raw: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update("AbsentMotion update v1\n".toByteArray())
        update(raw)
        sign()
    }

    private fun serve(path: String, body: ByteArray) = server.createContext(path) { exchange ->
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun publish(version: String = "5.0.3", minUpdater: Int = 1, size: Long = 5000, digest: String = sha, sign: Boolean = true) {
        val raw = """{"schema": 1, "version": "$version", "minUpdater": $minUpdater, "files": [{"name": "$name", "size": $size, "sha256": "$digest"}]}""".toByteArray()
        serve("/download/v5.0.3/update-manifest.json", raw)
        serve("/download/v5.0.3/update-manifest.json.sig", if (sign) sign(raw) else ByteArray(64))
    }

    private fun check(size: Long = 5000, listed: String? = sha): Result<String> {
        val source = FeedSource(base)
        val release = Release("5.0.3", "https://github.com/Comett1225/AbsentMotion/releases/tag/v5.0.3")
        val apk = ApkAsset(name, source.asset("v5.0.3", name), size, listed, null, source.asset("v5.0.3", "update-manifest.json"))
        return runCatching { signedChecksum(TrustedHttp(source), release, apk, listOf(key)) }
    }

    private fun failure(result: Result<String>) = (result.exceptionOrNull() as? UpdateProblem)?.failure

    @Test fun aSignedListGivesTheHashOfTheFile() {
        publish()
        assertEquals(sha, check().getOrThrow())
    }

    @Test fun aListSignedByAnotherKeyIsRefused() {
        publish(sign = false)
        assertEquals(UpdateFailure.Unsigned, failure(check()))
    }

    @Test fun aMissingListIsRefusedWithoutFallingBackToChecksums() {
        assertEquals(UpdateFailure.Unsigned, failure(check()))
    }

    @Test fun aListThatNeedsANewerUpdaterIsRefused() {
        publish(minUpdater = 99)
        assertEquals(UpdateFailure.Unsigned, failure(check()))
    }

    @Test fun aListForAnotherVersionIsRefused() {
        publish(version = "5.0.2")
        assertEquals(UpdateFailure.Unsigned, failure(check()))
    }

    @Test fun aSizeOrDigestThatDisagreesWithTheListIsRefused() {
        publish()
        assertEquals(UpdateFailure.Checksum, failure(check(size = 4999)))
        assertEquals(UpdateFailure.Checksum, failure(check(listed = "c".repeat(64))))
        assertTrue(check(listed = null).isSuccess)
    }
}
