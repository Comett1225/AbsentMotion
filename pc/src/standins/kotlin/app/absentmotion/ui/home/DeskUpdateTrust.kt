package app.absentmotion.ui.home

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val UPDATER_VERSION = 1
internal const val MANIFEST_NAME = "update-manifest.json"
internal const val MAX_MANIFEST_BYTES = 64 shl 10
private const val SIGNED_PREFIX = "AbsentMotion update v1\n"

internal val UPDATE_KEYS = listOf("MCowBQYDK2VwAyEAt+mJOGCEHbN9hJjuarwxm5/x5kLiTdRbVVsUJ0Ekg/o=")

internal fun trustedKeys(feed: UpdateSource? = localFeed(), extra: String? = System.getProperty("am.updateKey")): List<String> =
    UPDATE_KEYS + if (feed != null) listOfNotNull(extra?.trim()?.takeIf { it.isNotEmpty() }) else emptyList()

internal data class SignedFile(val name: String, val size: Long, val sha256: String)

internal data class SignedManifest(val version: String, val minUpdater: Int, val files: List<SignedFile>) {
    fun file(name: String) = files.firstOrNull { it.name == name }
}

internal fun signatureHolds(raw: ByteArray, signature: ByteArray, keys: List<String>): Boolean =
    signature.size == 64 && keys.any { key ->
        runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key))))
                update(SIGNED_PREFIX.toByteArray(Charsets.UTF_8))
                update(raw)
                verify(signature)
            }
        }.getOrDefault(false)
    }

internal fun verifiedManifest(raw: ByteArray, signature: ByteArray, keys: List<String> = UPDATE_KEYS): SignedManifest? {
    if (raw.isEmpty() || raw.size > MAX_MANIFEST_BYTES || !signatureHolds(raw, signature, keys)) return null
    return runCatching {
        val json = JSONObject(raw.decodeToString())
        require(json.getInt("schema") == 1)
        val version = json.getString("version")
        require(Version.parse(version) != null)
        val list = json.getJSONArray("files")
        val files = List(list.length()) { i -> list.getJSONObject(i).let { SignedFile(it.getString("name"), it.getLong("size"), it.getString("sha256")) } }
        require(files.map { it.name }.toSet().size == files.size)
        require(files.all { it.size in 1..MAX_APK_BYTES && isSha256(it.sha256) && safeName(it.name) == it.name })
        SignedManifest(version, json.optInt("minUpdater", 1), files)
    }.getOrNull()
}

internal fun fetchBytes(http: TrustedHttp, url: String, limit: Int): ByteArray? = http.get(url) { connection ->
    val code = connection.responseCode
    if (code in 500..599 || code == 408 || code == 429) throw UpdateProblem(UpdateFailure.Server(code))
    if (code != 200) return@get null
    val bytes = connection.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > limit) return@get null
        }
        out.toByteArray()
    }
    val expected = connection.contentLengthLong
    if (expected >= 0 && bytes.size.toLong() != expected) throw java.io.IOException("The release list was cut short")
    bytes
}

internal fun signedChecksum(http: TrustedHttp, release: Release, apk: ApkAsset, keys: List<String> = trustedKeys()): String {
    val url = apk.manifest ?: throw UpdateProblem(UpdateFailure.Unsigned)
    val raw = fetchBytes(http, url, MAX_MANIFEST_BYTES) ?: throw UpdateProblem(UpdateFailure.Unsigned)
    val signature = fetchBytes(http, "$url.sig", 256) ?: throw UpdateProblem(UpdateFailure.Unsigned)
    val manifest = verifiedManifest(raw, signature, keys) ?: throw UpdateProblem(UpdateFailure.Unsigned)
    if (manifest.version != release.version || manifest.minUpdater > UPDATER_VERSION) throw UpdateProblem(UpdateFailure.Unsigned)
    val entry = manifest.file(apk.name) ?: throw UpdateProblem(UpdateFailure.Unsigned)
    if (entry.size != apk.size) throw UpdateProblem(UpdateFailure.Checksum)
    if (apk.sha256 != null && apk.sha256 != entry.sha256) throw UpdateProblem(UpdateFailure.Checksum)
    return entry.sha256
}
