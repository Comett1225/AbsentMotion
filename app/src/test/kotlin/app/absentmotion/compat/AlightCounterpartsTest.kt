package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightCounterpartsTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun effects(node: XmlNode): List<XmlNode> = (if (node.name == "effect") listOf(node) else emptyList()) + node.children.flatMap(::effects)

    private fun convert(id: String, props: String, half: String = "540.000000,540.000000"): XmlNode {
        val xml = """<scene width="1080" height="1350" fps="30" totalTime="2000">
            <shape id="1" startTime="0" endTime="1999" fillType="color" s=".rect">
              <effect id="$id" locallyApplied="false">$props</effect>
              <property name="size" type="vec2" value="$half" />
            </shape></scene>"""
        return effects(migration.migrate(Scene.parse(xml)).scene.node).single()
    }

    private fun f(name: String, value: Float) = """<property name="$name" type="float" value="$value" />"""

    private fun XmlNode.value(name: String) = children.single { it.name == "property" && it["name"] == name }["value"]!!

    private fun XmlNode.number(name: String) = value(name).toFloat()

    private fun XmlNode.has(name: String) = children.any { it.name == "property" && it["name"] == name }

    @Test fun halftoneDotsBecomeTheTwoColorStyleWithOneCellPerAmountAcrossTheLayerHeight() {
        val e = convert("com.alightcreative.effects.halftonedots", f("amount", 60f) + f("angle", 45f) +
            """<property name="color1" type="color" value="#ff103152" /><property name="color2" type="color" value="#fffcecda" />""")
        assertEquals("absent.halftone", e["id"])
        assertEquals(18f, e.number("cell"), 1e-3f)
        assertEquals(45f, e.number("angle"), 1e-3f)
        assertEquals(1f, e.number("style"), 0f)
        assertEquals("#ff103152", e.value("ink"))
        assertEquals("#fffcecda", e.value("paper"))
        assertEquals(54f, convert("com.alightcreative.effects.halftonedots", f("amount", 20f)).number("cell"), 1e-3f)
    }

    @Test fun halftoneKeepsItsOldLookUnlessTheStyleIsChosen() {
        val style = catalog.find("absent.halftone")!!.params.single { it.id == "style" }
        assertEquals(0f, style.default.toFloat(), 0f)
        assertEquals(style.default, style.missingDefault)
    }

    @Test fun zoomBlurStrengthFollowsTheFittedScale() {
        assertEquals(0.115f, convert("com.alightcreative.effects.zoomblur", f("strength", 0.15f)).number("amount"), 2e-3f)
        assertEquals(0.266f, convert("com.alightcreative.effects.zoomblur", f("strength", 0.35f)).number("amount"), 2e-3f)
    }

    @Test fun rgbSplitAndSpinBlurKeepTheirFittedValues() {
        val rgb = convert("com.alightcreative.effects.rgbsep", f("strength", 2f) + f("angle", 0f) +
            """<property name="centerChannel" type="int" value="1" /><property name="mode" type="int" value="0" />""")
        assertEquals(2f, rgb.number("strength"), 1e-4f)
        assertEquals(1f, rgb.number("center"), 1e-4f)
        assertEquals(90f, convert("com.alightcreative.effects.spinblur", f("angle", 90f) + f("cx", 0.5f) + f("cy", 0.5f)).number("sweep"), 1e-4f)
    }

    @Test fun hexTileSizeBecomesTheHexagonTilingScale() {
        val e = convert("com.alightcreative.effects.hextile", """<property name="position" type="vec2" value="0.0,0.0" />""" + f("size", 30f) + f("angle", 20f))
        assertEquals("absent.hexagontiling", e["id"])
        assertEquals(1.2f, e.number("scale"), 1e-3f)
        assertEquals(20f, e.number("angle"), 1e-3f)
    }

    @Test fun mirrorBecomesAFlipWithItsAlpha() {
        val e = convert("com.alightcreative.effects.mirror", """<property name="type" type="int" value="0" />""" + f("alpha", 0.75f))
        assertEquals("absent.mirror", e["id"])
        assertEquals(90f, e.number("angle"), 1e-3f)
        assertEquals(0.75f, e.number("alpha"), 1e-3f)
        assertEquals(1f, e.number("style"), 0f)
        assertEquals(0f, convert("com.alightcreative.effects.mirror", """<property name="type" type="int" value="1" />""").number("angle"), 1e-3f)
    }

    @Test fun iridescenceFrequencyBecomesTheInverseScaleAndKeepsTheSoftLook() {
        for (id in listOf("com.alightcreative.effects.irridescence", "com.alightcreative.effects.irridescence2")) {
            val e = convert(id, f("FREQ", 2f) + f("AMP", 0.125f))
            assertEquals("absent.iridescence", e["id"])
            assertEquals(0.5f, e.number("scale"), 1e-3f)
            assertEquals(1f, e.number("alpha"), 1e-3f)
            assertEquals(0.3f, e.number("saturation"), 1e-3f)
        }
    }

    @Test fun matteChokeGivesAnEdgeBlurAndAHighThreshold() {
        for (id in listOf("com.alightcreative.effects.mattechoke", "com.alightcreative.mattechoke")) {
            val e = convert(id, f("choke", 53.75f) + f("feather", 0.25f) + """<property name="invert" type="bool" value="true" />""")
            assertEquals("absent.mattechoker", e["id"])
            assertEquals(80.625f, e.number("blur"), 1e-2f)
            assertEquals(0.045f, e.number("softness"), 1e-3f)
            assertEquals(0.97f, e.number("amount"), 1e-3f)
            assertEquals("true", e.value("invert"))
        }
    }

    @Test fun bumpMapKeepsLightingSettings() {
        for (id in listOf("com.alightcreative.effects.bumpmap", "com.alightcreative.effects.bumpmap2")) {
            val e = convert(id, f("ambient", 0.7f) + f("shine", 0.85f) + """<property name="lightColor" type="color" value="#FF3366CC" />""" + f("height", 0.325f))
            assertEquals("absent.bumpmap", e["id"])
            assertEquals(0.7f, e.number("ambient"), 1e-3f)
            assertEquals(0.85f, e.number("shine"), 1e-3f)
            assertEquals("#FF3366CC", e.value("light"))
            assertTrue(!e.has("height"))
        }
    }
}
