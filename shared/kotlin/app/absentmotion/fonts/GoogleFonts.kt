package app.absentmotion.fonts

import app.absentmotion.ui.editor.matchesSearch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GoogleFamily(val family: String, val category: Int, val weights: Set<Int>, val italics: Set<Int>) {
    val regular: FontName get() = FontName("googlefonts", family, closest(weights, 400) ?: 400)
    fun nearest(weight: Int, italic: Boolean): FontName? {
        val slanted = if (italic) italics.isNotEmpty() else weights.isEmpty()
        return closest(if (slanted) italics else weights, weight)?.let { FontName("googlefonts", family, it, slanted) }
    }
}

private fun closest(options: Set<Int>, weight: Int) = options.minByOrNull { kotlin.math.abs(it - weight) * 2 + if (it < weight) 1 else 0 }

enum class FontSource { All, Imported, App, Device, Google }

class FontResults(val local: List<FontEntry>, val google: List<GoogleFamily>)

object GoogleFonts {
    const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
    const val URL = "https://fonts.google.com/metadata/fonts"
    private val categories = FontCatalog.categories.entries.associate { it.value to it.key }

    fun key(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    fun parse(text: String): List<GoogleFamily> = runCatching {
        val list = JSONObject(text.substring(text.indexOf('{'))).optJSONArray("familyMetadataList") ?: JSONArray()
        (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val family = item.optString("family").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val styles = item.optJSONArray("fonts")?.let { array -> (0 until array.length()).map { array.optString(it) } }
                ?: item.optJSONObject("fonts")?.keys()?.asSequence()?.toList().orEmpty()
            val parsed = styles.mapNotNull { style -> style.removeSuffix("i").toIntOrNull()?.takeIf { it in 100..900 }?.let { it to style.endsWith("i") } }
            GoogleFamily(family, categories[item.optString("category")] ?: 0,
                parsed.filter { !it.second }.map { it.first }.toSet(), parsed.filter { it.second }.map { it.first }.toSet())
        }
    }.getOrDefault(emptyList())

    private fun slim(families: List<GoogleFamily>): String = JSONObject().put("familyMetadataList", JSONArray(families.map { family ->
        JSONObject().put("family", family.family).put("category", categories.entries.firstOrNull { it.value == family.category }?.key.orEmpty())
            .put("fonts", JSONArray(family.weights.map { "$it" } + family.italics.map { "${it}i" }))
    })).toString()

    fun load(cache: File, now: Long, fetch: () -> String): List<GoogleFamily> {
        val cached = if (cache.isFile) parse(cache.readText()) else emptyList()
        if (cached.isNotEmpty() && now - cache.lastModified() in 0 until MAX_AGE) return cached
        val fresh = runCatching { parse(fetch()) }.getOrDefault(emptyList())
        if (fresh.isEmpty()) return cached
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(slim(fresh)); cache.setLastModified(now) }
        return fresh
    }

    fun arrange(imported: List<FontEntry>, app: List<FontEntry>, device: List<FontEntry>, google: List<GoogleFamily>, query: String, source: FontSource): FontResults {
        fun pick(entries: List<FontEntry>, wanted: FontSource) = if (source == FontSource.All || source == wanted) entries.filter { matchesSearch(query, it.name.label, it.name.family) } else emptyList()
        val appKeys = app.map { key(it.name.family) }.toSet()
        val deviceShown = device.filter { key(it.name.family) !in appKeys }
        val deviceKeys = deviceShown.map { key(it.name.family) }.toSet()
        val remote = if (query.isBlank() || source != FontSource.All && source != FontSource.Google) emptyList()
            else google.filter { key(it.family) !in appKeys && key(it.family) !in deviceKeys && matchesSearch(query, it.family) }
        return FontResults(pick(imported, FontSource.Imported) + pick(app, FontSource.App) + pick(deviceShown, FontSource.Device), remote)
    }
}
