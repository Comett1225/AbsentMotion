package app.absentmotion.effects

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import app.absentmotion.editor.imageLayer
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.platform.Context
import app.absentmotion.platform.Uri
import app.absentmotion.library.sha256String
import app.absentmotion.ui.home.isSha256
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal const val AddedEffectMaxBytes = 512 * 1024
internal const val AddedEffectStoredBytes = AddedEffectMaxBytes + 16 * 1024
internal const val AddedEffectCategory = "added"
internal const val AddedEffectPrefix = "user."
private const val MaxPackEffects = 20
private val effectKey = Regex("[a-z0-9-]{1,40}")
internal val addedEffectKey = Regex("[a-z0-9](?:[a-z0-9]|-(?!-)){0,31}\\.[a-z0-9-]{1,40}")
internal const val AddedEffectBundleLimit = 32 * 1024 * 1024

internal fun creatorSlug(author: String): String {
    if (author.isBlank()) return "unknown"
    val lower = author.lowercase(Locale.ROOT)
    return lower.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
        .ifEmpty { "u" + sha256String(lower).take(8) }
}

internal fun addedEffectVersion(value: Any?): Long? =
    (value as? Number)?.toString()?.toBigDecimalOrNull()?.let {
        runCatching { it.longValueExact() }.getOrNull()?.takeIf { version -> version >= 1 }
    }

internal enum class AddedEffectAction { Add, Update, AddAgain, Replace }

internal fun addedEffectAction(version: Long, installed: Long?, sameAuthor: Boolean = true) = when {
    installed == null -> AddedEffectAction.Add
    !sameAuthor -> AddedEffectAction.Replace
    version > installed -> AddedEffectAction.Update
    version == installed -> AddedEffectAction.AddAgain
    else -> AddedEffectAction.Replace
}

internal data class AddedEffectSource(val pack: String, val sha256: String) {
    fun json() = JSONObject().put("pack", pack).put("sha256", sha256)

    companion object {
        fun read(json: JSONObject?): AddedEffectSource? {
            val pack = json?.opt("pack") as? String ?: return null
            val sha256 = json.opt("sha256") as? String ?: return null
            return if (pack.matches(Regex("[a-z0-9-]{1,48}")) && isSha256(sha256)) AddedEffectSource(pack, sha256) else null
        }
    }
}

internal data class AddedEffect(val definition: EffectDefinition, val json: JSONObject) {
    val author = (json.opt("author") as? String).orEmpty()
    val licence = (json.opt("licence") as? String).orEmpty()

    fun from(source: AddedEffectSource) = copy(
        definition = definition.copy(addedPack = source.pack),
        json = JSONObject(json.toString()).put("source", source.json()),
    )
}

internal data class AddedEffectPreview(val effect: AddedEffect, val works: Boolean, val picture: ByteArray?)

internal fun isAddedEffectFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("json", "abmfx")

private fun InputStream.effectBytes(limit: Int = AddedEffectMaxBytes): ByteArray = ByteArrayOutputStream().use { out ->
    val buffer = ByteArray(8192)
    while (out.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    out.toByteArray()
}

internal fun EffectCatalog.sameAddedAuthor(effect: EffectDefinition): Boolean =
    find(effect.id)?.let { it.addedAuthor.trim().equals(effect.addedAuthor.trim(), ignoreCase = true) } ?: true

private fun EffectParam.safe(): Boolean {
    if (!min.isFinite() || !max.isFinite() || !step.isFinite() || step <= 0f || min > max) return false
    if (kind !in setOf("float", "angle", "point")) return true
    return default.split(',').all { it.trim().toFloatOrNull()?.isFinite() == true }
}

private fun parseAddedEffect(bytes: ByteArray, keepSource: Boolean = false): AddedEffect {
    require(bytes.size in 1..(if (keepSource) AddedEffectStoredBytes else AddedEffectMaxBytes))
    val source = JSONObject(bytes.toString(Charsets.UTF_8))
    val key = source.opt("id") as? String
    val name = source.opt("name") as? String
    val category = (source.opt("category") as? String)?.takeIf { it in EffectCatalog.Categories }
    val params = source.opt("params") as? JSONArray
    val shader = source.opt("sksl") as? String
    require(key != null && effectKey.matches(key) && !name.isNullOrBlank())
    require(params != null && params.length() <= 32 && !shader.isNullOrBlank() && shader.toByteArray().size <= 64 * 1024)
    val json = JSONObject()
        .put("id", key).put("name", name).put("category", category)
        .put("params", params).put("sksl", shader)
    for (field in listOf("description", "author", "licence")) {
        (source.opt(field) as? String)?.let { json.put(field, it) }
    }
    val version = if (source.has("version")) requireNotNull(addedEffectVersion(source.opt("version"))) else 1L
    json.put("version", version)
    val author = (json.opt("author") as? String).orEmpty()
    val slug = creatorSlug(author)
    val deviceKey = "$slug.$key"
    val parsed = EffectDefinition.parse(json, category)
    require(parsed.params.all { it.safe() })
    val effect = AddedEffect(parsed.copy(
        id = AddedEffectPrefix + deviceKey, key = deviceKey, addedAuthor = author, addedCreator = slug, addedVersion = version,
    ), json)
    return if (keepSource) AddedEffectSource.read(source.optJSONObject("source"))?.let(effect::from) ?: effect else effect
}

internal fun readAddedEffects(name: String, input: InputStream, cacheDir: File): List<AddedEffect> =
    readAddedEffects(name, input.effectBytes(), cacheDir)

private fun readAddedEffects(name: String, bytes: ByteArray, cacheDir: File): List<AddedEffect> =
    readAddedEffectEntries(name, bytes, cacheDir).map { (_, effect) -> effect.getOrThrow() }

internal fun readAddedEffectEntries(name: String, input: InputStream, cacheDir: File) =
    readAddedEffectEntries(name, input.effectBytes(), cacheDir)

private fun readAddedEffectEntries(name: String, bytes: ByteArray, cacheDir: File): List<Pair<String, Result<AddedEffect>>> {
    require(isAddedEffectFile(name) && bytes.size in 1..AddedEffectMaxBytes)
    if (name.endsWith(".json", ignoreCase = true)) return listOf(effectEntry(name, bytes))
    val temp = File.createTempFile("effect-pack-", ".abmfx", cacheDir)
    try {
        temp.writeBytes(bytes)
        return ZipFile(temp).use { zip ->
            val entries = zip.entries().asSequence().filter {
                !it.isDirectory && '/' !in it.name && '\\' !in it.name && it.name.endsWith(".json", ignoreCase = true)
            }.take(MaxPackEffects + 1).toList()
            require(entries.size in 1..MaxPackEffects)
            entries.map { entry -> zip.getInputStream(entry).use { effectEntry(entry.name, it.effectBytes()) } }.also { read ->
                val keys = read.mapNotNull { it.second.getOrNull()?.definition?.key }
                require(keys.size == keys.toSet().size)
            }
        }
    } finally {
        temp.delete()
    }
}

private fun effectEntry(name: String, bytes: ByteArray, key: String? = null): Pair<String, Result<AddedEffect>> {
    val parsed = runCatching {
        parseAddedEffect(bytes, keepSource = key != null).also { if (key != null) require(it.definition.key == key) }
    }
    val label = parsed.getOrNull()?.definition?.name ?: (
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).opt("name") as? String }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: name
        ).take(80)
    return label to parsed
}

internal fun readBundledAddedEffect(entry: String, bytes: ByteArray): Pair<String, Result<AddedEffect>> {
    val name = entry.substringAfterLast('/').removeSuffix(".json")
    if (entry != "effects/$name.json" || !addedEffectKey.matches(name)) {
        return name.take(80) to Result.failure(IllegalArgumentException())
    }
    return effectEntry(name, bytes, name)
}

internal fun readAddedEffectUri(context: Context, uri: Uri, fromPicker: Boolean = false): Result<List<AddedEffect>>? {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull().orEmpty()
    val path = uri.lastPathSegment.orEmpty()
    val type = runCatching { resolver.getType(uri) }.getOrNull()
    val filename = when {
        isAddedEffectFile(name) -> name
        isAddedEffectFile(path) -> path
        type == "application/x-abmfx" -> "effect.abmfx"
        type == "application/json" -> "effect.json"
        fromPicker -> return Result.failure(IllegalArgumentException())
        else -> return null
    }
    val bytes = runCatching { requireNotNull(resolver.openInputStream(uri)).use { it.effectBytes() } }
    if (!fromPicker && filename.endsWith(".json", ignoreCase = true)) {
        val text = bytes.getOrNull()?.toString(Charsets.UTF_8) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull()
        val looksLikeEffect = if (json != null) json.has("sksl") && (json.has("id") || json.has("params"))
            else text.trimStart().startsWith("{") && "\"sksl\"" in text && "\"id\"" in text
        if (!looksLikeEffect) return null
    }
    return bytes.mapCatching { readAddedEffects(filename, it, context.cacheDir) }
}

internal class AddedEffectStore(private val filesDir: File, private val preferences: SharedPreferences? = null) {
    constructor(context: Context) : this(context.filesDir, context.getSharedPreferences("effects", 0))

    val shownEffects: Set<String> get() = preferences?.getStringSet("shown_added_effects", emptySet()).orEmpty().toSet()
    val shownCreators: Set<String> get() = preferences?.getStringSet("shown_added_creators", emptySet()).orEmpty().toSet()

    fun savePlacement(effects: Set<String>, creators: Set<String>) {
        preferences?.edit()?.putStringSet("shown_added_effects", effects)?.putStringSet("shown_added_creators", creators)?.apply()
    }

    private val directory = File(filesDir, "effects-added")
    private fun definition(key: String) = File(directory, "$key.json")
    fun picture(key: String) = File(directory, "$key.webp")

    fun definitions(): List<EffectDefinition> = synchronized(lock) {
        directory.listFiles().orEmpty().filter {
            it.extension == "json" && addedEffectKey.matches(it.nameWithoutExtension)
        }.sortedBy { it.name }.mapNotNull { file ->
            runCatching {
                file.inputStream().use { parseAddedEffect(it.effectBytes(), keepSource = true).definition }
                    .takeIf { it.key == file.nameWithoutExtension }
                    ?.copy(addedModified = Files.getLastModifiedTime(file.toPath()).to(TimeUnit.NANOSECONDS))
            }.getOrNull()
        }
    }

    fun bundleBytes(scene: Scene): Long = synchronized(lock) {
        scene.addedEffectUses().sumOf { definition(it.id.removePrefix(AddedEffectPrefix)).length() }
    }

    fun bundled(scene: Scene): Map<String, ByteArray> = synchronized(lock) {
        scene.addedEffectUses().mapNotNull { use ->
            val key = use.id.removePrefix(AddedEffectPrefix)
            val file = definition(key)
            if (!file.isFile) return@mapNotNull null
            val bytes = file.inputStream().use { it.effectBytes(AddedEffectStoredBytes) }
            val (name, parsed) = readBundledAddedEffect("effects/$key.json", bytes)
            check(parsed.isSuccess) { name }
            "effects/$key.json" to bytes
        }.toMap()
    }

    fun put(effect: AddedEffect, picture: ByteArray?) = putAll(listOf(AddedEffectPreview(effect, true, picture)))

    fun putAll(effects: List<AddedEffectPreview>) = synchronized(lock) {
        if (effects.isEmpty()) return@synchronized
        check(directory.isDirectory || directory.mkdirs())
        val staging = Files.createTempDirectory(filesDir.toPath(), "effects-added-").toFile()
        val originals = File(staging, "originals")
        val changes = linkedMapOf<File, File?>()
        val applied = mutableListOf<File>()
        try {
            check(originals.mkdir())
            for (preview in effects) {
                val effect = preview.effect
                val key = effect.definition.key
                require(addedEffectKey.matches(key))
                val json = effect.json.toString().toByteArray()
                require(json.size <= AddedEffectStoredBytes)
                changes[definition(key)] = File(staging, "$key.json").apply { writeBytes(json) }
                changes[picture(key)] = preview.picture?.let { bytes -> File(staging, "$key.webp").apply { writeBytes(bytes) } }
            }
            for (file in changes.keys) if (file.exists()) {
                check(file.isFile)
                Files.copy(file.toPath(), File(originals, file.name).toPath(), COPY_ATTRIBUTES)
            }
            for ((file, staged) in changes) {
                if (staged == null) Files.deleteIfExists(file.toPath()) else move(staged, file)
                applied += file
            }
        } catch (failure: Exception) {
            for (file in applied.asReversed()) {
                val original = File(originals, file.name)
                runCatching {
                    if (original.isFile) move(original, file) else Files.deleteIfExists(file.toPath())
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staging.deleteRecursively()
        }
    }

    fun remove(key: String) = synchronized(lock) {
        require(addedEffectKey.matches(key))
        Files.deleteIfExists(definition(key).toPath())
        picture(key).delete()
        Unit
    }

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private companion object {
        val lock = Any()
    }
}

internal fun EffectCatalog.changeAddedEffects(
    store: AddedEffectStore,
    add: List<AddedEffectPreview>,
    remove: EffectDefinition?,
    load: (String) -> String,
    newerOnly: Boolean = false,
): Pair<EffectCatalog, Boolean> {
    val statuses = definitions.associate { it.id to it.status }.toMutableMap()
    val working = add.filter { preview ->
        val effect = preview.effect.definition
        preview.works && (!newerOnly || (effect.addedVersion > (find(effect.id)?.addedVersion ?: 0L) && sameAddedAuthor(effect)))
    }.associateBy { it.effect.definition.id }.values.toList()
    val loaded = mutableListOf<EffectDefinition>()
    val saved = runCatching {
        for (preview in working) {
            val effect = preview.effect.definition
            loaded += effect
            check(load(effect.runtimeXml()).substringBefore('\n') == "ok")
        }
        store.putAll(working)
        loaded.forEach { statuses[it.id] = "ok" }
    }
    if (saved.isFailure) for (effect in loaded.asReversed()) {
        load(find(effect.id)?.runtimeXml() ?: removeEffectXml(effect.id))
    }
    val removed = remove == null || runCatching {
        store.remove(remove.key)
        load(removeEffectXml(remove.id))
    }.isSuccess
    val refreshed = withAdded(store)
    val placed = if (remove != null && refreshed.find(remove.id) == null) refreshed.clearAddedPlacement(store, remove) else refreshed
    return placed.copy(definitions = placed.definitions.map { it.copy(status = statuses[it.id] ?: it.status) }) to (saved.isSuccess && removed)
}

private fun removeEffectXml(id: String) = XmlNode("effect", mapOf("id" to id, "remove" to "true")).toXml()

internal fun addedEffectSample(context: Context): Bitmap? =
    context.assets.open("presets/placeholders/city.webp").use { BitmapFactory.decodeStream(it) }

internal fun previewAddedEffect(native: NativeEngine, effect: AddedEffect, sample: Bitmap?): AddedEffectPreview {
    val definition = effect.definition.copy(id = "${AddedEffectPrefix}-preview.shader")
    val works = runCatching { native.loadEffect(definition.runtimeXml()).substringBefore('\n') == "ok" }.getOrDefault(false)
    if (!works) return AddedEffectPreview(effect, false, null)
    val picture = try {
        sample?.let { renderAddedEffectPicture(native, definition, it) }
    } finally {
        native.loadEffect(removeEffectXml(definition.id))
    }
    return AddedEffectPreview(effect, true, picture)
}

private fun renderAddedEffectPicture(native: NativeEngine, definition: EffectDefinition, sample: Bitmap): ByteArray? =
    runCatching {
        val uri = "added-effect-sample"
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "width" to "${sample.width}", "height" to "${sample.height}")))
        val size = 256
        val scene = Scene.create("", size, size, 30, 0)
        val layer = checkNotNull(scene.imageLayer(media, 0, 1)).addEffect(definition.instance())
        check(native.setPicture(uri, sample))
        native.setScene(scene.add(layer, listOf(media)).toXml())
        val rgba = checkNotNull(native.still(100.0, size, size))
        val pixels = IntArray(size * size) { i ->
            val p = i * 4
            ((rgba[p + 3].toInt() and 255) shl 24) or ((rgba[p].toInt() and 255) shl 16) or
                ((rgba[p + 1].toInt() and 255) shl 8) or (rgba[p + 2].toInt() and 255)
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        try {
            ByteArrayOutputStream().use { out ->
                @Suppress("DEPRECATION")
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

internal data class AddedEffectUse(val id: String, val name: String, val version: Long, val pack: String?)

internal fun Scene.addedEffectUses(): List<AddedEffectUse> {
    val effects = linkedMapOf<String, AddedEffectUse>()
    fun visit(node: XmlNode) {
        val id = node["id"]
        if (node.name == "effect" && id != null && id.startsWith(AddedEffectPrefix)) {
            val key = id.removePrefix(AddedEffectPrefix)
            if (addedEffectKey.matches(key)) {
                val version = node["version"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1L
                if (version > (effects[id]?.version ?: 0L)) {
                    effects[id] = AddedEffectUse(id, node["name"]?.takeIf(String::isNotBlank) ?: key, version, node["pack"])
                }
            }
        }
        node.children.forEach(::visit)
    }
    visit(node)
    return effects.values.toList()
}

internal fun Scene.missingAddedEffectUses(catalog: EffectCatalog): List<AddedEffectUse> =
    addedEffectUses().filter { (catalog.find(it.id)?.addedVersion ?: 0L) < it.version }

internal fun Scene.missingAddedEffects(catalog: EffectCatalog): List<String> =
    missingAddedEffectUses(catalog).map { it.name }
