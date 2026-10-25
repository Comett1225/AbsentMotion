package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightEditImportTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private val edit = """
        <scene title="e" width="1080" height="1080" fps="60" totalTime="2000" bgcolor="#00000000">
          <shape id="1" label="clip" startTime="0" endTime="2000" fillType="color" s=".rect">
            <effect id="com.alightcreative.effects.ominodiff" locallyApplied="true">
              <property name="angle" type="float"><kf t="0.5" v="0.000000" /><kf t="0.6" v="48.500000" /></property>
              <property name="strength" type="float"><kf t="0.5" v="0.010000" /><kf t="0.55" v="1.000000" e="cubicBezier 0.0 0.6383378 1.0 1.0" /></property>
              <property name="steps" type="float" value="35.000000" /><property name="errorWeight" type="float" value="0.750000" />
              <property name="errorFalloff" type="float" value="0.000000" /><property name="palette" type="int" value="2" />
              <property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" />
              <property name="color1" type="color" value="#ff000000" /><property name="color2" type="color" value="#ff5555ff" />
              <property name="color3" type="color" value="#ff55ff55" /><property name="color4" type="color" value="#ff55ffff" />
              <property name="color5" type="color" value="#ffff5555" /><property name="color6" type="color" value="#ffff55ff" />
            </effect>
            <effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
              <property name="tune" type="float" value="2.000000" /><property name="usePos" type="bool" value="true" />
              <property name="useScale" type="bool" value="false" /><property name="useAngle" type="bool" value="true" />
            </effect>
            <effect id="com.alightcreative.effects.tile" locallyApplied="true">
              <property name="scale" type="float" value="1.000000" /><property name="phase" type="float" value="0.250000" />
              <property name="mirror" type="bool" value="true" /><property name="vertoffs" type="bool" value="false" />
              <property name="angle" type="float" value="0.000000" />
            </effect>
            <effect id="com.alightcreative.effects.shake2" locallyApplied="true">
              <property name="mag" type="float"><kf t="0.015025" v="343.000000" /><kf t="0.459098" v="19.000000" e="cubicBezier 0.0 0.33926946 1.0 1.0" /></property>
              <property name="freq" type="float" value="2.000000" /><property name="evolution" type="float" value="0.000000" />
              <property name="seed" type="float" value="0.000000" /><property name="angle" type="float" value="45.000000" />
              <property name="slack" type="float" value="0.250000" /><property name="zshake" type="float" value="0.000000" />
            </effect>
            <effect id="com.alightcreative.effects.softglow" locallyApplied="true">
              <property name="strength" type="float" value="0.100000" />
              <property name="brightness" type="float"><kf t="0.764608" v="0.000000" e="cubicBezier 0.0 0.65810263 0.6769108 1.0" /><kf t="0.015025" v="1.310000" /></property>
              <property name="contrast" type="float" value="0.200000" /><property name="highlights" type="float" value="0.500000" />
              <property name="color" type="color" value="#ffff5566" /><property name="blend" type="float" value="0.250000" />
              <property name="alpha" type="float" value="1.000000" /><property name="outsideAlpha" type="float" value="0.000000" />
            </effect>
            <property name="size" type="vec2" value="540,540" />
          </shape>
          <shape id="2" label="adjust" startTime="0" endTime="2000" fillType="color" s=".roundrect">
            <fillColor value="#ff5f87f2" />
            <effect id="com.alightcreative.effects.lift" locallyApplied="true"><property name="fill" type="float" value="0.000000" /></effect>
            <effect id="com.alightcreative.effects.noise3" locallyApplied="true">
              <property name="amount" type="float" value="0.205000" /><property name="mono" type="bool" value="true" />
              <property name="freeze" type="bool" value="false" /><property name="extremes" type="bool" value="true" />
              <property name="overshoot" type="float" value="1.995000" />
            </effect>
            <effect id="com.alightcreative.effects.threshold" locallyApplied="true">
              <property name="threshold" type="float" value="0.500000" /><property name="feather" type="float" value="0.400000" />
              <property name="invert" type="bool" value="false" /><property name="blendMode" type="int" value="1" />
            </effect>
            <property name="size" type="vec2" value="598,598" />
          </shape>
        </scene>"""

    private fun effects(scene: Scene): List<XmlNode> = scene.node.children.filter { it.name == "shape" }.flatMap { s -> s.children.filter { it.name == "effect" } }

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it.name == "property" && it["name"] == name }?.let { it["value"] }

    private fun XmlNode.keys(name: String): List<XmlNode> = children.first { it.name == "property" && it["name"] == name }.children.filter { it.name == "kf" }

    @Test fun everyEffectOfATypicalAlightEditImportsAndRuns() {
        val result = migration.migrate(Scene.parse(edit))
        val ids = effects(result.scene).map { it["id"]!! }
        assertEquals(listOf("absent.ominodiffusion", "absent.motionblur", "absent.tiles", "absent.autoshake", "absent.softglow", "absent.backdrop", "absent.noise", "absent.threshold"), ids)
        ids.forEach { assertNotNull(it, catalog.find(it)) }
        assertEquals(emptySet<String>(), result.dropped)
    }

    @Test fun settingsAndKeyframesCarryOver() {
        val e = effects(migration.migrate(Scene.parse(edit)).scene).associateBy { it["id"]!! }
        val omino = e.getValue("absent.ominodiffusion")
        assertEquals(listOf("0.000000", "48.500000"), omino.keys("angle").map { it["v"] })
        assertEquals("cubicBezier 0.0 0.6383378 1.0 1.0", omino.keys("strength")[1]["e"])
        assertEquals(0.75f, omino.value("weight")!!.toFloat(), 1e-6f)
        assertEquals(2f, omino.value("palette")!!.toFloat(), 1e-6f)
        assertEquals("#ff5555ff", omino.value("color2"))
        val blur = e.getValue("absent.motionblur")
        assertEquals(2f, blur.value("tune")!!.toFloat(), 1e-6f)
        assertEquals(listOf("true", "false", "true"), listOf("position", "scale", "rotation").map { blur.value(it) })
        val tiles = e.getValue("absent.tiles")
        assertEquals(1f, tiles.value("zoom")!!.toFloat(), 1e-6f)
        assertEquals(0.25f, tiles.value("offset")!!.toFloat(), 1e-6f)
        assertEquals("true", tiles.value("mirror"))
        assertEquals(1f, tiles.value("sizing")!!.toFloat(), 1e-6f)
        val noise = e.getValue("absent.noise")
        assertEquals(0.205f, noise.value("amount")!!.toFloat(), 1e-6f)
        assertEquals(listOf("true", "false", "true"), listOf("mono", "freeze", "extremes").map { noise.value(it) })
        assertEquals(1.995f, noise.value("overshoot")!!.toFloat(), 1e-6f)
    }

    @Test fun shakeGlowAndThresholdFollowAlightsBehaviour() {
        val e = effects(migration.migrate(Scene.parse(edit)).scene).associateBy { it["id"]!! }
        val shake = e.getValue("absent.autoshake")
        assertEquals(1f, shake.value("pattern")!!.toFloat(), 1e-6f)
        assertEquals(listOf("343.000000", "19.000000"), shake.keys("magnitude").map { it["v"] })
        val glow = e.getValue("absent.softglow")
        assertEquals(0.25f, glow.value("blend")!!.toFloat(), 1e-6f)
        assertEquals(24f, glow.value("blur")!!.toFloat(), 1e-4f)
        assertEquals(0.1f, e.getValue("absent.threshold").value("softness")!!.toFloat(), 1e-6f)
    }

    @Test fun ownShakesAndGlowsSavedBeforeKeepTheirLook() {
        val shake = catalog.find("absent.autoshake")!!.params.single { it.id == "pattern" }
        assertEquals("0.000000", shake.missingDefault)
        assertEquals("1.000000", shake.default)
        val blend = catalog.find("absent.softglow")!!.params.single { it.id == "blend" }
        assertEquals(1f, blend.missingDefault.toFloat(), 1e-6f)
        val old = Scene.parse("""<scene title="o" width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect">
            <effect id="absent.autoshake"><property name="magnitude" type="float" value="50" /></effect></shape></scene>""")
        val kept = effects(migration.migrate(old).scene).single()
        assertTrue(kept.children.none { it["name"] == "pattern" })
    }
}
