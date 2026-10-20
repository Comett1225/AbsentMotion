package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class BendMigrationTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun scene(effect: String) = Scene.parse(
        """<scene title="b" width="720" height="720" fps="30" totalTime="2000" bgcolor="#ff000000">
          <shape id="1" label="r" startTime="0" endTime="2000" fillType="color" s=".rect">$effect
          <property name="size" type="vec2" value="60,240" /></shape></scene>""",
    )

    private fun bend(scene: Scene): XmlNode = scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.value(name: String) = children.single { it["name"] == name }["value"]!!.toFloat()

    @Test fun aBendSavedBefore31KeepsTheSideItBendsTo() {
        val result = migration.migrate(scene("""<effect id="absent.bend" hidden="false">
            <property name="origin" type="float" value="3" /><property name="angle" type="float" value="45" />
            <property name="start" type="float" value="0.25" /><property name="before" type="float" value="2" />
            <property name="tuneHead" type="float" value="0.75" /><property name="tuneTail" type="float" value="0.25" /></effect>"""))
        val b = bend(result.scene)
        assertEquals("absent.bend", b["id"])
        assertEquals(listOf("origin", "amount", "start", "before", "tuneHead", "tuneTail"), b.children.map { it["name"] })
        assertEquals(-45f, b.value("amount"), 1e-4f)
        assertEquals(0.25f, b.value("start"), 1e-4f)
        assertEquals(2f, b.value("before"), 1e-4f)
        assertFalse(migration.migrate(result.scene).changed)
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }

    @Test fun keyframedAnglesAreTurnedAroundToo() {
        val result = migration.migrate(scene("""<effect id="absent.bend" hidden="false"><property name="angle" type="float">
            <kf t="0.000000" v="10.000000" /><kf t="1.000000" v="-120.000000" e="cubicBezier 0.42 0 0.58 1" /></property></effect>"""))
        val keys = bend(result.scene).children.single { it["name"] == "amount" }.keyframes
        assertEquals(listOf(-10f, 120f), keys.map { it.v.toFloat() })
        assertEquals("cubicBezier 0.42 0 0.58 1", keys[1].e)
    }

    @Test fun alightBendImportsWithItsSettings() {
        val result = migration.migrate(scene("""<effect id="com.alightcreative.effects.bendwarp" locallyApplied="true">
            <property name="originType" type="int" value="1" /><property name="amount" type="float" value="90.000000" />
            <property name="offset" type="float" value="0.400000" /><property name="oppositeType" type="int" value="1" />
            <property name="highLimit" type="float" value="0.600000" /><property name="lowLimit" type="float" value="0.300000" /></effect>"""))
        val b = bend(result.scene)
        assertEquals("absent.bend", b["id"])
        assertEquals(1f, b.value("origin"), 1e-4f)
        assertEquals(90f, b.value("amount"), 1e-4f)
        assertEquals(0.4f, b.value("start"), 1e-4f)
        assertEquals(1f, b.value("before"), 1e-4f)
        assertEquals(0.6f, b.value("tuneHead"), 1e-4f)
        assertEquals(0.3f, b.value("tuneTail"), 1e-4f)
        assertEquals(emptySet<String>(), result.dropped)
        assertEquals(mapOf("bendwarp → Bend" to 1), result.converted)
    }
}
