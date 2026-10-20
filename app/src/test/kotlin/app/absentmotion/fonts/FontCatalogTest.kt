package app.absentmotion.fonts

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontCatalogTest {
    private val entries = FontCatalog.entries
    private val folder = TestData.project.resolve("app/src/main/assets/fonts")
    private val bundled = entries.filter { it.bundled }
    private val onDisk: Set<String> = folder.listFiles { f -> f.isDirectory }.orEmpty().flatMap { dir ->
        dir.listFiles { f -> f.name.endsWith(".ttf") }.orEmpty().map { "${dir.name}/${it.name}" }
    }.toSet()

    @Test fun fiftyBuiltInAndOneHundredDownloadableFamilies() {
        assertEquals(50, bundled.map { it.name.family }.distinct().size)
        assertEquals(100, entries.filterNot { it.bundled }.map { it.name.family }.distinct().size)
        assertEquals(150, entries.map { it.name.family }.distinct().size)
    }

    @Test fun noFamilyWeightItalicTwice() {
        val keys = entries.map { Triple(it.name.family, it.name.weight, it.name.italic) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun everyCategoryAndScriptHasAName() {
        assertEquals(setOf(1, 2, 3, 4, 5), FontCatalog.categories.keys)
        assertTrue(entries.all { it.category in FontCatalog.categories })
        assertTrue(entries.all { it.languages.isNotEmpty() && FontCatalog.languages.keys.containsAll(it.languages) })
        assertTrue(FontCatalog.categories.values.all { it.isNotBlank() } && FontCatalog.languages.values.all { it.isNotBlank() })
    }

    @Test fun weightsAreWholeHundredsAndTheDefaultFontIsBuiltIn() {
        assertTrue(entries.all { it.name.source == "googlefonts" && it.name.weight in 100..900 && it.name.weight % 100 == 0 })
        assertTrue(entries.any { it.name == FontName.Default && it.bundled })
    }

    @Test fun savedFontReferencesKeepTheirShape() {
        assertEquals("googlefonts?name=Roboto&weight=400", FontName.Default.xml)
        assertEquals("googlefonts?name=Roboto&weight=700&italic=1", FontName("googlefonts", "Roboto", 700, true).xml)
    }

    @Test fun everyBuiltInVariantHasAFileAndEveryFamilyHasALicence() {
        assertTrue(onDisk.isNotEmpty())
        bundled.forEach { entry ->
            assertTrue(entry.name.xml, BundledFonts.pick(entry.name, onDisk) != null)
            val dir = File(folder, FontName.assetFolder(entry.name.family))
            assertTrue(entry.name.family + " licence", listOf("OFL.txt", "LICENSE.txt", "UFL.txt").any { File(dir, it).length() > 1000 })
        }
        assertEquals(bundled.map { FontName.assetFolder(it.name.family) }.toSet(), onDisk.map { it.substringBefore('/') }.toSet())
    }

    @Test fun everyBundledFontFileBelongsToABuiltInVariant() {
        val used = bundled.map { BundledFonts.pick(it.name, onDisk) }.toSet()
        assertEquals(onDisk, used)
    }

    @Test fun bundledFontsAreRealTrueTypeFiles() {
        onDisk.forEach { name ->
            val head = File(folder, name).inputStream().use { it.readNBytes(4) }
            assertTrue(name, head.contentEquals(byteArrayOf(0, 1, 0, 0)) || head.contentEquals("true".toByteArray()))
        }
    }

    @Test fun builtInFontsCoverEveryInterfaceLanguageAndCategory() {
        fun covering(language: String): Int {
            val id = FontCatalog.languages.entries.first { it.value == language }.key
            return bundled.filter { id in it.languages }.map { it.name.family }.distinct().size
        }
        assertEquals(50, covering("Latin"))
        assertTrue(covering("Latin Extended") >= 45)
        assertTrue(covering("Vietnamese") >= 25 && covering("Cyrillic") >= 20)
        assertTrue(covering("Devanagari") >= 2 && covering("Thai") >= 2 && covering("Arabic") >= 2)
        assertEquals(setOf(1, 2, 3, 4, 5), bundled.map { it.category }.toSet())
        val popular = listOf("Montserrat", "Poppins", "Bebas Neue", "Anton", "Oswald", "Roboto", "Inter", "Playfair Display", "Lobster", "Pacifico",
            "Dancing Script", "Caveat", "Permanent Marker", "Bangers", "Press Start 2P", "Kanit", "Prompt", "Cairo", "Tajawal", "Hind")
        assertTrue(bundled.map { it.name.family }.containsAll(popular))
    }

    @Test fun builtInLookupPrefersTheExactFileAndFallsBackToTheNearestWeight() {
        val names = setOf("Hind/Hind-Regular.ttf", "Hind/Hind-Bold.ttf", "Lora/Lora-Variable.ttf", "Anton/Anton-Regular.ttf")
        fun pick(family: String, weight: Int, italic: Boolean = false, exact: Boolean = true) =
            BundledFonts.pick(FontName("googlefonts", family, weight, italic), names, exact)
        assertEquals("Hind/Hind-Bold.ttf", pick("Hind", 700))
        assertNull(pick("Hind", 500))
        assertEquals("Hind/Hind-Regular.ttf", pick("Hind", 500, exact = false))
        assertEquals("Hind/Hind-Bold.ttf", pick("Hind", 600, exact = false))
        assertEquals("Hind/Hind-Bold.ttf", pick("Hind", 700, italic = true, exact = false))
        assertEquals("Lora/Lora-Variable.ttf", pick("Lora", 600))
        assertEquals("Anton/Anton-Regular.ttf", pick("Anton", 900, exact = false))
        assertNull(pick("Merriweather", 400, exact = false))
        assertNull(BundledFonts.pick(FontName("imported", "Hind-Regular.ttf"), names, false))
    }

    @Test fun fontsRemovedFromTheCatalogueStillParseAndStayOutsideIt() {
        val old = FontName("googlefonts", "Merriweather", 300, true)
        assertEquals("googlefonts?name=Merriweather&weight=300&italic=1", old.xml)
        assertTrue(entries.none { it.name.family == "Merriweather" && it.bundled })
        assertNull(BundledFonts.pick(old, onDisk, exact = false))
        assertEquals("PressStart2P", FontName.assetFolder("Press Start 2P"))
    }

    @Test fun downloadableFamiliesOfferOneOrTwoUprightWeights() {
        entries.filterNot { it.bundled }.groupBy { it.name.family }.forEach { (family, variants) ->
            assertTrue(family, variants.size in 1..2 && variants.none { it.name.italic })
        }
    }
}
