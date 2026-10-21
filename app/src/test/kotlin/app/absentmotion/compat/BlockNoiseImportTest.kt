package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BlockNoiseImportTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    @Test fun alightBlockNoiseImportsWithItsSettings() {
        val scene = Scene.parse(
            """<scene title="b" width="720" height="720" fps="30" totalTime="2000" bgcolor="#ff000000">
              <shape id="1" label="r" startTime="0" endTime="2000" fillType="color" s=".rect">
              <effect id="com.alightcreative.effects.blocknoise2" locallyApplied="true">
                <property name="size" type="float" value="32.700001" /><property name="stretch" type="vec2" value="10.000000,1.000000" />
                <property name="amount" type="float" value="0.400000" /><property name="mono" type="bool" value="true" />
                <property name="freeze" type="bool" value="true" /><property name="extremes" type="bool" value="false" />
                <property name="overshoot" type="float" value="1.500000" /><property name="seed" type="float" value="2.000000" />
              </effect><property name="size" type="vec2" value="100,100" /></shape></scene>""",
        )
        val result = migration.migrate(scene)
        val effect = result.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        fun value(name: String) = effect.children.single { it["name"] == name }["value"]!!
        assertEquals("absent.blocknoise", effect["id"])
        assertEquals(32.7f, value("blockSize").toFloat(), 1e-3f)
        assertEquals(listOf(10f, 1f), value("stretch").split(',').map { it.trim().toFloat() })
        assertEquals(0.4f, value("strength").toFloat(), 1e-4f)
        assertEquals("true", value("mono"))
        assertEquals("true", value("freeze"))
        assertEquals(1.5f, value("overshoot").toFloat(), 1e-4f)
        assertEquals(2f, value("seed").toFloat(), 1e-4f)
        assertEquals(1f, value("blending").toFloat(), 1e-4f)
        assertEquals(emptySet<String>(), result.dropped)
    }
}
