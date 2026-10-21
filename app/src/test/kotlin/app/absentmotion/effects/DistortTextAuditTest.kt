package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DistortTextAuditTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun scene(effects: String) = Scene.parse("""
        <scene width="720" height="720" fps="30" totalTime="2000">
          <shape id="1" startTime="0" endTime="2000" s=".rect">$effects</shape>
        </scene>
    """.trimIndent())

    private fun effects(scene: Scene) = scene.node.children.single().children.filter { it.name == "effect" }
    private fun values(effect: XmlNode) = effect.children.associate { it["name"]!! to it["value"] }
    private fun defaults(key: String) = catalog.find("absent.$key")!!.params.associate { it.id to it.default }

    @Test fun correctedDistortionsPreserveOldValuesAndKeyframes() {
        val old = scene("""
            <effect id="absent.tiles"><property name="crop" type="float">
              <kf t="0" v="0.5"/><kf t="1" v="4" e="cubicBezier 0.42 0 1 1"/>
            </property><property name="mirror" type="bool" value="true"/></effect>
            <effect id="absent.squeeze"><property name="strength" type="float">
              <kf t="0" v="-2"/><kf t="1" v="2"/>
            </property></effect>
        """.trimIndent())
        val result = migration.migrate(old)
        val all = effects(result.scene)
        assertEquals("1.000000", values(all[0])["sizing"])
        assertEquals("true", values(all[0])["mirror"])
        val crop = all[0].children.first { it["name"] == "zoom" }.keyframes
        assertEquals(listOf("0.500000", "4.000000"), crop.map { it.v })
        assertEquals("cubicBezier 0.42 0 1 1", crop.last().e)
        assertEquals("1.000000", values(all[1])["shape"])
        assertEquals(listOf("-2.000000", "2.000000"), all[1].children.first { it["name"] == "amount" }.keyframes.map { it.v })
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
        assertEquals("0.000000", defaults("tiles")["sizing"])
        assertEquals("0.000000", defaults("squeeze")["shape"])
    }

    @Test fun importedTextRangesAndEvolutionAreRetained() {
        val result = migration.migrate(scene("""
            <effect id="com.alightcreative.effects.textprogress">
              <property name="start" type="float" value="0.25"/>
              <property name="end" type="float" value="0.75"/>
              <property name="cursor" type="int" value="0"/><property name="blink" type="bool" value="false"/>
            </effect>
            <effect id="com.alightcreative.effects.textrand">
              <property name="amount" type="float"><kf t="0" v="0"/><kf t="1" v="1"/></property>
              <property name="evo" type="float" value="2"/>
              <property name="start" type="float" value="0.25"/><property name="end" type="float" value="0.75"/>
              <property name="charset" type="int" value="0"/><property name="preserveSpace" type="bool" value="true"/>
            </effect>
        """.trimIndent()))
        val all = effects(result.scene)
        assertEquals("0.250000", values(all[0])["start"])
        assertEquals("0.750000", values(all[0])["progress"])
        assertEquals("0.000000", values(all[0])["appearance"])
        val random = values(all[1])
        assertEquals("2.000000", random["evolution"])
        assertEquals("0.250000", random["start"])
        assertEquals("0.750000", random["end"])
        assertEquals("0.000000", random["speed"])
        assertEquals("1.000000", random["charset"])
        assertEquals(listOf("0.000000", "1.000000"), all[1].children.first { it["name"] == "amount" }.keyframes.map { it.v })
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertEquals("0.000000", defaults("textrandomizer")["amount"])
        assertEquals("0.000000", defaults("textrandomizer")["speed"])
        assertEquals("1.000000", defaults("textrandomizer")["charset"])
    }

    @Test fun newlyAddedEffectsUseTheRecordedDefaults() {
        assertEquals("10.000000", defaults("stretch")["stretch"])
        assertEquals("#ffff5566", defaults("ominoglass")["color"])
        assertEquals("0.000000", defaults("polardisplacement")["angle"])
        val reach = catalog.find("absent.polardisplacement")!!.params.first { it.id == "reach" }
        assertEquals("Inset/Outset", reach.label)
        assertEquals(-1000f, reach.min, 0f)
        assertEquals(1000f, reach.max, 0f)
        val result = migration.migrate(scene("<effect id=\"com.alightcreative.effects.stretchsegment\"/>"))
        assertEquals("10.000000", values(effects(result.scene).single())["stretch"])
    }

    @Test fun importedMagnifierKeepsItsControls() {
        val result = migration.migrate(scene("""
            <effect id="com.alightcreative.effects.magnifybg">
              <property name="scale" type="float" value="3.7"/><property name="scaleMask" type="bool" value="true"/>
              <property name="sample" type="int" value="0"/>
            </effect>
        """.trimIndent()))
        val all = effects(result.scene)
        assertEquals("3.700000", values(all[0])["scale"])
        assertEquals("true", values(all[0])["magnify"])
        assertEquals("0.000000", values(all[0])["sampling"])
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
    }
}
