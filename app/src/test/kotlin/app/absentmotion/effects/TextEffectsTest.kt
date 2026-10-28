package app.absentmotion.effects

import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextEffectsTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }

    private fun property(effect: app.absentmotion.model.XmlNode, name: String) = effect.children.first { it["name"] == name }

    @Test fun theTextCategoryHoldsTheTextAnimators() {
        assertEquals("Text", EffectCatalog.Categories["text"])
        assertEquals(setOf("textprogress", "texttransform", "textrandomizer", "countupdown", "timecode", "textpath"), catalog.listed("text").map { it.key }.toSet())
        assertTrue(catalog.listed("text").all { it.native && it.sksl == null && it.since == if (it.key == "textpath") "4.2.0" else "3.0.0" })
        assertTrue(listOf("absent.drawingprogress", "absent.stroketaper").all { catalog.find(it)?.native == true })
    }

    @Test fun theOneTapPresetsAreThere() {
        val names = catalog.definitions.flatMap { d -> d.presets.map { it.name } }.toSet()
        assertTrue(names.containsAll(listOf("Typewriter", "Fade In by Word", "Slide Up by Letter", "Pop In", "Scramble", "Wave")))
        for (effect in catalog.definitions) for (preset in effect.presets) {
            assertTrue("${effect.key}: ${preset.name}", (preset.values.keys + preset.keys.keys).all { id -> effect.params.any { it.id == id } })
            assertTrue("${effect.key}: ${preset.name} has a thumbnail", File(pack, effect.thumbnail(preset)).isFile)
        }
    }

    @Test fun presetKeyframesAreSecondsFromTheLayerStart() {
        val progress = catalog.find("absent.textprogress")!!
        val typewriter = progress.presets.first { it.name == "Typewriter" }
        val long = progress.instance(typewriter, 4000)
        assertEquals(listOf(0f to 0f, 0.5f to 1f), property(long, "progress").keyframes.map { it.t to it.v.toFloat() })
        assertNull(property(long, "progress")["value"])
        assertEquals(0f, property(long, "appearance")["value"]!!.toFloat())
        assertEquals(progress.instance().children.first { it["name"] == "softness" }, property(long, "softness"))
        val short = progress.instance(typewriter, 1000)
        assertEquals("a preset longer than the layer is squeezed into it", listOf(0f, 1f), property(short, "progress").keyframes.map { it.t })
        assertEquals(progress.instance(), progress.instance(null, 4000))
        val count = catalog.find("absent.countupdown")!!
        val eased = property(count.instance(count.presets.single(), 4000), "multiply").keyframes.last()
        assertEquals("cubicBezier 0 0 0.58 1", eased.e)
        val wave = catalog.find("absent.texttransform")!!.let { it.instance(it.presets.first { p -> p.name == "Wave" }, 3000) }
        assertEquals(listOf(0f, -40f), property(wave, "position")["value"]!!.split(',').map { it.toFloat() })
    }

    @Test fun textSettingsKeepTheirText() {
        val custom = catalog.find("absent.textrandomizer")!!.params.first { it.id == "characters" }
        assertEquals("text" to "string", custom.kind to custom.type)
        assertEquals("01", custom.default)
        assertEquals("01", property(catalog.find("absent.textrandomizer")!!.instance(), "characters")["value"])
        val percent = catalog.find("absent.textprogress")!!.params.first { it.id == "progress" }
        assertTrue(percent.percent && percent.min == 0f && percent.max == 1f)
        assertEquals("start", percent.after)
        val anchor = catalog.find("absent.texttransform")!!.params.first { it.id == "anchor" }
        assertEquals(listOf("Component", "Layer"), anchor.options)
        assertEquals("0.000000", anchor.default)
    }
}
