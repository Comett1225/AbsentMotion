package app.absentmotion.effects

import app.absentmotion.i18n.AppLanguage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectTranslationTest {
    private val pack = File("src/main/assets/effects")
    private val english = EffectCatalog.parse { File(pack, it).inputStream() }
    private fun shape(c: EffectCatalog) = c.definitions.map { d -> Triple(d.id, d.category, d.params.map { Triple(it.id, it.default, it.options.size) }) }

    @Test fun everyLanguageTranslatesNearlyEveryEffectText() {
        for (language in AppLanguage.entries - AppLanguage.English) {
            val file = File(pack, "i18n/${language.tag}.json")
            val hint = "${language.tag}: run python3 tools/i18n_translate.py --only effects"
            assertTrue("$file is missing: $hint", file.isFile)
            val json = JSONObject(file.readText())
            val effects = json.getJSONObject("effects")
            val missing = ArrayList<String>()
            var total = 0
            fun need(present: Boolean, what: String) {
                total++
                if (!present) missing += what
            }
            for (e in english.definitions) {
                val t = effects.optJSONObject(e.key)
                need(t?.optString("name").orEmpty().isNotBlank(), "${e.key} name")
                if (e.description.isNotBlank()) need(t?.optString("description").orEmpty().isNotBlank(), "${e.key} description")
                for (p in e.params) {
                    val tp = t?.optJSONObject("params")?.optJSONObject(p.id)
                    need(tp?.optString("label").orEmpty().isNotBlank(), "${e.key}.${p.id} label")
                    if (p.options.isNotEmpty()) need(tp?.optJSONArray("options")?.length() == p.options.size, "${e.key}.${p.id} options")
                }
                for (preset in e.presets) need(t?.optJSONObject("presets")?.optString(preset.slug).orEmpty().isNotBlank(), "${e.key} preset ${preset.slug}")
            }
            val names = json.getJSONObject("categories")
            for (key in EffectCatalog.Categories.keys + "new" + "ai") need(names.optString(key).isNotBlank(), "category $key")
            assertTrue("${language.tag} misses ${missing.size} of $total effect texts ($hint): ${missing.take(20)}", missing.size <= total * 0.05)
            val local = EffectCatalog.parse(language.tag) { File(pack, it).inputStream() }
            assertEquals(shape(english), shape(local))
            assertEquals(english.categories.map { it.first }, local.categories.map { it.first })
        }
    }

    @Test fun missingTranslationsFallBackToEnglish() {
        assertEquals(english.definitions, EffectCatalog.parse("xx") { File(pack, it).inputStream() }.definitions)
        val glitch = english.find("absent.glitch")!!
        val partial = english.translated(JSONObject("""{"categories":{"blur":"B"},"effects":{"glitch":{"name":"G","params":{"intensity":{"label":"I"}}}}}"""))
        val local = partial.find("absent.glitch")!!
        assertEquals("G", local.name)
        assertEquals(glitch.description, local.description)
        assertEquals(listOf("I") + glitch.params.drop(1).map { it.label }, local.params.map { it.label })
        assertEquals("B", partial.categories.first { it.first == "blur" }.second)
        assertEquals(english.categories.first { it.first == "color" }, partial.categories.first { it.first == "color" })
        assertEquals(english.definitions.filter { it.key != "glitch" }, partial.definitions.filter { it.key != "glitch" })
        val choice = english.definitions.flatMap { d -> d.params.filter { it.options.isNotEmpty() }.map { d to it } }.first()
        val wrong = english.translated(JSONObject().put("effects", JSONObject().put(choice.first.key,
            JSONObject().put("params", JSONObject().put(choice.second.id, JSONObject().put("options", org.json.JSONArray().put("only one")))))))
        assertEquals(choice.second.options, wrong.find(choice.first.id)!!.params.first { it.id == choice.second.id }.options)
        assertNotEquals(0, choice.second.options.size)
    }

    @Test fun presetNamesTranslateButKeepTheirThumbnails() {
        val typing = english.find("absent.textprogress")!!
        val local = english.translated(JSONObject("""{"effects":{"textprogress":{"presets":{"typewriter":"T"}}}}""")).find("absent.textprogress")!!
        assertEquals(listOf("T") + typing.presets.drop(1).map { it.name }, local.presets.map { it.name })
        assertEquals(typing.presets.map { typing.thumbnail(it) }, local.presets.map { local.thumbnail(it) })
        assertEquals(typing.presets.map { it.keys }, local.presets.map { it.keys })
    }
}
