package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File

class EffectSearchTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }

    @Test fun namesComeFirstThenDescriptions() {
        assertEquals("Echo Keyframes", catalog.search("echo").first().name)
        assertEquals("Echo Keyframes", catalog.search("ECHO keyframes").single().name)
        val copies = catalog.search("copies").map { it.name }
        assertTrue(copies.toString(), "Echo Keyframes" in copies && "Repeat" in copies && "Grid Repeat" in copies)
        val glow = catalog.search("glow")
        assertTrue(glow.takeWhile { "glow" in it.name.lowercase() }.size == glow.count { "glow" in it.name.lowercase() })
        assertEquals(catalog.definitions.size + catalog.definitions.sumOf { it.shortcuts.size }, catalog.search("  ").size)
    }

    @Test fun otherNamesFindTheEffectInEveryLanguage() {
        val renamed = EffectDefinition.parse(JSONObject("""{"id":"timequant","name":"Time Quantization","category":"move","native":true,"aliases":["Frame Rate"],
            "shortcuts":[{"key":"steps","name":"Steps"}]}"""))
        val english = EffectCatalog(listOf(renamed))
        assertEquals(listOf("Time Quantization"), english.search("frame rate").map { it.name })
        val german = english.translated(JSONObject("""{"effects":{"timequant":{"name":"Zeitquantisierung","aliases":["Bildrate"]}}}"""))
        for (query in listOf("bildrate", "Frame Rate", "zeitquant")) assertEquals(query, listOf("Zeitquantisierung"), german.search(query).map { it.name })
        assertTrue(german.definitions.single().shortcutTiles().single().aliases.isEmpty())
        val de = EffectCatalog.parse("de") { File("../shared/assets/effects", it).inputStream() }
        for (query in listOf("Gesichtsbewegung", "face motion", "Gesichtsverfolgung")) assertEquals(query, "facetrack", de.search(query).first().key)
    }

    @Test fun renamedEffectsAreFoundByTheirOldNames() {
        val de = EffectCatalog.parse("de") { File("../shared/assets/effects", it).inputStream() }
        val renamed = listOf(
            Triple("colorwheels", "Lift Gamma Gain", "Lift Gamma Gain"), Triple("splittone", "Split Tone", "Split-Toning"),
            Triple("exposure", "Exposure", "Belichtung"), Triple("highlightshadow", "Highlights & Shadows", "Lichter und Schatten"),
            Triple("edgedetect", "Edge Detect", "Kantenerkennung"), Triple("halftone", "Halftone", "Halbton"),
            Triple("timequant", "Frame Rate", "Bildrate"), Triple("brightnesscontrast", "Brightness & Contrast", "Helligkeit und Kontrast"),
            Triple("fourcolor", "Four-Color Gradient", "Vierfarbverlauf"), Triple("reorient360", "360° Reorient Sphere", "360°-Kugel ausrichten"),
            Triple("viewer360", "360° Viewer", "360°-Viewer"),
        )
        for ((key, english, german) in renamed) {
            assertTrue(key, catalog.definitions.single { it.key == key }.name != english)
            assertEquals(english, key, catalog.search(english).first().key)
            assertEquals(german, key, de.search(german).first().key)
            assertEquals("de: $english", key, de.search(english).first().key)
        }
        assertEquals("Time Quantization", catalog.search("frame rate").first().name)
        assertEquals("Zeitquantisierung", de.search("bildrate").first().name)
    }

    @Test fun everyEffectIsFoundByItsEnglishNameInEveryLanguage() {
        val languages = File("../shared/assets/effects/i18n").list()!!.map { it.removeSuffix(".json") }
        assertTrue(languages.size >= 24)
        for (language in languages) {
            val translated = EffectCatalog.parse(language) { File("../shared/assets/effects", it).inputStream() }
            for (effect in catalog.definitions) assertEquals("$language: ${effect.name}", effect.key, translated.search(effect.name).first().key)
        }
    }

    @Test fun effectsAlsoShowInTheCategoryAlightMotionUses() {
        val color = catalog.listed("color").map { it.key }
        val other = catalog.listed("other").map { it.key }
        for (key in listOf("lightglow", "softglow", "rays")) assertTrue(key, key in color && key in catalog.listed("stylize").map { it.key })
        for (key in listOf("timequant", "parenthelper", "channelremap", "backdrop")) assertTrue(key, key in other)
        assertTrue("sharpen" in catalog.listed("blur").map { it.key } && "pagecurl" in catalog.listed("3d").map { it.key })
        assertEquals(1, catalog.search("Light Glow").count { it.key == "lightglow" })
    }
}
