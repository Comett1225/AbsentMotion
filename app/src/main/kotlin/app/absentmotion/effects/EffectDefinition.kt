package app.absentmotion.effects

import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class EffectParam(
    val id: String,
    val label: String,
    val kind: String,
    val default: String,
    val min: Float = -Float.MAX_VALUE,
    val max: Float = Float.MAX_VALUE,
    val step: Float = 0.01f,
    val percent: Boolean = false,
    val options: List<String> = emptyList(),
    val pixels: Boolean = false,
) {
    val type: String get() = when (kind) { "point" -> "vec2"; "color" -> "color"; "switch" -> "bool"; else -> "float" }

    companion object {
        val Kinds = setOf("float", "angle", "point", "color", "switch", "choice")

        fun parse(json: JSONObject): EffectParam {
            val id = json.getString("id")
            val kind = json.getString("type")
            require(kind in Kinds) { "$id: unknown type $kind" }
            val d = json.opt("default")
            val default = when (kind) {
                "point" -> (d as JSONArray).let { formatFloats(floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) }
                "color" -> (d as String).lowercase(Locale.US).let { if (it.length == 7) "#ff" + it.drop(1) else it }
                    .also { require(Regex("#[0-9a-f]{8}").matches(it)) { "$id: colour $d" } }
                "switch" -> (d == true || (d as? Number)?.toInt() == 1).toString()
                else -> formatFloats(floatArrayOf((d as Number).toFloat()))
            }
            if (kind == "choice") {
                val options = json.getJSONArray("options").let { a -> List(a.length()) { a.getString(it) } }
                require(options.isNotEmpty() && (d as Number).toInt() in options.indices) { "$id: choice $d" }
                return EffectParam(id, json.optString("label", id), kind, default, 0f, options.size - 1f, 1f, options = options)
            }
            return EffectParam(id, json.optString("label", id), kind, default,
                json.optDouble("min", -Float.MAX_VALUE.toDouble()).toFloat(), json.optDouble("max", Float.MAX_VALUE.toDouble()).toFloat(),
                json.optDouble("step", 0.01).toFloat(), pixels = json.optBoolean("pixels"))
        }
    }
}

data class EffectDefinition(
    val id: String,
    val key: String,
    val name: String,
    val category: String,
    val description: String,
    val params: List<EffectParam>,
    val sksl: String? = null,
    val native: Boolean = false,
    val status: String = "ok",
) {
    val available: Boolean get() = status == "ok"
    val thumbnail: String get() = "thumb/$key.webp"

    fun instance(): XmlNode = XmlNode("effect", mapOf("id" to id, "hidden" to "false"), params.map {
        XmlNode("property", mapOf("name" to it.id, "type" to it.type, "value" to it.default))
    })

    fun runtimeXml(id: String = this.id): String = XmlNode("effect", listOfNotNull("id" to id, sksl?.let { "sksl" to it }).toMap(),
        params.map { XmlNode("param", mapOf("name" to it.id, "value" to it.default)) }).toXml()

    companion object {
        fun parse(json: JSONObject): EffectDefinition {
            val key = json.getString("id")
            val category = json.getString("category")
            require(category in EffectCatalog.Categories) { "$key: unknown category $category" }
            val params = json.optJSONArray("params") ?: JSONArray()
            val native = json.optBoolean("native")
            val sksl = json.optString("sksl").takeIf { it.isNotBlank() }
            require(native || sksl != null) { "$key: no shader" }
            return EffectDefinition("absent.$key", key, json.optString("name", key), category, json.optString("description"),
                List(params.length()) { EffectParam.parse(params.getJSONObject(it)) }, if (native) null else sksl, native)
        }
    }
}
