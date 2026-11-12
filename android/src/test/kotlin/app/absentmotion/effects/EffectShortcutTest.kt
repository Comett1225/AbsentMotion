package app.absentmotion.effects

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.ui.editor.effects.blockedCategory
import app.absentmotion.ui.editor.effects.usable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectShortcutTest {
    private val pack = File("../shared/assets/effects")
    private fun catalog(language: String = "en") = EffectCatalog.parse(language) { File(pack, it).inputStream() }

    @Test fun threeDTextIsATileInThe3dCategoryThatAddsRasterExtrude() {
        val catalog = catalog()
        val tile = catalog.listed("3d").single { it.shortcut == "text3d" }
        assertEquals("3D Text", tile.name)
        assertEquals("absent.rasterextrude", tile.id)
        assertEquals("absent.rasterextrude", tile.instance()["id"])
        assertEquals("thumb/text3d.webp", tile.thumbnail)
        assertTrue(File(pack, tile.thumbnail).isFile)
        assertTrue(catalog.listed("3d").any { it.id == "absent.rasterextrude" && it.shortcut == null })
        assertEquals(1, catalog.definitions.count { it.id == "absent.rasterextrude" })
        assertEquals("absent.rasterextrude", catalog.find("absent.rasterextrude")!!.id)
        assertEquals(null, catalog.find("absent.rasterextrude")!!.shortcut)
        assertTrue(catalog.search("3D text").first().shortcut == "text3d")
        assertTrue(catalog.listed("new").any { it.shortcut == "text3d" })
        assertTrue(catalog.listed("text").any { it.shortcut == "text3d" })
        assertTrue(catalog.listed("text").none { it.id == "absent.rasterextrude" && it.shortcut == null })
        assertEquals(catalog.listed("3d").size, catalog.listed("3d").map { it.shortcut ?: it.id }.toSet().size)
    }

    @Test fun threeDTextIsTranslatedAndOffForModels() {
        assertEquals("Texte 3D", catalog("fr").listed("3d").single { it.shortcut == "text3d" }.name)
        val model = Layer(XmlNode("model", linkedMapOf("id" to "1")))
        assertFalse(catalog().listed("3d").single { it.shortcut == "text3d" }.usable(blockedCategory(model)))
    }
}
