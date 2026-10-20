package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.valueAt
import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.withSign

class EffectMigration(private val rules: Map<String, Rule>, private val catalog: EffectCatalog) {
    class Conversion(
        val to: String?,
        val power: Float = 1f,
        val scale: Float = 1f,
        val offset: Float = 0f,
        val wrap: Float = 0f,
        val min: Float = -Float.MAX_VALUE,
        val max: Float = Float.MAX_VALUE,
        val values: Map<String, Float> = emptyMap(),
        val neutral: String? = null,
        val component: Int = -1,
        val plus: String? = null,
    )

    class Rule(val to: String, val params: Map<String, List<Conversion?>>, val set: Map<String, String>)

    data class Result(val scene: Scene, val converted: Map<String, Int>, val dropped: Set<String>) {
        val changed: Boolean get() = converted.isNotEmpty()
    }

    fun migrate(scene: Scene): Result {
        val converted = linkedMapOf<String, Int>()
        val dropped = sortedSetOf<String>()
        var renamed = false
        fun visit(node: XmlNode): XmlNode {
            val rule = if (node.name == "effect") rules[node["id"]] else null
            val own = rule?.let { catalog.definitions.firstOrNull { d -> d.key == it.to } }
            val older = own != null && node["id"] == own.id
            if (rule != null && own != null && (!older || node.children.any { c -> c.name == "property" && own.params.none { it.id == c["name"] } })) {
                if (older) renamed = true else converted.merge("${node["id"]!!.substringAfterLast('.')} → ${own.name}", 1, Int::plus)
                return convert(node, rule, own, older) { dropped += "${own.name}: $it" }
            }
            return if (node.children.isEmpty()) node else node.copy(children = node.children.map(::visit))
        }
        val root = visit(scene.node)
        return Result(if (converted.isEmpty() && !renamed) scene else Scene(root), converted, dropped)
    }

    private fun convert(effect: XmlNode, rule: Rule, own: EffectDefinition, keep: Boolean, drop: (String) -> Unit): XmlNode {
        val made = linkedMapOf<String, XmlNode>()
        val others = ArrayList<XmlNode>()
        for (child in effect.children) {
            val name = child["name"]
            if (child.name != "property" || name == null) { others += child; continue }
            val conversions = rule.params[name]
            if (conversions == null) {
                if (keep && own.params.any { it.id == name }) made.putIfAbsent(name, child) else drop(name)
                continue
            }
            val split = conversions.size > 1
            conversions.forEachIndexed { i, c ->
                val param = c?.to?.let { to -> own.params.firstOrNull { it.id == to } }
                if (param != null) {
                    val shift = c.plus?.let { other -> effect.children.firstOrNull { it.name == "property" && it["name"] == other }?.valueAt(0f)?.toFloatOrNull() } ?: 0f
                    made[param.id] = property(child, param.id, param.type) { text -> convertValue(text, c, if (c.component >= 0) c.component else if (split) i else -1, shift) }
                } else if (c?.neutral == null || values(child).any { !same(if (split) it.split(',').getOrElse(i) { "" } else it, c.neutral) }) {
                    drop(if (split) "$name (${i + 1})" else name)
                }
            }
        }
        val fixed = rule.set.filterKeys { it !in made }.mapNotNull { (id, value) ->
            own.params.firstOrNull { it.id == id }?.let { XmlNode("property", mapOf("name" to id, "type" to it.type, "value" to value)) }
        }
        val ordered = own.params.mapNotNull { p -> made[p.id] ?: fixed.firstOrNull { it["name"] == p.id } }
        return effect.copy(attrs = effect.attrs + ("id" to own.id), children = ordered + others)
    }

    private fun property(source: XmlNode, name: String, type: String, change: (String) -> String): XmlNode {
        val attrs = linkedMapOf("name" to name, "type" to type)
        source.attrs.forEach { (k, v) -> if (k != "name" && k != "type") attrs[k] = if (k == "value") change(v) else v }
        return source.copy(attrs = attrs, children = source.children.map { kf ->
            if (kf.name == "kf" && kf["v"] != null) kf.with("v", change(kf["v"]!!)) else kf
        })
    }

    private fun values(node: XmlNode): List<String> = listOfNotNull(node["value"]) + node.children.mapNotNull { it["v"] }

    private fun same(a: String, b: String): Boolean {
        val x = a.split(',').map { it.trim().toFloatOrNull() }
        val y = b.split(',').map { it.trim().toFloatOrNull() }
        return if (x.all { it != null } && y.all { it != null }) x == y else a.trim() == b.trim()
    }

    private fun convertValue(text: String, c: Conversion, component: Int, shift: Float): String {
        val t = text.trim()
        if (t.startsWith("#") || t == "true" || t == "false") return t
        val parts = t.split(',')
        val picked = if (component >= 0) listOf(parts.getOrElse(component) { "0" }) else parts
        val numbers = picked.map { p ->
            val n = p.trim().toFloatOrNull() ?: return t
            c.values[n.toInt().toString()]?.takeIf { n % 1f == 0f } ?: n
        }
        return formatFloats(FloatArray(numbers.size) { i ->
            val raised = if (c.power == 1f) numbers[i] else abs(numbers[i]).pow(c.power).withSign(numbers[i])
            val moved = raised * c.scale + c.offset + shift
            (if (c.wrap > 0f) moved.mod(c.wrap) else moved).coerceIn(c.min, c.max)
        })
    }

    companion object {
        const val ASSET = "effects/foreign.json"

        fun read(context: Context, catalog: EffectCatalog = EffectCatalog.read(context)): EffectMigration =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, catalog)

        fun parse(json: String, catalog: EffectCatalog): EffectMigration {
            val root = JSONObject(json)
            val rules = root.keys().asSequence().associateWith { id ->
                val rule = root.getJSONObject(id)
                val params = rule.optJSONObject("params") ?: JSONObject()
                val set = rule.optJSONObject("set") ?: JSONObject()
                Rule(
                    rule.getString("to"),
                    params.keys().asSequence().associateWith { name ->
                        when (val p = params.get(name)) {
                            is JSONArray -> List(p.length()) { i -> p.optJSONObject(i)?.let(::conversion) }
                            else -> listOf(conversion(p as JSONObject))
                        }
                    },
                    set.keys().asSequence().associateWith { set.get(it).let { v -> if (v is Number) formatFloats(floatArrayOf(v.toFloat())) else v.toString() } },
                )
            }
            return EffectMigration(rules, catalog)
        }

        private fun conversion(json: JSONObject) = Conversion(
            json.optString("to").takeIf { it.isNotEmpty() },
            json.optDouble("power", 1.0).toFloat(),
            json.optDouble("scale", 1.0).toFloat(),
            json.optDouble("offset", 0.0).toFloat(),
            json.optDouble("wrap", 0.0).toFloat(),
            json.optDouble("min", -Float.MAX_VALUE.toDouble()).toFloat(),
            json.optDouble("max", Float.MAX_VALUE.toDouble()).toFloat(),
            json.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.getDouble(it).toFloat() } }.orEmpty(),
            json.opt("neutral")?.toString(),
            json.optInt("component", -1),
            json.optString("plus").takeIf { it.isNotEmpty() },
        )
    }
}
