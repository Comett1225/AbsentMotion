package app.absentmotion.effects

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

val EffectCategories: List<Pair<String, String>> = EffectCatalog.Categories.toList() + ("ai" to "AI")

data class EffectCatalog(
    val definitions: List<EffectDefinition> = emptyList(),
    val message: String? = null,
    private val open: ((String) -> InputStream)? = null,
    val newest: Set<String> = emptySet(),
) {
    fun find(id: String): EffectDefinition? = definitions.firstOrNull { it.id == id }

    fun isNew(effect: EffectDefinition): Boolean = effect.since in newest

    fun listed(category: String? = null): List<EffectDefinition> =
        definitions.filter { category == null || it.category == category || (category == "new" && isNew(it)) }

    val categories: List<Pair<String, String>> get() = (if (definitions.any(::isNew)) listOf("new" to "New") else emptyList()) + EffectCategories

    fun drawable(path: String?): Drawable? = path?.let { p ->
        runCatching { open?.invoke(p)?.use { ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(it.readBytes()))) } }.getOrNull()
    }

    fun loadInto(load: (String) -> String): EffectCatalog {
        val status = definitions.associate { it.id to load(it.runtimeXml()).substringBefore('\n') }
        return copy(definitions = definitions.map { it.copy(status = status.getValue(it.id)) })
    }

    companion object {
        val Categories = linkedMapOf("color" to "Color", "blur" to "Blur", "distort" to "Distort", "stylize" to "Stylize",
            "noise" to "Noise", "repeat" to "Repeat", "key" to "Key", "move" to "Move", "3d" to "3D", "text" to "Text")

        fun read(context: Context): EffectCatalog = parse { context.assets.open("effects/$it") }

        fun parse(open: (String) -> InputStream): EffectCatalog = runCatching {
            fun text(path: String) = open(path).bufferedReader().use { it.readText() }
            val keys = JSONArray(text("index.json"))
            val definitions = List(keys.length()) { keys.getString(it) }.mapNotNull { key ->
                runCatching { EffectDefinition.parse(JSONObject(text("$key.json"))) }.getOrNull()
            }
            val releases = runCatching { JSONArray(text("releases.json")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
            EffectCatalog(definitions, open = open, newest = releases.takeLast(5).toSet())
        }.getOrElse { EffectCatalog(message = "The effects could not be loaded") }
    }
}
