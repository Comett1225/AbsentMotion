package app.absentmotion.effects

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import app.absentmotion.R
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.matchesSearch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

val EffectCategories: List<Pair<String, String>> = EffectCatalog.Categories.toList() + (if (app.absentmotion.platform.PlatformFeatures.ai) listOf("ai" to "AI") else emptyList())

data class EffectCatalog(
    val definitions: List<EffectDefinition> = emptyList(),
    val message: String? = null,
    private val open: ((String) -> InputStream)? = null,
    val newest: Set<String> = emptySet(),
    val categoryNames: Map<String, String> = emptyMap(),
) {
    fun find(id: String): EffectDefinition? = definitions.firstOrNull { it.id == id }

    fun isNew(effect: EffectDefinition): Boolean = effect.since in newest

    fun listed(category: String? = null): List<EffectDefinition> =
        definitions.filter { category == null || it.category == category || (category == "new" && isNew(it)) }

    fun search(query: String): List<EffectDefinition> {
        val byName = definitions.filter { matchesSearch(query, it.name) }.sortedWith(compareBy({ searchRank(query, it.name) }, { it.name.lowercase() }))
        return byName + definitions.filter { it !in byName && matchesSearch(query, it.name, it.description) }.sortedBy { it.name.lowercase() }
    }

    val categories: List<Pair<String, String>> get() =
        ((if (definitions.any(::isNew)) listOf("new" to "New") else emptyList()) + EffectCategories).map { (k, v) -> k to (categoryNames[k] ?: v) }

    fun bytes(path: String?): ByteArray? = path?.let { p -> runCatching { open?.invoke(p)?.use { it.readBytes() } }.getOrNull() }

    fun drawable(path: String?): Drawable? = bytes(path)?.let { bytes ->
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
            else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { android.graphics.drawable.BitmapDrawable(null, it) }
        }.getOrNull()
    }

    fun loadInto(load: (String) -> String): EffectCatalog {
        val status = definitions.associate { it.id to load(it.runtimeXml()).substringBefore('\n') }
        return copy(definitions = definitions.map { it.copy(status = status.getValue(it.id)) })
    }

    fun withRuntimeStatusFrom(previous: EffectCatalog): EffectCatalog {
        if (message != null && previous.definitions.isNotEmpty()) return previous
        val status = previous.definitions.associate { it.id to it.status }
        return copy(definitions = definitions.map { it.copy(status = status[it.id] ?: it.status) })
    }

    companion object {
        val Categories = linkedMapOf("color" to "Color", "blur" to "Blur", "distort" to "Distort", "stylize" to "Stylize",
            "noise" to "Noise", "repeat" to "Repeat", "key" to "Key", "move" to "Move", "beat" to "Beat", "3d" to "3D", "text" to "Text", "other" to "Other")

        fun read(context: Context): EffectCatalog = parse(currentLanguage(context).tag) { context.assets.open("effects/$it") }
            .let { if (it.message != null) it.copy(message = context.getString(R.string.effects_not_loaded)) else it }

        fun parse(language: String = "en", open: (String) -> InputStream): EffectCatalog = runCatching {
            fun text(path: String) = open(path).bufferedReader().use { it.readText() }
            val keys = JSONArray(text("index.json"))
            val definitions = List(keys.length()) { keys.getString(it) }.mapNotNull { key ->
                runCatching { EffectDefinition.parse(JSONObject(text("$key.json"))) }.getOrNull()
            }
            val releases = runCatching { JSONArray(text("releases.json")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
            val versions = releases + definitions.mapNotNull { it.since }
            val major = versions.mapNotNull { it.substringBefore('.').toIntOrNull() }.maxOrNull()
            val catalog = EffectCatalog(definitions, open = open, newest = versions.filter { it.substringBefore('.').toIntOrNull() == major }.toSet())
            if (language == "en") catalog else runCatching { catalog.translated(JSONObject(text("i18n/$language.json"))) }.getOrDefault(catalog)
        }.getOrElse { EffectCatalog(message = "The effects could not be loaded") }
    }
}

fun EffectCatalog.translated(json: JSONObject): EffectCatalog {
    val effects = json.optJSONObject("effects") ?: JSONObject()
    val names = json.optJSONObject("categories")
    return copy(
        categoryNames = names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
        definitions = definitions.map { d -> effects.optJSONObject(d.key)?.let(d::translated) ?: d },
    )
}

private fun EffectDefinition.translated(json: JSONObject): EffectDefinition {
    val params = json.optJSONObject("params") ?: JSONObject()
    val presetNames = json.optJSONObject("presets") ?: JSONObject()
    return copy(
        name = json.optString("name").ifBlank { name },
        description = json.optString("description").ifBlank { description },
        params = this.params.map { p ->
            val t = params.optJSONObject(p.id) ?: return@map p
            val options = t.optJSONArray("options")?.let { a -> List(a.length()) { a.optString(it) } }
            p.copy(
                label = t.optString("label").ifBlank { p.label },
                options = if (options != null && options.size == p.options.size && options.none { it.isBlank() }) options else p.options,
            )
        },
        presets = presets.map { it.copy(name = presetNames.optString(it.slug).ifBlank { it.name }) },
    )
}

internal fun searchRank(query: String, name: String): Int {
    val q = query.trim().lowercase()
    val n = name.trim().lowercase()
    return when {
        q.isEmpty() -> 3
        n == q -> 0
        n.startsWith(q) -> 1
        n.split(' ', '-', '/', '(').any { it.startsWith(q) } -> 2
        else -> 3
    }
}
