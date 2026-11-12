package app.absentmotion.ui.editor.effects

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SolidEffectBlockTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }

    @Test fun threeDEffectsAreOffForModelsOnly() {
        val model = Layer(XmlNode("model", linkedMapOf("id" to "1", "src" to "file:/m.glb")))
        val text = Layer(XmlNode("text", linkedMapOf("id" to "2")))
        assertEquals("3d", blockedCategory(model))
        assertNull(blockedCategory(text))
        assertNull(blockedCategory(null))
        val cube = catalog.find("absent.cube")!!
        val extrude = catalog.find("absent.rasterextrude")!!
        val glow = catalog.find("absent.glow")!!
        assertFalse(cube.usable(blockedCategory(model)))
        assertFalse(extrude.usable(blockedCategory(model)))
        assertTrue(glow.usable(blockedCategory(model)))
        assertTrue(cube.usable(blockedCategory(text)))
        assertTrue(extrude.usable(blockedCategory(text)))
    }
}
