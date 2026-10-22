package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class GlowMigrationTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun scene(effect: String) = Scene.parse(
        """<scene title="g" width="720" height="720" fps="30" totalTime="2000" bgcolor="#ff000000">
          <shape id="1" label="c" startTime="0" endTime="2000" fillType="color" s=".circle">$effect
          <property name="size" type="vec2" value="100,100" /></shape></scene>""",
    )

    private fun glow(scene: Scene): XmlNode = scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.value(name: String) = children.single { it["name"] == name }["value"]!!

    @Test fun alightGlowImportsWithItsSettings() {
        val result = migration.migrate(scene("""<effect id="com.alightcreative.glow" locallyApplied="true">
            <property name="radius" type="float" value="120.000000" /><property name="alpha" type="float" value="0.600000" />
            <property name="hardness" type="float" value="0.300000" /><property name="color" type="color" value="#ff40ff80" />
            <property name="blendMode" type="int" value="1" /></effect>"""))
        val g = glow(result.scene)
        assertEquals("absent.glow", g["id"])
        assertEquals(120f, g.value("radius").toFloat(), 1e-4f)
        assertEquals(0.6f, g.value("alpha").toFloat(), 1e-4f)
        assertEquals(0.3f, g.value("hardness").toFloat(), 1e-4f)
        assertEquals("#ff40ff80", g.value("color"))
        assertEquals(1f, g.value("blendMode").toFloat(), 1e-4f)
        assertEquals(emptySet<String>(), result.dropped)
    }

    @Test fun aGlowSavedBy300BecomesTheNewGlow() {
        val result = migration.migrate(scene("""<effect id="absent.glow" hidden="false">
            <property name="strength" type="float" value="20" /><property name="intensity" type="float" value="1.5" /></effect>"""))
        val g = glow(result.scene)
        assertEquals("absent.glow", g["id"])
        assertEquals(49f, g.value("radius").toFloat(), 1e-3f)
        assertEquals(1f, g.value("alpha").toFloat(), 1e-4f)
        assertEquals(0f, g.value("hardness").toFloat(), 1e-4f)
        assertEquals("#ffffffff", g.value("color"))
        assertEquals(2f, g.value("blendMode").toFloat(), 1e-4f)
        assertFalse(migration.migrate(result.scene).changed)
        assertEquals(result.scene.node.toXml(), migration.migrate(result.scene).scene.node.toXml())
    }
}
