package app.absentmotion.pc

import app.absentmotion.ui.home.signatureHolds
import app.absentmotion.ui.home.verifiedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class DeskUpdateTrustTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
    private val sha = "a".repeat(64)

    private fun sign(raw: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update("AbsentMotion update v1\n".toByteArray())
        update(raw)
        sign()
    }

    private fun manifest(version: String = "5.0.3", files: String = """{"name": "AbsentMotion-5.0.3-windows-x64.msi", "size": 1234, "sha256": "$sha"}""") =
        """{"schema": 1, "version": "$version", "minUpdater": 1, "files": [$files]}""".toByteArray()

    @Test fun aSignedListIsAccepted() {
        val raw = manifest()
        val list = verifiedManifest(raw, sign(raw), listOf(key))
        assertNotNull(list)
        assertEquals("5.0.3", list!!.version)
        assertEquals(1234L, list.file("AbsentMotion-5.0.3-windows-x64.msi")!!.size)
        assertNull(list.file("other.msi"))
    }

    @Test fun anEditedListIsRefused() {
        val raw = manifest()
        val signature = sign(raw)
        assertNull(verifiedManifest(manifest(version = "5.0.4"), signature, listOf(key)))
        assertNull(verifiedManifest(raw.copyOf().also { it[it.size - 3] = 'x'.code.toByte() }, signature, listOf(key)))
    }

    @Test fun anotherKeyOrAShortSignatureIsRefused() {
        val raw = manifest()
        assertNull(verifiedManifest(raw, sign(raw), listOf(other)))
        assertFalse(signatureHolds(raw, ByteArray(10), listOf(key)))
        assertFalse(signatureHolds(raw, ByteArray(64), listOf(key)))
        assertTrue(signatureHolds(raw, sign(raw), listOf(other, key)))
    }

    @Test fun theSignatureCoversTheProductPrefix() {
        val raw = manifest()
        val bare = Signature.getInstance("Ed25519").run { initSign(pair.private); update(raw); sign() }
        assertNull(verifiedManifest(raw, bare, listOf(key)))
    }

    @Test fun badEntriesAreRefusedEvenWhenSigned() {
        fun refused(files: String) = verifiedManifest(manifest(files = files), sign(manifest(files = files)), listOf(key)) == null
        assertTrue(refused("""{"name": "../x.msi", "size": 5, "sha256": "$sha"}"""))
        assertTrue(refused("""{"name": "a.msi", "size": 0, "sha256": "$sha"}"""))
        assertTrue(refused("""{"name": "a.msi", "size": 5, "sha256": "XYZ"}"""))
        assertTrue(refused("""{"name": "a.msi", "size": 5, "sha256": "$sha"}, {"name": "a.msi", "size": 6, "sha256": "$sha"}"""))
        val notJson = "not json".toByteArray()
        assertNull(verifiedManifest(notJson, sign(notJson), listOf(key)))
        val future = """{"schema": 2, "version": "5.0.3", "files": []}""".toByteArray()
        assertNull(verifiedManifest(future, sign(future), listOf(key)))
    }

    @Test fun theShippedKeyIsAValidEd25519Key() {
        assertFalse(signatureHolds(manifest(), ByteArray(64), app.absentmotion.ui.home.UPDATE_KEYS))
        assertEquals(1, app.absentmotion.ui.home.UPDATE_KEYS.size)
    }

    @Test fun anExtraKeyIsTrustedOnlyWithALoopbackTestFeed() {
        val feed = app.absentmotion.ui.home.localFeed("http://127.0.0.1:8899")
        assertEquals(1, app.absentmotion.ui.home.trustedKeys(null, key).size)
        assertEquals(2, app.absentmotion.ui.home.trustedKeys(feed, key).size)
        assertEquals(1, app.absentmotion.ui.home.trustedKeys(feed, null).size)
        assertNull(app.absentmotion.ui.home.localFeed("https://example.com"))
        assertNull(app.absentmotion.ui.home.localFeed("http://192.168.1.5:8899"))
    }
}
