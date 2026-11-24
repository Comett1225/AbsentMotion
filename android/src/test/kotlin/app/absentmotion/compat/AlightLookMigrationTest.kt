package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightLookMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { pack.resolve(it).inputStream() }
    private val migration = EffectMigration.parse(pack.resolve("foreign.json").readText(), catalog)

    private fun migrate(effect: String, clipped: Boolean = false): XmlNode {
        val scene = Scene.parse("""<scene width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect" clippingMask="$clipped"><property name="size" type="vec2" value="110,82.5" />$effect</shape></scene>""")
        val result = migration.migrate(scene)
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertFalse(migration.migrate(result.scene).changed)
        return result.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.number(name: String) = children.single { it["name"] == name }["value"]!!.toFloat()

    @Test fun mirrorKeepsItsBlendOffsetAndDirection() {
        val mirror = migrate("""<effect id="com.alightcreative.effects.mirror"><property name="type" type="int" value="1" /><property name="blendMode" type="int" value="1" /><property name="offset" type="float" value="0.5" /><property name="alpha" type="float" value="0.75" /></effect>""")
        assertEquals("absent.mirror", mirror["id"])
        for ((name, value) in mapOf("angle" to 0f, "style" to 1f, "blend" to 1f, "offset" to 0.5f, "alpha" to 0.75f)) assertEquals(name, value, mirror.number(name), 1e-5f)
        assertEquals(1f, migrate("""<effect id="com.alightcreative.effects.mirror" />""").number("style"), 0f)
        assertEquals(0f, catalog.definitions.single { it.key == "mirror" }.params.single { it.id == "blend" }.missingDefault.toFloat(), 0f)
    }

    @Test fun bothMatteChokeIdsUseErosionAndItsDefaultInset() {
        for (id in listOf("com.alightcreative.effects.mattechoke", "com.alightcreative.mattechoke")) {
            val choke = migrate("""<effect id="$id" />""")
            assertEquals("absent.mattechoker", choke["id"])
            assertEquals(1f, choke.number("method"), 0f)
            assertEquals(1f, choke.number("blur"), 0f)
        }
        val method = catalog.definitions.single { it.key == "mattechoker" }.params.single { it.id == "method" }
        assertEquals(0f, method.default.toFloat(), 0f)
        assertEquals(0f, method.missingDefault.toFloat(), 0f)
    }

    @Test fun chromaticZoomUsesEvenRaySamplingAndConvertsItsCenter() {
        val blur = migrate("""<effect id="com.alightcreative.effects.chromaticzoomblur"><property name="centerPoint" type="vec2" value="40,-25" /><property name="strength" type="float" value="0.5" /><property name="adaptive" type="bool" value="true" /></effect>""")
        assertEquals("absent.chromaticzoomblur", blur["id"])
        assertEquals(1f, blur.number("sampling"), 0f)
        assertEquals(0.5f, blur.number("amount"), 1e-5f)
        val center = blur.children.single { it["name"] == "center" }["value"]!!.split(',').map(String::toFloat)
        assertEquals(0.5f + 18f / 220f, center[0], 1e-3f)
        assertEquals(0.5f - 11.25f / 165f, center[1], 1e-3f)
        val defaults = migrate("""<effect id="com.alightcreative.effects.chromaticzoomblur" />""")
        assertEquals(0.0525f, defaults.number("amount"), 1e-5f)
        assertEquals(1f, defaults.number("sampling"), 0f)
        val explicit = migrate("""<effect id="com.alightcreative.effects.chromaticzoomblur"><property name="centerPoint" type="vec2" value="0,0" /><property name="strength" type="float" value="0.0525" /><property name="adaptive" type="bool" value="true" /></effect>""")
        assertEquals(defaults.toXml(), explicit.toXml())
        assertEquals("0.500000,0.500000", explicit.children.single { it["name"] == "center" }["value"])

        val sampling = catalog.definitions.single { it.key == "chromaticzoomblur" }.params.single { it.id == "sampling" }
        assertEquals(listOf("Linear", "Rays"), sampling.options)
        assertEquals(0f, sampling.default.toFloat(), 0f)
        assertEquals(0f, sampling.missingDefault.toFloat(), 0f)
    }

    @Test fun glowEmptyAndExplicitDefaultsUseTheSameValues() {
        val empty = migrate("""<effect id="com.alightcreative.glow" />""")
        val explicit = migrate("""<effect id="com.alightcreative.glow"><property name="radius" type="float" value="100" /><property name="alpha" type="float" value="0.75" /><property name="hardness" type="float" value="0.5" /><property name="color" type="color" value="#ffffffaa" /><property name="blendMode" type="int" value="2" /></effect>""")
        assertEquals(empty.toXml(), explicit.toXml())
        assertEquals(1f, empty.number("spread"), 0f)
        assertEquals(0f, catalog.definitions.single { it.key == "glow" }.params.single { it.id == "spread" }.missingDefault.toFloat(), 0f)
        assertEquals("#ffffffaa", empty.children.single { it["name"] == "color" }["value"])
    }

    @Test fun lumaStamperEmptyAndExplicitFittedDefaultsUseTheSameValues() {
        val empty = migrate("""<effect id="com.alightcreative.effects.lumastamper" />""")
        val explicit = migrate("""<effect id="com.alightcreative.effects.lumastamper"><property name="color" type="color" value="#ffff5566" /><property name="clipLow" type="float" value="0" /><property name="clipHigh" type="float" value="0" /><property name="gamma" type="float" value="1" /><property name="invert" type="bool" value="false" /></effect>""")
        assertEquals(empty.toXml(), explicit.toXml())
        assertEquals(0f, empty.number("compositing"), 0f)
        assertEquals(0f, migrate("""<effect id="com.alightcreative.effects.lumastamper" />""", clipped = true).number("compositing"), 0f)
        assertEquals(0f, empty.number("low"), 0f)
        assertEquals(1f, empty.number("high"), 0f)
        val own = catalog.definitions.single { it.key == "lumastamp" }
        assertEquals(0f, own.params.single { it.id == "compositing" }.default.toFloat(), 0f)
        assertEquals(0f, own.params.single { it.id == "compositing" }.missingDefault.toFloat(), 0f)
    }

}
