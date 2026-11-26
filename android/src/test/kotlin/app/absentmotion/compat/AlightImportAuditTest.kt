package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AlightImportAuditTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val rules = JSONObject(File(pack, "foreign.json").readText())
    private val migration = EffectMigration.parse(rules.toString(), catalog)
    private val loose = EffectMigration.parse(unlimited(rules).toString(), catalog)

    data class Finding(val kind: String, val effect: String, val property: String, val detail: String)

    private fun unlimited(json: JSONObject): JSONObject = JSONObject(json.toString()).also { root ->
        for (id in root.keys()) {
            val params = root.getJSONObject(id).optJSONObject("params") ?: continue
            for (name in params.keys()) {
                when (val p = params.get(name)) {
                    is JSONObject -> { p.remove("min"); p.remove("max") }
                    is JSONArray -> for (i in 0 until p.length()) p.optJSONObject(i)?.let { it.remove("min"); it.remove("max") }
                }
            }
        }
    }

    private fun conversions(rule: JSONObject, name: String): List<JSONObject?>? = when (val p = rule.optJSONObject("params")?.opt(name)) {
        is JSONObject -> listOf(p)
        is JSONArray -> List(p.length()) { p.optJSONObject(it) }
        else -> null
    }

    private fun values(node: XmlNode): List<String> = listOfNotNull(node["value"]) + node.children.mapNotNull { it["v"] }

    private fun same(a: String, b: String): Boolean {
        val x = a.split(',').map { it.trim().toFloatOrNull() }
        val y = b.split(',').map { it.trim().toFloatOrNull() }
        return if (x.all { it != null } && y.all { it != null }) x.zip(y).all { (p, q) -> kotlin.math.abs(p!! - q!!) < 1e-4f } && x.size == y.size else a.trim().equals(b.trim(), true)
    }

    private fun property(node: XmlNode, name: String) = node.children.firstOrNull { it.name == "property" && it["name"] == name }

    fun audit(xml: String): List<Finding> {
        val scene = Scene.parse(xml)
        val out = migration.migrate(scene).scene.node
        val free = loose.migrate(scene).scene.node
        val found = ArrayList<Finding>()
        fun walk(a: XmlNode, b: XmlNode, c: XmlNode) {
            if (a.name == "effect") return effect(a, b, c, found)
            if (a.children.size != b.children.size || a.children.size != c.children.size) return
            for (i in a.children.indices) walk(a.children[i], b.children[i], c.children[i])
        }
        walk(scene.node, out, free)
        return found
    }

    private fun effect(a: XmlNode, b: XmlNode, c: XmlNode, found: MutableList<Finding>) {
        val id = a["id"] ?: return
        val short = id.removePrefix("com.alightcreative.")
        if (!id.startsWith("com.alightcreative.")) return
        val rule = rules.optJSONObject(id)
        if (rule == null || b["id"] == id) {
            found += Finding("unsupported", short, "*", "")
            for (p in a.children) if (p.name == "property") found += Finding("dropped", short, p["name"] ?: "?", "unsupported effect")
            return
        }
        val all = rule.optJSONObject("params")?.let { ps -> ps.keys().asSequence().flatMap { conversions(rule, it).orEmpty().filterNotNull() }.toList() }.orEmpty()
        val gates = all.mapNotNull { it.optString("gate").takeIf { g -> g.isNotEmpty() } }.toSet()
        val turn = rule.optJSONObject("orientation")?.let { setOf(it.optString("quat"), it.optString("euler")) }.orEmpty()
        val helpers = all.flatMap { listOf(it.optString("plus"), it.optString("over"), it.optString("unlessEnabled"), it.optString("ignoreWhenDisabled"), it.optString("ignoreWhenEnabled")) }.filter { it.isNotEmpty() }.toSet()
        for (p in a.children) {
            val name = p["name"]
            if (p.name != "property" || name == null) continue
            if (name in gates || name in turn) continue
            val list = conversions(rule, name)
            if (list == null) {
                if (name in helpers) continue
                found += Finding("dropped", short, name, "no rule: ${values(p).take(3).joinToString(" ")}")
                continue
            }
            list.forEachIndexed { i, conv ->
                val part = if (list.size > 1) "$name.${i + 1}" else name
                val pick = { v: String -> if (list.size > 1) v.split(',').getOrElse(i) { "" } else v }
                if (conv == null) {
                    found += Finding("dropped", short, part, "no conversion")
                    return@forEachIndexed
                }
                val to = conv.optString("to").takeIf { it.isNotEmpty() }
                val made = to?.let { property(b, it) }
                when {
                    conv.optBoolean("ignore") -> found += Finding("ignored", short, part, values(p).take(3).joinToString(" "))
                    made != null -> {
                        if (p.isKeyframed && listOf("gate", "ignoreWhenDisabled", "ignoreWhenEnabled").none(conv::has)) {
                            val src = p.keyframes
                            val dst = made.keyframes
                            if (!made.isKeyframed) {
                                if (src.map { it.v }.distinct().size > 1) found += Finding("keyframesLost", short, part, "${src.size} keys -> none")
                            } else {
                                val missing = src.filter { k -> dst.none { kotlin.math.abs(it.t - k.t) < 1e-4f && (it.e ?: "") == (k.e ?: "") } }
                                if (missing.isNotEmpty()) found += Finding("keyframesChanged", short, part, "${missing.size}/${src.size} keys differ")
                            }
                        }
                        val freeMade = property(c, to)
                        if (freeMade != null && values(freeMade) != values(made)) {
                            val i0 = values(made).indices.firstOrNull { values(made)[it] != values(freeMade).getOrNull(it) } ?: 0
                            found += Finding("clamped", short, part, "${values(freeMade).getOrNull(i0)} -> ${values(made).getOrNull(i0)}")
                        }
                    }
                    to != null && conv.has("texts") -> found += Finding("dropped", short, part, "unknown choice ${values(p).take(3).joinToString(" ")}")
                    to != null && (conv.has("ignoreWhenDisabled") || conv.has("ignoreWhenEnabled") || conv.has("unlessEnabled")) -> Unit
                    to != null -> found += Finding("dropped", short, part, "target $to missing")
                    conv.has("neutral") -> {
                        val neutral = conv.get("neutral").toString()
                        if (values(p).any { !same(pick(it), neutral) }) found += Finding("dropped", short, part, "neutral-only $neutral, got ${values(p).map(pick).distinct().take(3).joinToString(" ")}")
                        else found += Finding("neutralKept", short, part, "")
                    }
                    else -> found += Finding("dropped", short, part, "no target")
                }
            }
        }
    }

    @Test fun auditCorpus() {
        val list = System.getenv("ALIGHT_IMPORT_AUDIT")
        assumeTrue(!list.isNullOrBlank())
        val out = File(requireNotNull(System.getenv("ALIGHT_IMPORT_AUDIT_OUT")))
        out.printWriter().use { w ->
            for (path in File(list!!).readLines().filter { it.isNotBlank() }) {
                val findings = runCatching { audit(File(path).readText()) }.getOrElse { listOf(Finding("error", "-", "-", it.toString())) }
                for (f in findings) w.println(listOf(path, f.kind, f.effect, f.property, f.detail.replace('\t', ' ')).joinToString("\t"))
            }
        }
    }

    private val gapKinds = setOf("dropped", "ignored", "keyframesLost", "keyframesChanged", "clamped", "unsupported", "error")

    private fun gaps(xml: String): List<String> = audit(xml).filter { it.kind in gapKinds }.map { "${it.kind} ${it.effect} ${it.property}" }.distinct().sorted()

    @Test fun everyFixturePropertyIsKeptOrAKnownGap() {
        val found = gaps(File("../testdata/alight/effects-fixture.xml").readText())
        System.getenv("ALIGHT_IMPORT_GAPS_OUT")?.let { File(it).writeText(found.joinToString("\n", postfix = "\n")) }
        val known = File("../testdata/alight/import-gaps.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        val added = found - known.toSet()
        val fixed = known - found.toSet()
        assertTrue("New gaps: $added; fixed gaps still listed: $fixed", added.isEmpty() && fixed.isEmpty())
    }
}
