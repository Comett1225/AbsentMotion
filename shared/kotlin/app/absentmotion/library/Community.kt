package app.absentmotion.library

import app.absentmotion.BuildConfig
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.HOME_PAGE
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateSource
import app.absentmotion.ui.home.isSha256
import app.absentmotion.ui.home.sha256Of
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private val COMMUNITY_OWNER = HOME_PAGE.removePrefix("https://github.com/").substringBefore('/')
val COMMUNITY_RAW = "https://raw.githubusercontent.com/$COMMUNITY_OWNER/AbsentMotion-Community/main/"

private const val INDEX_MAX_BYTES = 4L shl 20
private const val THUMB_MAX_BYTES = 4L shl 20
private val NAME_REGEX = Regex("^[a-z0-9-]{1,48}$")
private val PATH_REGEX = Regex("^[A-Za-z0-9._/-]+$")

internal object CommunitySource : UpdateSource {
    private const val HOST = "raw.githubusercontent.com"
    private val PREFIX = "/${COMMUNITY_OWNER.lowercase(Locale.ROOT)}/absentmotion-community/main/"

    override val releases: String = COMMUNITY_RAW

    override fun asset(tag: String, name: String): String =
        "$COMMUNITY_RAW${name.trimStart('/')}"

    override fun allows(url: URI, first: Boolean): Boolean {
        if (!url.isAbsolute || url.isOpaque || !url.scheme.equals("https", ignoreCase = true)) return false
        val authority = url.rawAuthority ?: return false
        if ('@' in authority || '\\' in authority) return false
        if (url.rawUserInfo != null || url.userInfo != null) return false
        if (url.rawFragment != null || url.fragment != null) return false
        if (url.port != -1 && url.port != 443) return false
        val host = url.host?.lowercase(Locale.ROOT) ?: return false
        if (host != HOST) return false
        val rawPath = url.rawPath ?: return false
        val path = url.path ?: return false
        if ('\\' in rawPath || '\\' in path) return false
        if (!rawPath.lowercase(Locale.ROOT).startsWith(PREFIX) || !path.lowercase(Locale.ROOT).startsWith(PREFIX)) return false
        if (rawPath.split('/').any { it == "." || it == ".." }) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        return true
    }
}

enum class CommunityKind(
    val id: String,
    val folder: String,
    val maxBytes: Long,
    val ext: String
) {
    Bundle("bundle", "bundles", 20L shl 20, "absproj"),
    Preset("preset", "presets", 1L shl 20, "xml")
}

data class CommunityItem(
    val kind: CommunityKind,
    val name: String,
    val title: String,
    val creator: String,
    val description: String,
    val tags: List<String>,
    val added: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val thumbnail: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val lengthMs: Long = 0L
)

data class CommunityIndex(
    val items: List<CommunityItem>,
    val tooNew: Boolean = false
)

class CommunityDamagedException(message: String) : IOException(message)

fun safeCommunityPath(path: String, folder: String): Boolean {
    if (path.isEmpty() || folder.isEmpty()) return false
    if (path.startsWith("/") || path.endsWith("/")) return false
    if (folder.startsWith("/")) return false
    if (!PATH_REGEX.matches(path) || !PATH_REGEX.matches(folder)) return false
    val prefix = if (folder.endsWith('/')) folder else "$folder/"
    if (!path.startsWith(prefix) || path.length <= prefix.length) return false
    if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
    if (folder.trimEnd('/').split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
    return true
}

fun parseCommunityIndex(text: String, versionCode: Int): CommunityIndex {
    val root = JSONObject(text)
    val format = root.optString("format", "")
    val version = root.optInt("version", -1)
    if (format != "absentmotion-community" || version != 2) {
        return CommunityIndex(emptyList(), tooNew = true)
    }
    val arr = root.optJSONArray("items") ?: return CommunityIndex(emptyList())
    val items = ArrayList<CommunityItem>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val kind = when (obj.optString("kind", "")) {
            CommunityKind.Bundle.id -> CommunityKind.Bundle
            CommunityKind.Preset.id -> CommunityKind.Preset
            else -> continue
        }
        val minVersion = obj.optInt("minVersion", 0)
        if (minVersion > versionCode) continue
        val name = obj.optString("name", "")
        if (!NAME_REGEX.matches(name)) continue
        val folder = "${kind.folder}/$name/"
        val file = obj.optString("file", "")
        if (!safeCommunityPath(file, folder)) continue
        val rawThumb = if (obj.isNull("thumbnail")) "" else obj.optString("thumbnail", "")
        if (rawThumb.isNotEmpty() && !safeCommunityPath(rawThumb, folder)) continue
        val bytes = obj.optLong("bytes", -1L)
        if (bytes <= 0L || bytes > kind.maxBytes) continue
        val sha256 = obj.optString("sha256", "")
        if (!isSha256(sha256)) continue
        val title = obj.optString("title", "").trim().take(80)
        val creator = obj.optString("creator", "").trim().take(80)
        val description = obj.optString("description", "").trim().take(500)
        val tagsArr = obj.optJSONArray("tags")
        val tags = ArrayList<String>(8)
        if (tagsArr != null) {
            for (j in 0 until tagsArr.length()) {
                if (tags.size >= 8) break
                val tag = tagsArr.optString(j, "").trim().take(30)
                if (tag.isNotEmpty()) tags.add(tag)
            }
        }
        val added = obj.optString("added", "").trim().take(32)
        val width = obj.optInt("width", 0).coerceAtLeast(0)
        val height = obj.optInt("height", 0).coerceAtLeast(0)
        val lengthMs = obj.optLong("lengthMs", 0L).coerceAtLeast(0L)
        items.add(
            CommunityItem(
                kind = kind,
                name = name,
                title = title,
                creator = creator,
                description = description,
                tags = tags,
                added = added,
                file = file,
                bytes = bytes,
                sha256 = sha256,
                thumbnail = rawThumb.ifEmpty { null },
                width = width,
                height = height,
                lengthMs = lengthMs
            )
        )
    }
    return CommunityIndex(items)
}

internal class CommunityClient(
    private val cacheDir: File,
    private val http: () -> TrustedHttp
) {
    fun index(): CommunityIndex {
        val text = http().get(CommunitySource.asset("", "index.json")) { conn ->
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val length = conn.contentLengthLong
            if (length > INDEX_MAX_BYTES) {
                throw IOException("Index exceeds size limit")
            }
            conn.inputStream.use { readBounded(it, INDEX_MAX_BYTES).toString(Charsets.UTF_8) }
        }
        return parseCommunityIndex(text, BuildConfig.VERSION_CODE)
    }

    fun thumbnail(item: CommunityItem): ByteArray? {
        val rel = item.thumbnail ?: return null
        if (!safeCommunityPath(rel, "${item.kind.folder}/${item.name}/")) return null
        val dir = File(cacheDir, "community/thumbs")
        val cached = File(dir, sha256String(rel + item.sha256))
        if (cached.isFile && cached.length() in 1L..THUMB_MAX_BYTES) {
            try {
                return cached.readBytes()
            } catch (_: IOException) {
            }
        }
        return try {
            val bytes = http().get(CommunitySource.asset("", rel)) { conn ->
                if (conn.responseCode !in 200..299) return@get null
                val length = conn.contentLengthLong
                if (length > THUMB_MAX_BYTES) return@get null
                conn.inputStream.use { readBounded(it, THUMB_MAX_BYTES) }
            } ?: return null
            if (bytes.isEmpty()) return null
            dir.mkdirs()
            val part = File(dir, "${cached.name}.part")
            try {
                part.writeBytes(bytes)
                if (!part.renameTo(cached)) {
                    part.copyTo(cached, overwrite = true)
                    part.delete()
                }
            } catch (_: IOException) {
                part.delete()
            }
            bytes
        } catch (_: IOException) {
            null
        }
    }

    fun download(item: CommunityItem): File {
        val folder = "${item.kind.folder}/${item.name}/"
        if (!NAME_REGEX.matches(item.name) ||
            !safeCommunityPath(item.file, folder) ||
            item.bytes <= 0L ||
            item.bytes > item.kind.maxBytes ||
            !isSha256(item.sha256)
        ) {
            throw CommunityDamagedException("Invalid community item metadata")
        }
        val dir = File(cacheDir, "community/files")
        dir.mkdirs()
        val target = File(dir, "${item.name}.${item.kind.ext}")
        if (target.isFile) {
            if (target.length() == item.bytes && sha256Of(target) == item.sha256) {
                return target
            }
            target.delete()
        }
        val part = File(dir, "${item.name}.${item.kind.ext}.part")
        part.delete()
        try {
            val limit = minOf(item.bytes, item.kind.maxBytes)
            http().get(CommunitySource.asset("", item.file)) { conn ->
                if (conn.responseCode !in 200..299) {
                    throw IOException("HTTP ${conn.responseCode}")
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > 0L && contentLength != item.bytes) {
                    throw CommunityDamagedException("Content length mismatch")
                }
                if (contentLength > limit) {
                    throw CommunityDamagedException("Download exceeds byte limit")
                }
                val buf = ByteArray(8192)
                var total = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > limit) {
                                throw CommunityDamagedException("Download exceeds byte limit")
                            }
                            output.write(buf, 0, read)
                        }
                    }
                }
            }
            if (part.length() != item.bytes || sha256Of(part) != item.sha256) {
                throw CommunityDamagedException("Downloaded file verification failed")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } catch (t: Throwable) {
            part.delete()
            target.delete()
            throw t
        }
    }
}

fun addCommunityPreset(file: File, store: PresetStore): String {
    if (!file.isFile) throw IllegalArgumentException("Preset file does not exist")
    val size = file.length()
    if (size <= 0L || size > CommunityKind.Preset.maxBytes) {
        throw IllegalArgumentException("Preset file exceeds size limit")
    }
    val scene = Scene.parse(file.readText())
    if (scene.node.name != "scene" || scene.layers.isEmpty()) {
        throw IllegalArgumentException("Preset does not contain any layers")
    }
    return store.save(scene)
}

private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = input.read(buf)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw IOException("Stream exceeds $maxBytes bytes")
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}

private fun sha256String(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
