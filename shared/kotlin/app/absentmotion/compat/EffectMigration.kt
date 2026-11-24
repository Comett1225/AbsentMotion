package app.absentmotion.compat

import app.absentmotion.model.finiteFloat
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.valueAt
import app.absentmotion.platform.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
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
        val relative: Boolean = false,
        val per: String? = null,
        val take: Int = 0,
        val gate: String? = null,
        val ignore: Boolean = false,
        val turn: Boolean = false,
        val blank: String? = null,
        val signs: List<Float> = emptyList(),
        val pre: List<Float> = emptyList(),
        val absent: String? = null,
        val pad: List<Float> = emptyList(),
        val slot: Int = -1,
        val texts: Map<String, String> = emptyMap(),
        val plusScale: Float = 1f,
        val over: String? = null,
        val overDefault: String? = null,
        val polar: Boolean = false,
        val unlessEnabled: String? = null,
        val ignoreWhenDisabled: String? = null,
        val ignoreWhenEnabled: String? = null,
    )

    class Turn(val quat: String, val spin: String, val to: List<String>, val inverse: Boolean = false, val sphere: Boolean = false)

    class Rule(val to: String, val params: Map<String, List<Conversion?>>, val set: Map<String, String>, val onlyWith: String? = null, val turn: Turn? = null)

    data class Result(val scene: Scene, val converted: Map<String, Int>, val dropped: Set<String>) {
        val changed: Boolean get() = converted.isNotEmpty()
    }

    fun migrate(scene: Scene): Result {
        val converted = linkedMapOf<String, Int>()
        val dropped = sortedSetOf<String>()
        var renamed = false
        val media = scene.node.children.filter { it.name == "media" }.mapNotNull { m ->
            val w = m["width"]?.finiteFloat()?.takeIf { it > 0f } ?: return@mapNotNull null
            val h = m["height"]?.finiteFloat()?.takeIf { it > 0f } ?: return@mapNotNull null
            m["uri"]?.let { it to if (m["orientation"] in setOf("90", "270")) floatArrayOf(h, w) else floatArrayOf(w, h) }
        }.toMap()
        fun visit(node: XmlNode, dims: FloatArray, clipped: Boolean = false): XmlNode {
            val rule = if (node.name == "effect") rules[node["id"]]?.takeIf { r -> r.onlyWith == null || node.children.any { c -> c.name == "property" && c["name"] == r.onlyWith } } else null
            val own = rule?.let { catalog.definitions.firstOrNull { d -> d.key == it.to } }
            val older = own != null && node["id"] == own.id
            if (rule != null && own != null && (!older || node.children.any { c -> c.name == "property" && own.params.none { it.id == c["name"] } && (rule.params.isEmpty() || c["name"] in rule.params) })) {
                if (older) renamed = true else converted.merge("${node["id"]!!.substringAfterLast('.')} → ${own.name}", 1, Int::plus)
                val convertedEffect = convert(node, rule, own, older, dims) { dropped += "${own.name}: $it" }
                return if (node["id"] == "com.alightcreative.effects.lumastamper" && clipped) convertedEffect.copy(children = convertedEffect.children.map {
                    if (it["name"] == "compositing") it.with("value", "0") else it
                }) else convertedEffect
            }
            val inner = if (node.name in Scene.LAYER_TAGS) layerDims(node, dims, media) else dims
            return if (node.children.isEmpty()) node else node.copy(children = node.children.map { visit(it, inner, node["clippingMask"] == "true") })
        }
        val w = scene.node["width"]?.finiteFloat()?.takeIf { it > 0f } ?: 1080f
        val h = scene.node["height"]?.finiteFloat()?.takeIf { it > 0f } ?: 1080f
        var root = visit(scene.node, floatArrayOf(w, h, 0f, w, h, w, h))
        val foreign = root["am"]?.startsWith("com.alightcreative") == true
        if (foreign) root = root.copy(attrs = root.attrs + ("am" to "app.absentmotion") + ("alightImport" to "true"), children = root.children.map { pixelText(squaredBlendOpacity(it)) })
        return Result(if (converted.isEmpty() && !renamed && !foreign) scene else Scene(root), converted, dropped)
    }

    private fun layerDims(layer: XmlNode, dims: FloatArray, media: Map<String, FloatArray>): FloatArray {
        val size = sizeOf(layer)
        val scale = transformOf(layer, "scale")?.split(',')?.mapNotNull { it.trim().finiteFloat() }.orEmpty()
        val sx = abs(scale.getOrElse(0) { 1f }); val sy = abs(scale.getOrElse(1) { scale.getOrElse(0) { 1f } })
        val w = size?.get(0) ?: dims[0]; val h = size?.get(1) ?: dims[1]
        val texture = (if (layer["fillType"] == "media") media[layer["fillImage"] ?: layer["fillVideo"]] else null)?.let { floatArrayOf(w / it[0], h / it[1]) }
        val texel = when {
            texture == null -> floatArrayOf(1f, 1f)
            layer["mediaFillMode"] == "fill" -> FloatArray(2) { maxOf(texture[0], texture[1]) }
            layer["mediaFillMode"] == "fit" -> FloatArray(2) { minOf(texture[0], texture[1]) }
            else -> texture
        }
        return floatArrayOf(w, h, dims.getOrElse(2) { 0f } + rotationOf(layer), (size?.get(0) ?: dims.getOrElse(3) { w }) * sx, (size?.get(1) ?: dims.getOrElse(4) { h }) * sy,
            dims.getOrElse(5) { w }, dims.getOrElse(6) { h }, texel[0], texel[1])
    }

    private fun transformOf(layer: XmlNode, name: String): String? =
        layer.children.firstOrNull { it.name == "transform" }?.children?.firstOrNull { it.name == name }?.valueAt(0f)

    private fun pixelText(node: XmlNode): XmlNode = when {
        node.name == "text" -> node.with("textMetrics", "pixel")
        node.children.isEmpty() -> node
        else -> node.copy(children = node.children.map(::pixelText))
    }

    private fun squaredBlendOpacity(node: XmlNode): XmlNode {
        if (node.name !in Scene.LAYER_TAGS) return node
        val layer = if (node.name != "embedScene") node else node.copy(children = node.children.map { s -> if (s.name == "scene") s.copy(children = s.children.map(::squaredBlendOpacity)) else s })
        if (layer["blending"] !in setOf("multiply", "subtract")) return layer
        if (layer.children.any { it.name == "effect" && it["id"] in setOf(LIFT, "absent.backdrop") }) return layer
        val square = { v: String -> v.trim().finiteFloat()?.let { formatFloats(floatArrayOf(it * it)) } ?: v }
        return layer.copy(children = layer.children.map { t ->
            if (t.name != "transform") t else t.copy(children = t.children.map { o ->
                if (o.name != "opacity") o else o.copy(attrs = o.attrs.mapValues { (k, v) -> if (k == "value") square(v) else v },
                    children = o.children.map { kf -> kf["v"]?.let { kf.with("v", square(it)) } ?: kf })
            })
        })
    }

    private fun rotationOf(layer: XmlNode): Float = layer.children.firstOrNull { it.name == "transform" }?.children
        ?.firstOrNull { it.name == "rotation" }?.valueAt(0f)?.split(',')?.firstOrNull()?.trim()?.finiteFloat() ?: 0f

    private fun sizeOf(layer: XmlNode): FloatArray? = layer.children.firstOrNull { it.name == "property" && it["name"] == "size" }?.valueAt(0f)
        ?.split(',')?.mapNotNull { it.trim().finiteFloat() }?.takeIf { it.size >= 2 && it[0] > 0f && it[1] > 0f }?.let { floatArrayOf(2 * it[0], 2 * it[1]) }

    private fun convert(effect: XmlNode, rule: Rule, own: EffectDefinition, keep: Boolean, dims: FloatArray, drop: (String) -> Unit): XmlNode {
        val made = linkedMapOf<String, XmlNode>()
        val others = ArrayList<XmlNode>()
        val gates = rule.params.values.flatten().mapNotNull { it?.gate }.toSet()
        val turn = rule.turn?.takeIf { t -> t.to.all { id -> own.params.any { it.id == id } } }
        fun enabled(key: String) = effect.children.any { it.name == "property" && it["name"] == key &&
            values(it).any { value -> value == "true" || (value.finiteFloat() ?: 0f) > 0f } }
        fun sibling(name: String?) = name?.let { n -> effect.children.firstOrNull { it.name == "property" && it["name"] == n } }
        fun convertAt(text: String, c: Conversion, component: Int, t: Float) = convertValue(text, c, component,
            (sibling(c.plus)?.valueAt(t)?.finiteFloat() ?: 0f) * c.plusScale, dims, c.over?.let { length(sibling(it)?.valueAt(t) ?: c.overDefault) } ?: 1f)
        fun linked(source: XmlNode, name: String, type: String, c: Conversion, component: Int): XmlNode {
            val moving = listOfNotNull(sibling(c.plus), sibling(c.over)).filter { it.isKeyframed }
            if (moving.isEmpty()) return property(source, name, type) { convertAt(it, c, component, 0f) }
            val keys = (listOf(source) + moving).flatMap { it.keyframes }
            return property(source, name, type) { it }.withKeyframes(keys.map { it.t }.distinct().sorted().map { t ->
                keys.first { it.t == t }.copy(v = convertAt(source.valueAt(t) ?: "", c, component, t))
            })
        }
        for (child in effect.children) {
            val name = child["name"]
            if (child.name != "property" || name == null) { others += child; continue }
            if (name in gates || (turn != null && (name == turn.quat || name == turn.spin))) continue
            val conversions = rule.params[name]
            if (conversions == null) {
                if (keep && own.params.any { it.id == name }) made.putIfAbsent(name, child) else drop(name)
                continue
            }
            val split = conversions.size > 1
            conversions.forEachIndexed { i, c ->
                val param = c?.to?.let { to -> own.params.firstOrNull { it.id == to } }
                if (c?.ignore == true || c?.ignoreWhenDisabled?.let { !enabled(it) } == true || c?.ignoreWhenEnabled?.let(::enabled) == true) return@forEachIndexed
                if (c?.unlessEnabled?.let(::enabled) == true) { drop(name); return@forEachIndexed }
                if (c != null && c.texts.isNotEmpty() && values(child).any { textFor(it, c) == null }) { drop(name); return@forEachIndexed }
                if (param != null) {
                    val off = c.gate?.let { gate -> sibling(gate)?.valueAt(0f)?.trim() != "true" } ?: false
                    made[param.id] = if (off) XmlNode("property", mapOf("name" to param.id, "type" to param.type, "value" to "#00000000"))
                        else if (c.slot >= 0) slotted(made[param.id], child, param.id, param.type, rule.set[param.id] ?: param.default, c.slot) { convertAt(it, c, -1, 0f) }
                        else linked(child, param.id, param.type, c, if (c.component >= 0) c.component else if (split && c.take == 0) i else -1)
                } else if (c?.neutral == null || values(child).any { !same(if (split) it.split(',').getOrElse(i) { "" } else it, c.neutral) }) {
                    drop(if (split) "$name (${i + 1})" else name)
                }
            }
        }
        if (turn != null) {
            val quat = effect.children.firstOrNull { it.name == "property" && it["name"] == turn.quat }
            val spin = effect.children.firstOrNull { it.name == "property" && it["name"] == turn.spin }
            if (quat != null || spin != null) {
                val base = turn.to.map { id -> rule.set[id]?.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                Orientation(quat, spin, base, turn.inverse, turn.sphere).properties(turn.to).forEach { made[it["name"]!!] = it }
            }
        }
        for ((name, conversions) in rule.params) {
            if (effect.children.any { it.name == "property" && it["name"] == name }) continue
            for (c in conversions) {
                val param = c?.absent?.let { c.to?.let { to -> own.params.firstOrNull { it.id == to } } } ?: continue
                if (param.id !in made) made[param.id] = linked(XmlNode("property", mapOf("value" to c.absent)), param.id, param.type, c, c.component)
            }
        }
        val fixed = rule.set.filterKeys { it !in made }.mapNotNull { (id, value) ->
            own.params.firstOrNull { it.id == id }?.let { XmlNode("property", mapOf("name" to id, "type" to it.type, "value" to value)) }
        }
        val ordered = own.params.mapNotNull { p ->
            made[p.id] ?: fixed.firstOrNull { it["name"] == p.id }
                ?: if (!keep && p.missingDefault != p.default) XmlNode("property", mapOf("name" to p.id, "type" to p.type, "value" to p.default)) else null
        }
        return effect.copy(attrs = effect.attrs + ("id" to own.id), children = ordered + others)
    }

    private fun property(source: XmlNode, name: String, type: String, change: (String) -> String): XmlNode {
        val attrs = linkedMapOf("name" to name, "type" to type)
        source.attrs.forEach { (k, v) -> if (k != "name" && k != "type") attrs[k] = if (k == "value") change(v) else v }
        return source.copy(attrs = attrs, children = source.children.map { kf ->
            if (kf.name == "kf" && kf["v"] != null) kf.with("v", change(kf["v"]!!)) else kf
        })
    }

    private fun slotted(prior: XmlNode?, source: XmlNode, name: String, type: String, fallback: String, slot: Int, change: (String) -> String): XmlNode {
        fun put(base: String?, text: String) = if (type == "color") recolor(base ?: fallback, slot, change(text))
            else (base ?: fallback).split(',').mapIndexed { k, v -> if (k == slot) change(text) else v.trim() }.joinToString(",")
        if (prior == null || !prior.isKeyframed) return property(source, name, type) { put(prior?.get("value"), it) }
        if (!source.isKeyframed) return source["value"]?.let { text -> prior.withKeyframes(prior.keyframes.map { it.copy(v = put(it.v, text)) }) } ?: prior
        val mine = prior.keyframes
        val theirs = source.keyframes
        return prior.withKeyframes((mine.map { it.t } + theirs.map { it.t }).distinct().sorted().map { t ->
            (mine.firstOrNull { it.t == t } ?: theirs.first { it.t == t }).copy(t = t, v = put(prior.valueAt(t), source.valueAt(t) ?: ""))
        })
    }

    private fun recolor(base: String, slot: Int, value: String): String {
        val hex = base.trim().removePrefix("#").let { if (it.length == 6) "ff$it" else it }
        val parts = hex.takeIf { it.length == 8 }?.chunked(2)?.map { it.toIntOrNull(16) ?: return base }?.toMutableList() ?: return base
        val v = value.trim().finiteFloat() ?: return base
        if (slot !in 0..3) return base
        parts[slot] = Math.round(v.coerceIn(0f, 1f) * 255f)
        return parts.joinToString("", "#") { it.toString(16).padStart(2, '0') }
    }

    private fun values(node: XmlNode): List<String> = listOfNotNull(node["value"]) + node.children.mapNotNull { it["v"] }

    private fun same(a: String, b: String): Boolean {
        val x = a.split(',').map { it.trim().finiteFloat() }
        val y = b.split(',').map { it.trim().finiteFloat() }
        return if (x.all { it != null } && y.all { it != null }) x == y else a.trim() == b.trim()
    }

    private fun textFor(value: String, c: Conversion): String? = c.texts[value.trim().finiteFloat()?.toInt()?.toString()]

    private fun length(text: String?): Float =
        text?.split(',')?.mapNotNull { it.trim().finiteFloat() }?.let { hypot(it.getOrElse(0) { 0f }, it.getOrElse(1) { 0f }) }?.takeIf { it > 0f } ?: 1f

    private fun convertValue(text: String, c: Conversion, component: Int, shift: Float, dims: FloatArray, divisor: Float = 1f): String {
        if (c.texts.isNotEmpty()) return textFor(text, c) ?: text
        val t = text.trim().let { s -> if (c.blank != null && s.split(',').all { it.trim().finiteFloat() == 0f }) c.blank else s }
        if (t == "true" || t == "false") return c.values[t]?.let { formatFloats(floatArrayOf(it)) } ?: t
        if (t.startsWith("#")) return t
        val parts = t.split(',')
        val picked = if (component >= 0) listOf(parts.getOrElse(component) { "0" }) else if (c.take > 0) parts.take(c.take) else parts
        val numbers = picked.map { p ->
            val n = p.trim().finiteFloat() ?: return t
            c.values[n.toInt().toString()]?.takeIf { n % 1f == 0f } ?: n
        }.let { n -> if (n.size == 1 && component < 0 && c.pad.isNotEmpty()) c.pad.map { if (it.isNaN()) n[0] else it } else n }
        if (c.polar && numbers.size >= 2) return formatFloats(floatArrayOf(Math.toDegrees(atan2(-numbers[1], numbers[0]).toDouble()).toFloat() * c.scale + c.offset))
        val whole = when (c.per) { "width" -> dims[0]; "height" -> dims[1]; "short" -> minOf(dims[0], dims[1]); "spanWidth" -> dims.getOrElse(3) { dims[0] }; "spanHeight" -> dims.getOrElse(4) { dims[1] }
            "screenShort" -> minOf(dims.getOrElse(5) { dims[0] }, dims.getOrElse(6) { dims[1] }); else -> 1f }
        return formatFloats(FloatArray(numbers.size) { i ->
            val axis = (if (component >= 0) component else i) % 2
            val size = if (c.per == "span") dims.getOrElse(3 + axis) { dims[axis] } else whole
            val texel = if (c.per == "texture") dims.getOrElse(7 + axis) { 1f } else 1f
            val shown = if (c.relative) numbers[i] * texel / dims[axis] + 0.5f else numbers[i]
            val base = shown * c.signs.getOrElse(i) { 1f } + c.pre.getOrElse(i) { 0f }
            val raised = if (c.power == 1f) base else abs(base).pow(c.power).withSign(base)
            val moved = raised * c.scale * size / divisor + c.offset + shift + if (c.turn) dims.getOrElse(2) { 0f } else 0f
            (if (c.wrap > 0f) moved.mod(c.wrap) else moved).coerceIn(c.min, c.max)
        })
    }

    companion object {
        const val ASSET = "effects/foreign.json"
        private const val LIFT = "com.alightcreative.effects.lift"

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
                    rule.optString("onlyWith").takeIf { it.isNotEmpty() },
                    rule.optJSONObject("orientation")?.let { o -> Turn(o.getString("quat"), o.optString("euler"), o.getJSONArray("to").let { a -> List(a.length()) { a.getString(it) } }, o.optBoolean("inverse"), o.optBoolean("sphere")) },
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
            json.optBoolean("relative"),
            json.optString("per").takeIf { it.isNotEmpty() },
            json.optInt("take", 0),
            json.optString("gate").takeIf { it.isNotEmpty() },
            json.optBoolean("ignore"),
            json.optBoolean("turn"),
            json.optString("blank").takeIf { it.isNotEmpty() },
            json.optJSONArray("signs")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } }.orEmpty(),
            json.optJSONArray("pre")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } }.orEmpty(),
            json.opt("absent")?.toString(),
            json.optJSONArray("pad")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } }.orEmpty(),
            json.optInt("slot", -1),
            json.optJSONObject("texts")?.let { v -> v.keys().asSequence().associateWith { v.getString(it) } }.orEmpty(),
            json.optDouble("plusScale", 1.0).toFloat(),
            json.optString("over").takeIf { it.isNotEmpty() },
            json.optString("overDefault").takeIf { it.isNotEmpty() },
            json.optBoolean("polar"),
            json.optString("unlessEnabled").takeIf { it.isNotEmpty() },
            json.optString("ignoreWhenDisabled").takeIf { it.isNotEmpty() },
            json.optString("ignoreWhenEnabled").takeIf { it.isNotEmpty() },
        )
    }
}

fun Scene.oldAlightImport(): Boolean {
    if (!fromAlight || node["reimportTip"] != null) return false
    fun lacks(effect: XmlNode, name: String) = effect.children.none { it.name == "property" && it["name"] == name }
    fun stale(n: XmlNode): Boolean = n.name == "effect" && (n["id"] == "absent.backdrop" && lacks(n, "spill") || n["id"] == "absent.exposure" && lacks(n, "soft") || n["id"] == "absent.stripes" && lacks(n, "style") ||
        n["id"] == "absent.tiles" && n.children.any { it.name == "property" && it["name"] == "sizing" && it["value"]?.trim()?.finiteFloat() == 1f }) ||
        n.children.any(::stale)
    return stale(node)
}

fun Scene.withReimportTipShown(): Scene = Scene(node.with("reimportTip", "shown"))
