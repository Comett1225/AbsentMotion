package app.absentmotion.library

import app.absentmotion.BuildConfig
import app.absentmotion.effects.AddedEffectMaxBytes
import app.absentmotion.effects.AddedEffectPrefix
import app.absentmotion.effects.AddedEffectUse
import app.absentmotion.effects.addedEffectKey
import app.absentmotion.effects.addedEffectVersion
import app.absentmotion.effects.readAddedEffectEntries
import app.absentmotion.effects.isAddedEffectFile
import app.absentmotion.engine.EngineThread
import app.absentmotion.platform.Context
import app.absentmotion.export.ExportSettings
import app.absentmotion.model.Scene
import app.absentmotion.model.mapMediaUris
import app.absentmotion.platform.encodeRgba
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
import java.io.InterruptedIOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import app.absentmotion.ui.editor.matchesSearch

private val COMMUNITY_OWNER = HOME_PAGE.removePrefix("https://github.com/").substringBefore('/')
val COMMUNITY_RAW = "https://raw.githubusercontent.com/$COMMUNITY_OWNER/AbsentMotion-Community/main/"

private const val INDEX_MAX_BYTES = 4L shl 20
private const val THUMB_MAX_BYTES = 4L shl 20
private const val PRESET_PROBES = 5
private const val PRESET_PROBE_SIDE = 64
private const val PRESET_PICTURE_SIDE = 512
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

internal object CommunityFeed {
    @Volatile var source: UpdateSource = CommunitySource
}

enum class CommunityKind(
    val id: String,
    val folder: String,
    val maxBytes: Long
) {
    Bundle("bundle", "bundles", 20L shl 20),
    Preset("preset", "presets", 1L shl 20),
    Effect("effect", "effects", AddedEffectMaxBytes.toLong())
}

internal fun communityLimit(kind: CommunityKind, file: String): Long =
    if (file.endsWith(".absproj")) CommunityKind.Bundle.maxBytes else kind.maxBytes

data class CommunityEffectContent(val id: String, val name: String, val version: Long)

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
    val lengthMs: Long = 0L,
    val credit: String? = null,
    val contents: List<CommunityEffectContent> = emptyList(),
    val minVersion: Int = 0,
)

data class CommunityIndex(
    val items: List<CommunityItem>,
    val tooNew: Boolean = false,
    val unavailableEffects: List<CommunityItem> = emptyList(),
)

class CommunityDamagedException(message: String) : IOException(message)

internal class CommunityItemUnavailable : IOException()

internal class CommunityWriteException(cause: Exception) : IOException(cause)

internal fun CommunityIndex.effectPacks(missing: List<AddedEffectUse>): Map<CommunityItem, List<AddedEffectUse>> {
    val remaining = missing.toMutableList()
    val candidates = items.filter { it.kind == CommunityKind.Effect }.associateWith { pack ->
        missing.filter { use -> pack.contents.any { it.id == use.id.removePrefix(AddedEffectPrefix) && it.version >= use.version } }
    }.toMutableMap()
    val chosen = linkedMapOf<CommunityItem, List<AddedEffectUse>>()
    while (remaining.isNotEmpty()) {
        val matches = candidates.mapValues { (_, uses) -> uses.filter { it in remaining } }
        val best = matches.maxWithOrNull(compareBy<Map.Entry<CommunityItem, List<AddedEffectUse>>>(
            { it.value.size }, { (item, uses) -> uses.count { it.pack == item.name } },
        )) ?: break
        if (best.value.isEmpty()) break
        chosen[best.key] = candidates.getValue(best.key)
        remaining.removeAll(best.value.toSet())
        candidates.remove(best.key)
    }
    return chosen
}

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
    val unavailableEffects = ArrayList<CommunityItem>()
    val seen = HashSet<String>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val kind = when (obj.optString("kind", "")) {
            CommunityKind.Bundle.id -> CommunityKind.Bundle
            CommunityKind.Preset.id -> CommunityKind.Preset
            CommunityKind.Effect.id -> CommunityKind.Effect
            else -> continue
        }
        val minVersion = obj.optInt("minVersion", 0)
        if (minVersion > versionCode && kind != CommunityKind.Effect) continue
        val name = obj.optString("name", "")
        if (!NAME_REGEX.matches(name)) continue
        val folder = "${kind.folder}/$name/"
        val file = obj.optString("file", "")
        if (!safeCommunityPath(file, folder) || (kind == CommunityKind.Effect && !isAddedEffectFile(file))) continue
        val rawThumb = if (kind != CommunityKind.Bundle || obj.isNull("thumbnail")) "" else obj.optString("thumbnail", "")
        if (rawThumb.isNotEmpty() && !safeCommunityPath(rawThumb, folder)) continue
        val bytes = obj.optLong("bytes", -1L)
        if (bytes <= 0L || bytes > communityLimit(kind, file)) continue
        val sha256 = obj.optString("sha256", "")
        if (!isSha256(sha256) || !seen.add("${kind.id}/$name")) continue
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
        val entries = if (kind == CommunityKind.Effect) obj.optJSONArray("contents") else null
        val contents = if (entries == null) emptyList() else (0 until entries.length()).mapNotNull { j ->
            val entry = entries.optJSONObject(j) ?: return@mapNotNull null
            val id = entry.opt("id") as? String ?: return@mapNotNull null
            val label = (entry.opt("name") as? String)?.trim()?.take(80) ?: return@mapNotNull null
            val version = addedEffectVersion(entry.opt("version")) ?: return@mapNotNull null
            if (label.isEmpty() || !addedEffectKey.matches(id)) null else CommunityEffectContent(id, label, version)
        }
        val target = if (minVersion > versionCode) unavailableEffects else items
        target.add(
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
                lengthMs = lengthMs,
                credit = (obj.opt("credit") as? String)?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
                contents = contents,
                minVersion = minVersion,
            )
        )
    }
    return CommunityIndex(items, unavailableEffects = unavailableEffects)
}

internal class CommunityClient(
    private val cacheDir: File,
    private val http: () -> TrustedHttp
) {
    fun index(): CommunityIndex {
        val text = http().get(CommunityFeed.source.asset("", "index.json")) { conn ->
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
        val bytes = http().get(CommunityFeed.source.asset("", rel)) { conn ->
            if (conn.responseCode !in 200..299) return@get null
            val length = conn.contentLengthLong
            if (length > THUMB_MAX_BYTES) return@get null
            conn.inputStream.use { readBounded(it, THUMB_MAX_BYTES) }
        } ?: return null
        if (bytes.isEmpty()) return null
        keepCached(cached, bytes)
        return bytes
    }

    suspend fun presetPicture(item: CommunityItem, engine: EngineThread): ByteArray? {
        if (item.kind != CommunityKind.Preset || !isSha256(item.sha256)) return null
        val cached = File(cacheDir, "community/thumbs/${item.sha256}.png")
        if (cached.isFile && cached.length() in 1L..THUMB_MAX_BYTES) {
            try {
                return cached.readBytes()
            } catch (_: IOException) {
            }
        }
        val file = download(item)
        val unpacked = File(cacheDir, "community/preview/${UUID.randomUUID()}")
        val picture = (try {
            val scene = if (file.extension == "absproj") bundleScene(file, unpacked) else communityScene(file, MediaAccess.forPhone(cacheDir))
            scene?.let { renderPresetPicture(engine, it) }
        } finally {
            unpacked.deleteRecursively()
        }) ?: return null
        keepCached(cached, picture)
        return picture
    }

    suspend fun effectPicture(item: CommunityItem, engine: EngineThread, context: Context): ByteArray? {
        if (item.kind != CommunityKind.Effect || !isSha256(item.sha256)) return null
        val cached = File(cacheDir, "community/thumbs/${item.sha256}.webp")
        if (cached.isFile && cached.length() in 1L..THUMB_MAX_BYTES) {
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val file = download(item)
        val effects = file.inputStream().use { readAddedEffectEntries(file.name, it, cacheDir) }.mapNotNull { it.second.getOrNull() }
        val picture = engine.previewAddedEffects(context, effects).firstNotNullOfOrNull { it.picture } ?: return null
        keepCached(cached, picture)
        return picture
    }

    fun download(item: CommunityItem): File {
        if (item.minVersion > BuildConfig.VERSION_CODE) throw CommunityItemUnavailable()
        val folder = "${item.kind.folder}/${item.name}/"
        if (!NAME_REGEX.matches(item.name) ||
            !safeCommunityPath(item.file, folder) ||
            (item.kind == CommunityKind.Effect && !isAddedEffectFile(item.file)) ||
            item.bytes <= 0L ||
            item.bytes > communityLimit(item.kind, item.file) ||
            !isSha256(item.sha256)
        ) {
            throw CommunityDamagedException("Invalid community item metadata")
        }
        val dir = File(cacheDir, "community/files")
        cacheWrite { if (!dir.isDirectory && !dir.mkdirs()) throw IOException() }
        val target = if (item.kind == CommunityKind.Effect) File(dir, "effect-${item.sha256}.${item.file.substringAfterLast('.')}")
            else File(dir, "${item.name}.${if (item.file.endsWith(".absproj")) "absproj" else "xml"}")
        return synchronized(downloads.getOrPut(target.absolutePath) { Any() }) { fetch(item, target) }
    }

    private fun fetch(item: CommunityItem, target: File): File {
        if (target.isFile) {
            if (target.length() == item.bytes && sha256Of(target) == item.sha256) {
                return target
            }
            target.delete()
        }
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        try {
            val limit = minOf(item.bytes, communityLimit(item.kind, item.file))
            http().get(CommunityFeed.source.asset("", item.file)) { conn ->
                if (conn.responseCode in listOf(404, 410)) throw CommunityItemUnavailable()
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
                    cacheWrite { part.outputStream() }.use { output ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > limit) {
                                throw CommunityDamagedException("Download exceeds byte limit")
                            }
                            cacheWrite { output.write(buf, 0, read) }
                        }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            if (part.length() != item.bytes || sha256Of(part) != item.sha256) {
                throw CommunityDamagedException("Downloaded file verification failed")
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            if (!part.renameTo(target)) {
                cacheWrite { part.copyTo(target, overwrite = true) }
                part.delete()
            }
            return target
        } catch (t: Throwable) {
            part.delete()
            target.delete()
            throw t
        }
    }

    private inline fun <T> cacheWrite(write: () -> T): T = try {
        write()
    } catch (e: IOException) {
        throw CommunityWriteException(e)
    } catch (e: SecurityException) {
        throw CommunityWriteException(e)
    }

    private companion object {
        val downloads = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

internal fun keepCached(cached: File, bytes: ByteArray) {
    cached.parentFile?.mkdirs()
    val part = File(cached.parentFile, "${cached.name}.part")
    try {
        part.writeBytes(bytes)
        if (!part.renameTo(cached)) {
            part.copyTo(cached, overwrite = true)
            part.delete()
        }
    } catch (_: IOException) {
        part.delete()
    }
}

private fun communityScene(file: File, access: MediaAccess): Scene =
    Scene(Scene.parse(file.readText().removePrefix("\uFEFF")).node.mapMediaUris(access::imported))

private fun bundleScene(file: File, root: File): Scene? {
    val store = ProjectStore(root)
    return Importer(store, access = MediaAccess.forPhone(root)).import { file.inputStream() }.singleOrNull()?.let { store.load(it.id) }
}

fun addCommunityPreset(file: File, store: PresetStore, access: MediaAccess): String {
    if (!file.isFile) throw IllegalArgumentException("Preset file does not exist")
    val size = file.length()
    if (size <= 0L || size > CommunityKind.Preset.maxBytes) {
        throw IllegalArgumentException("Preset file exceeds size limit")
    }
    val scene = communityScene(file, access)
    if (scene.node.name != "scene" || scene.layers.isEmpty()) {
        throw IllegalArgumentException("Preset does not contain any layers")
    }
    return store.save(scene)
}

internal suspend fun renderPresetPicture(engine: EngineThread, scene: Scene): ByteArray? {
    val xml = scene.toXml()
    val layers = scene.layers.takeIf { it.isNotEmpty() } ?: return null
    val start = layers.minOf { it.startTime }.coerceAtLeast(0)
    val span = (layers.maxOf { it.endTime } - start).coerceAtLeast(0)
    val times = List(PRESET_PROBES) { start + (span * (2L * it + 1) / (2 * PRESET_PROBES)).toInt() }
    val long = maxOf(scene.width, scene.height, 1)
    fun size(edge: Int) = (edge * scene.width / long).coerceAtLeast(1) to (edge * scene.height / long).coerceAtLeast(1)
    val (probeWidth, probeHeight) = size(PRESET_PROBE_SIDE)
    val (width, height) = size(PRESET_PICTURE_SIDE)
    val pixels = engine.exportSession(xml) {
        val sums = times.map { still(it, probeWidth, probeHeight)?.let(::opacitySum) ?: -1L }
        val best = sums.indices.maxBy { sums[it] }
        if (sums[best] > 0) still(times[best], width, height) else null
    } ?: return null
    return ByteArrayOutputStream().also { encodeRgba(pixels, width, height, ExportSettings(minOf(width, height), scene.fps), null, it) }.toByteArray()
}

private fun opacitySum(rgba: ByteArray): Long {
    var sum = 0L
    for (i in 3 until rgba.size step 4) sum += rgba[i].toInt() and 0xff
    return sum
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

internal fun sha256String(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}

enum class CommunitySort { Newest, Title }

fun communityMatches(
    items: List<CommunityItem>, query: String = "", sort: CommunitySort = CommunitySort.Newest,
    include: Set<String> = emptySet(), exclude: Set<String> = emptySet(), locale: Locale = Locale.getDefault()
): List<CommunityItem> {
    val matches = items.filter { item ->
        matchesSearch(query, item.title, item.creator, *item.tags.toTypedArray()) &&
            item.tags.containsAll(include) && item.tags.none { it in exclude }
    }
    return when (sort) {
        CommunitySort.Newest -> matches.sortedByDescending { item ->
            runCatching { Instant.parse(item.added) }.getOrNull()
                ?: runCatching { LocalDate.parse(item.added).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        CommunitySort.Title -> {
            val collator = Collator.getInstance(locale)
            matches.sortedWith { a, b -> collator.compare(a.title.ifEmpty { a.name }, b.title.ifEmpty { b.name }) }
        }
    }
}
