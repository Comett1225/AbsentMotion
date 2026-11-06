package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ElectricEdgesImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun imported(properties: String): XmlNode {
        val scene = Scene.parse(
            """<scene title="e" width="1080" height="1080" fps="30" totalTime="2000" bgcolor="#ff000000" am="com.alightcreative.motion/5.0.273">
              <shape id="1" label="r" startTime="0" endTime="2000" fillType="color" s=".rect"><property name="size" type="vec2" value="100,100" />
              <effect id="com.alightcreative.effects.electricedges" locallyApplied="false">$properties</effect></shape></scene>""",
        )
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.prop(name: String) = children.single { it.name == "property" && it["name"] == name }

    @Test fun thicknessAndDistanceKeepAlightValues() {
        val effect = imported(
            """<property name="thickness" type="float" value="1.000000" /><property name="distance" type="float" value="0.500000" />
               <property name="strength" type="float" value="0.420000" />""",
        )
        assertEquals("absent.electricedges", effect["id"])
        assertEquals(1f, effect.prop("thickness")["value"]!!.toFloat(), 1e-6f)
        assertEquals(30f, effect.prop("blur")["value"]!!.toFloat(), 1e-4f)
        assertEquals(0.42f, effect.prop("strength")["value"]!!.toFloat(), 1e-6f)
        assertEquals(1f, imported("""<property name="thickness" type="float" value="1.500000" />""").prop("thickness")["value"]!!.toFloat(), 1e-6f)
    }

    @Test fun missingStrengthGetsAlightsHiddenDefault() {
        val effect = imported("""<property name="thickness" type="float" value="0.591000" />""")
        assertEquals(0.42f, effect.prop("strength")["value"]!!.toFloat(), 1e-6f)
    }

    @Test fun seedKeyframesKeepTheirValuesTimesAndEasing() {
        val effect = imported(
            """<property name="seed" type="float"><kf t="0.000000" v="0.000000" /><kf t="0.400000" v="2.500000" e="cubicBezier 0.42 0 0.58 1" />
               <kf t="1.000000" v="5.000000" e="step 0.5 0" /></property><property name="evolution" type="float" value="0.000000" />""",
        )
        val keys = effect.prop("seed").keyframes
        assertEquals(listOf(0f, 0.4f, 1f), keys.map { it.t })
        assertEquals(listOf(0f, 2.5f, 5f), keys.map { it.v.toFloat() })
        assertEquals(listOf(null, "cubicBezier 0.42 0 0.58 1", "step 0.5 0"), keys.map { it.e })
        assertNull(effect.prop("seed")["value"])
    }

    @Test fun evolutionKeyframesAreNotRescaled() {
        val effect = imported(
            """<property name="evolution" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="10.000000" e="cubicBezier 0.42 0 0.58 1" /></property>""",
        )
        val keys = effect.prop("evolution").keyframes
        assertEquals(listOf(0f, 10f), keys.map { it.v.toFloat() })
        assertEquals(listOf(null, "cubicBezier 0.42 0 0.58 1"), keys.map { it.e })
        assertEquals(-12.5f, imported("""<property name="evolution" type="float" value="-12.5" />""").prop("evolution")["value"]!!.toFloat(), 1e-6f)
    }

    @Test fun seedStaysInsideAlightsRange() {
        val effect = imported("""<property name="seed" type="float"><kf t="0" v="-1.0" /><kf t="1" v="7.25" /></property>""")
        assertEquals(listOf(0f, 5f), effect.prop("seed").keyframes.map { it.v.toFloat() })
        assertEquals(3.75f, imported("""<property name="seed" type="float" value="3.75" />""").prop("seed")["value"]!!.toFloat(), 1e-6f)
    }

    @Test fun projectsSavedBefore50KeepTheOldElectricEdges() {
        val old = Scene.parse(
            """<scene title="o" width="1080" height="1080" fps="30" totalTime="2000" bgcolor="#ff000000" am="app.absentmotion">
              <shape id="1" label="r" startTime="0" endTime="2000" fillType="color" s=".rect"><property name="size" type="vec2" value="100,100" />
              <effect id="absent.electricedges" hidden="false"><property name="blur" type="float" value="30.000000" />
              <property name="thickness" type="float" value="1.000000" /><property name="seed" type="float" value="2.000000" /></effect></shape></scene>""",
        )
        assertEquals(old.node, migration.migrate(old).scene.node)
        val strength = catalog.definitions.single { it.id == "absent.electricedges" }.params.single { it.id == "strength" }
        assertEquals(-1f, strength.missingDefault.toFloat(), 0f)
        assertEquals(0.42f, strength.default.toFloat(), 1e-6f)
    }
}
