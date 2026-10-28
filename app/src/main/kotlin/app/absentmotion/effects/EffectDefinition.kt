package app.absentmotion.effects

import app.absentmotion.model.Keyframe
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.formatFloats
import app.absentmotion.model.valueAt
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
    val unit: String = "",
    val layerTime: Boolean = false,
    val after: String? = null,
    val legacyDefault: String? = null,
    val showWhen: Pair<String, Set<Int>>? = null,
) {
    val type: String get() = when (kind) { "point" -> "vec2"; "color" -> "color"; "switch" -> "bool"; "text" -> "string"; else -> "float" }
    val missingDefault: String get() = legacyDefault ?: default

    fun format(value: Any?): String = format(id, kind, value)

    companion object {
        val Kinds = setOf("float", "angle", "point", "color", "switch", "choice", "text")

        fun format(id: String, kind: String, d: Any?): String = when (kind) {
            "point" -> (d as JSONArray).let { formatFloats(floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) }
            "color" -> (d as String).lowercase(Locale.US).let { if (it.length == 7) "#ff" + it.drop(1) else it }
                .also { require(Regex("#[0-9a-f]{8}").matches(it)) { "$id: colour $d" } }
            "switch" -> (d == true || (d as? Number)?.toInt() == 1).toString()
            "text" -> d as String
            else -> formatFloats(floatArrayOf((d as Number).toFloat()))
        }

        fun parse(json: JSONObject): EffectParam {
            val id = json.getString("id")
            val kind = json.getString("type")
            require(kind in Kinds) { "$id: unknown type $kind" }
            val d = json.opt("default")
            val default = format(id, kind, d)
            val legacyDefault = if (json.has("legacyDefault")) format(id, kind, json.get("legacyDefault")) else null
            val showWhen = json.optJSONObject("when")?.let { o ->
                o.keys().asSequence().firstOrNull()?.let { key -> key to o.getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) }.toSet() } }
            }
            if (kind == "choice") {
                val options = json.getJSONArray("options").let { a -> List(a.length()) { a.getString(it) } }
                require(options.isNotEmpty() && (d as Number).toInt() in options.indices) { "$id: choice $d" }
                return EffectParam(id, json.optString("label", id), kind, default, 0f, options.size - 1f, 1f, options = options, legacyDefault = legacyDefault)
            }
            return EffectParam(id, json.optString("label", id), kind, default,
                json.optDouble("min", -Float.MAX_VALUE.toDouble()).toFloat(), json.optDouble("max", Float.MAX_VALUE.toDouble()).toFloat(),
                json.optDouble("step", 0.01).toFloat(), percent = json.optBoolean("percent"), pixels = json.optBoolean("pixels"), unit = json.optString("unit"),
                layerTime = json.optBoolean("layerTime"), after = json.optString("after").takeIf { it.isNotEmpty() }, legacyDefault = legacyDefault,
                showWhen = showWhen)
        }
    }
}

data class EffectPreset(
    val name: String, val values: Map<String, String>, val keys: Map<String, List<Keyframe>>,
    val slug: String = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-'),
)

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
    val since: String? = null,
    val presets: List<EffectPreset> = emptyList(),
) {
    val available: Boolean get() = status == "ok"
    val thumbnail: String get() = "thumb/$key.webp"

    fun thumbnail(preset: EffectPreset): String = "thumb/$key.${preset.slug}.webp"

    fun instance(preset: EffectPreset?, lengthMs: Int): XmlNode {
        val plain = instance()
        if (preset == null) return plain
        val seconds = lengthMs.coerceAtLeast(1) / 1000f
        return plain.copy(children = plain.children.map { node ->
            val keys = preset.keys[node["name"]]
            val value = preset.values[node["name"]]
            when {
                keys != null -> {
                    val fit = minOf(1f, seconds / keys.maxOf { it.t }.coerceAtLeast(1e-3f))
                    node.withKeyframes(keys.map { it.copy(t = (it.t * fit / seconds).coerceIn(0f, 1f)) })
                }
                value != null -> node.with("value", value)
                else -> node
            }
        })
    }

    fun instance(): XmlNode = XmlNode("effect", mapOf("id" to id, "hidden" to "false"), params.map {
        XmlNode("property", mapOf("name" to it.id, "type" to it.type, "value" to it.default))
    })

    fun bounded(param: EffectParam, seconds: Float, value: (EffectParam) -> Float): EffectParam {
        if (!param.layerTime && param.after == null) return param
        val floor = params.firstOrNull { it.id == param.after }?.let { p -> bounded(p, seconds, value).let { b -> value(p).coerceIn(b.min, b.max) } }
        val lo = maxOf(param.min, floor ?: param.min)
        return param.copy(min = lo, max = maxOf(lo, if (param.layerTime) minOf(param.max, seconds) else param.max))
    }

    fun shown(param: EffectParam, instance: XmlNode, t: Float): Boolean {
        val (id, values) = param.showWhen ?: return true
        val raw = instance.children.firstOrNull { it.name == "property" && it["name"] == id }?.valueAt(t) ?: params.firstOrNull { it.id == id }?.missingDefault
        return raw?.toFloatOrNull()?.let { kotlin.math.round(it).toInt() } in values
    }

    fun runtimeXml(): String = XmlNode("effect", listOfNotNull("id" to id, sksl?.let { "sksl" to it }).toMap(),
        params.map { XmlNode("param", mapOf("name" to it.id, "value" to it.missingDefault)) }).toXml()

    companion object {
        fun parse(json: JSONObject): EffectDefinition {
            val key = json.getString("id")
            val category = json.getString("category")
            require(category in EffectCatalog.Categories) { "$key: unknown category $category" }
            val params = json.optJSONArray("params") ?: JSONArray()
            val native = json.optBoolean("native")
            val sksl = json.optString("sksl").takeIf { it.isNotBlank() }
            require(native || sksl != null) { "$key: no shader" }
            val parsed = List(params.length()) { EffectParam.parse(params.getJSONObject(it)) }
            parsed.forEachIndexed { i, p -> require(p.after == null || parsed.take(i).any { it.id == p.after }) { "$key.${p.id}: after ${p.after} is not an earlier parameter" } }
            val presets = json.optJSONArray("presets") ?: JSONArray()
            return EffectDefinition("absent.$key", key, json.optString("name", key), category, json.optString("description"),
                parsed, if (native) null else sksl, native, since = json.optString("since").takeIf { it.isNotBlank() },
                presets = List(presets.length()) { preset(key, parsed, presets.getJSONObject(it)) })
        }

        private fun preset(key: String, params: List<EffectParam>, json: JSONObject): EffectPreset {
            val name = json.getString("name")
            val settings = json.getJSONObject("params")
            val values = linkedMapOf<String, String>()
            val keys = linkedMapOf<String, List<Keyframe>>()
            for (id in settings.keys()) {
                val param = params.firstOrNull { it.id == id } ?: error("$key preset $name: no parameter $id")
                val v = settings.get(id)
                val frames = (v as? JSONArray)?.takeIf { it.optJSONArray(0) != null }
                if (frames == null) values[id] = param.format(v)
                else keys[id] = List(frames.length()) { k ->
                    frames.getJSONArray(k).let { Keyframe(it.getDouble(0).toFloat(), param.format(it.get(1)), it.optString(2).takeIf(String::isNotBlank)) }
                }
            }
            return EffectPreset(name, values, keys)
        }
    }
}
