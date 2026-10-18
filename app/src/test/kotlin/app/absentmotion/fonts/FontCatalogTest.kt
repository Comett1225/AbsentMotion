package app.absentmotion.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogTest {
    private val entries = FontCatalog.entries

    @Test fun hasAboutEightHundredEightyFamilies() {
        val families = entries.map { it.name.family }.distinct().size
        assertTrue("$families families", families in 800..1000)
        assertTrue(entries.size > families)
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

    @Test fun weightsAreWholeHundredsAndTheDefaultFontIsListed() {
        assertTrue(entries.all { it.name.source == "googlefonts" && it.name.weight in 100..900 && it.name.weight % 100 == 0 })
        assertTrue(entries.any { it.name == FontName.Default })
    }

    @Test fun savedFontReferencesKeepTheirShape() {
        assertEquals("googlefonts?name=Roboto&weight=400", FontName.Default.xml)
        assertEquals("googlefonts?name=Roboto&weight=700&italic=1", FontName("googlefonts", "Roboto", 700, true).xml)
    }
}
