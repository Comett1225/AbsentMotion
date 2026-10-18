package app.absentmotion.effects

import app.absentmotion.compat.ForeignEffectIds
import app.absentmotion.platform.Bitmap
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

val EffectCategories: List<Pair<String, String>> = EffectCatalog.Categories.toList() + ("ai" to "AI")

data class EffectCatalog(
    val definitions: List<EffectDefinition> = emptyList(),
    val message: String? = null,
    private val open: ((String) -> InputStream)? = null,
) {
    fun find(id: String): EffectDefinition? =
        definitions.firstOrNull { it.id == id } ?: ForeignEffectIds.ids[id]?.let { own -> definitions.firstOrNull { it.id == own } }

    fun listed(category: String? = null): List<EffectDefinition> = definitions.filter { category == null || it.category == category }

    fun image(path: String?): Bitmap? = path?.let { p -> runCatching { open?.invoke(p)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() }

    fun loadInto(load: (String) -> String): EffectCatalog {
        val status = definitions.associate { it.id to load(it.runtimeXml()).substringBefore('\n') }
        ForeignEffectIds.ids.forEach { (foreign, own) -> definitions.firstOrNull { it.id == own }?.let { load(it.runtimeXml(foreign)) } }
        return copy(definitions = definitions.map { it.copy(status = status.getValue(it.id)) })
    }

    companion object {
        val Categories = linkedMapOf("color" to "Color", "blur" to "Blur", "distort" to "Distort", "stylize" to "Stylize",
            "noise" to "Noise", "repeat" to "Repeat", "key" to "Key")

        fun read(context: Context): EffectCatalog = parse { context.assets.open("effects/$it") }

        fun parse(open: (String) -> InputStream): EffectCatalog = runCatching {
            fun text(path: String) = open(path).bufferedReader().use { it.readText() }
            val keys = JSONArray(text("index.json"))
            val definitions = List(keys.length()) { keys.getString(it) }.mapNotNull { key ->
                runCatching { EffectDefinition.parse(JSONObject(text("$key.json"))) }.getOrNull()
            }
            EffectCatalog(definitions, open = open)
        }.getOrElse { EffectCatalog(message = "The effects could not be loaded") }
    }
}
