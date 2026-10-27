package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightEffectCoverageTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val rules = JSONObject(File(pack, "foreign.json").readText())
    private val migration = EffectMigration.parse(rules.toString(), catalog)

    private fun migrate(effect: String, size: String = "110,82.5"): XmlNode {
        val scene = Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="$size" /></shape></scene>""")
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.floats(name: String): List<Float> = children.single { it["name"] == name }["value"]!!.split(',').map { it.trim().toFloat() }

    @Test fun centrePointsBecomeShareOfTheLayer() {
        val blur = migrate("""<effect id="com.alightcreative.effects.zoomblur3"><property name="centerPoint" type="vec2" value="40.000000,-25.000000" /><property name="strength" type="float" value="0.3" /></effect>""")
        assertEquals("absent.radialblur", blur["id"])
        val c = blur.floats("center")
        assertEquals(0.5f + 40f / 220f, c[0], 1e-4f)
        assertEquals(0.5f - 25f / 165f, c[1], 1e-4f)
        assertEquals(1.5f, blur.floats("amount")[0], 1e-5f)
    }

    @Test fun pointsLeftAtAlightsDefaultTakeTheCurrentDefaultNotTheLegacyOne() {
        val rays = migrate("""<effect id="com.alightcreative.effects.rays"><property name="strength" type="float" value="0.15" /></effect>""")
        assertEquals(listOf(0.5f, 0.5f), rays.floats("center"))
    }

    @Test fun raysKeepThresholdAddAndBlendAndOnlyDropQualityWhenItIsNotTheDefault() {
        val varied = migrate("""<effect id="com.alightcreative.effects.rays"><property name="centerPoint" type="vec2" value="40.000000,-25.000000" /><property name="strength" type="float" value="1.1125" />
            <property name="intensity" type="float" value="2" /><property name="threshold" type="float" value="0.7" /><property name="thresholdColor" type="color" value="#FF3366CC" />
            <property name="fillColor" type="color" value="#FF3366CC" /><property name="blend" type="float" value="0.25" /><property name="quality" type="float" value="150" /></effect>""")
        assertEquals("absent.rays", varied["id"])
        assertEquals(listOf(1.1125f, 2f, 0.7f, 0.25f), listOf("reach", "intensity", "threshold", "blend").map { varied.floats(it)[0] })
        assertEquals(0.5f + 40f / 220f, varied.floats("center")[0], 1e-4f)
        assertEquals("#FF3366CC", varied.children.single { it["name"] == "thresholdAdd" }["value"])
        assertEquals("#FF3366CC", varied.children.single { it["name"] == "color" }["value"])
        assertTrue(varied.children.none { it["name"] == "quality" })
        assertEquals(1f, varied.floats("style")[0], 1e-6f)
        val plain = migrate("""<effect id="com.alightcreative.effects.rays" />""")
        assertEquals(0f, plain.floats("blend")[0], 1e-6f)
        assertEquals(1f, plain.floats("style")[0], 1e-6f)
        assertEquals(0.15f, plain.floats("reach")[0], 1e-6f)
        assertTrue(plain.children.none { it["name"] == "thresholdAdd" })
    }

    @Test fun sizesInLayerPixelsFollowTheLayer() {
        assertEquals(108f, migrate("""<effect id="com.alightcreative.effects.mosaic"><property name="size" type="float" value="10" /></effect>""", "540,540").floats("count")[0], 1e-3f)
        assertEquals(0.02f * 0.57f * 1080f, migrate("""<effect id="com.alightcreative.effects.smoothedges"><property name="strength" type="float" value="0.02" /></effect>""", "540,540").floats("blur")[0], 1e-3f)
    }

    @Test fun newEffectsImportFromAlight() {
        val t = migrate("""<effect id="com.alightcreative.effects.transform"><property name="scale" type="float" value="2.5" /><property name="angle" type="float" value="30" />
            <property name="maskToLayer" type="bool" value="true" /><property name="alpha" type="float" value="0.75" /><property name="fill" type="float" value="0.25" /><property name="sample" type="int" value="0" /></effect>""")
        assertEquals("absent.rastertransform", t["id"])
        assertEquals(listOf(2.5f, 30f, 0.75f, 0.25f, 0f), listOf("scale", "angle", "alpha", "fill", "sampling").map { t.floats(it)[0] })
        assertEquals("true", t.children.single { it["name"] == "mask" }["value"])
        val m = migrate("""<effect id="com.alightcreative.solidmatte"><property name="color" type="color" value="#FF3366CC" /><property name="punchout" type="bool" value="true" /></effect>""")
        assertEquals("absent.solidmatte", m["id"])
        assertEquals("#FF3366CC", m.children.single { it["name"] == "color" }["value"])
    }

    @Test fun switchesCanBecomeNumbers() {
        fun invert(on: Boolean) = migrate("""<effect id="com.alightcreative.effects.invert"><property name="invertRed" type="bool" value="$on" /><property name="invertGreen" type="bool" value="true" /><property name="invertBlue" type="bool" value="true" /></effect>""")
        assertEquals("absent.invert", invert(true)["id"])
        assertEquals(1f, invert(true).floats("amount")[0], 1e-6f)
        assertEquals(0f, invert(false).floats("amount")[0], 1e-6f)
        val strips = migrate("""<effect id="com.alightcreative.effects.contourstrips"><property name="amount" type="float" value="12" /><property name="lineColor" type="color" value="#ff223355" /><property name="heightSource" type="int" value="0" /></effect>""")
        assertEquals(12f, strips.floats("count")[0], 1e-6f)
        assertEquals("#ff223355", strips.children.single { it["name"] == "edgeColor" }["value"])
        assertEquals(0f, strips.floats("map")[0], 1e-6f)
    }

    @Test fun aThreePartPointSplitsIntoPositionAndDepth() {
        val stars = migrate("""<effect id="com.alightcreative.effects.simplestars2"><property name="starParallax" type="vec3" value="10.000000,20.000000,30.000000" /><property name="starCount" type="float" value="31.25" /></effect>""")
        assertEquals("absent.simplestarfield", stars["id"])
        assertEquals(listOf(10f, 20f), stars.floats("position"))
        assertEquals(30f, stars.floats("depth")[0], 1e-6f)
        assertEquals(31.25f, stars.floats("amount")[0], 1e-5f)
        val older = migrate("""<effect id="com.alightcreative.effects.simplestars"><property name="starParallax" type="vec2" value="40.000000,-25.000000" /><property name="starDepth" type="float" value="249.75" /></effect>""")
        assertEquals("absent.simplestarfield", older["id"])
        assertEquals(listOf(40f, -25f), older.floats("position"))
        assertEquals(249.75f, older.floats("depth")[0], 1e-4f)
    }

    @Test fun commonAlightEffectsAllRun() {
        val common = listOf("zoomblur3", "spinblur2", "dblur", "swirl4", "pinchbulge2", "wavewarp2", "turbulentdisplace3", "pixelate2", "mosaic", "posterize",
            "vignette", "wipe2", "radialwipe", "kaleidoscope", "chromakey", "lumakey3", "halftonedots", "rays", "radialrays", "star", "stripes", "grid2",
            "voronoi2", "turbulence2", "ribbon", "rgbsep", "displacemap3", "tileshift2", "tilerotate2", "streaks-zoom", "streaks-spin", "transform", "smoothedges")
        for (id in common) {
            val rule = rules.optJSONObject("com.alightcreative.effects.$id")
            assertTrue(id, rule != null && catalog.definitions.any { it.key == rule.getString("to") })
        }
    }
}
