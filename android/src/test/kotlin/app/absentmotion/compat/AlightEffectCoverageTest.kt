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
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val rules = JSONObject(File(pack, "foreign.json").readText())
    private val migration = EffectMigration.parse(rules.toString(), catalog)

    private fun migrate(effect: String, size: String = "110,82.5"): XmlNode {
        val scene = Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="$size" /></shape></scene>""")
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.floats(name: String): List<Float> = children.single { it["name"] == name }["value"]!!.split(',').map { it.trim().toFloat() }

    @Test fun zoomBlurCentresMoveAFiveHundredthOfTheLayerPerUnit() {
        val blur = migrate("""<effect id="com.alightcreative.effects.zoomblur3"><property name="centerPoint" type="vec2" value="40.000000,-25.000000" /><property name="strength" type="float" value="0.3" /></effect>""")
        assertEquals("absent.radialblur", blur["id"])
        val c = blur.floats("center")
        assertEquals(0.58f, c[0], 1e-4f)
        assertEquals(0.45f, c[1], 1e-4f)
        assertEquals(0.3f, blur.floats("zoom")[0], 1e-5f)
        assertEquals(1f, blur.floats("style")[0], 0f)
        assertEquals(listOf(0.58f, 0.45f), migrate("""<effect id="com.alightcreative.effects.zoomblur3"><property name="centerPoint" type="vec2" value="40.000000,-25.000000" /></effect>""", "270,540").floats("center"))
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
        assertEquals(1.1125f * 0.065f, varied.floats("reach")[0], 1e-5f)
        assertEquals(listOf(2f, 0.7f, 0.25f), listOf("intensity", "threshold", "blend").map { varied.floats(it)[0] })
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

    @Test fun drawingEffectsImportFromAlight() {
        val d = migrate("""<effect id="com.alightcreative.effects.drawing.progress"><property name="start" type="float" value="25" /><property name="end" type="float" value="80" /></effect>""")
        assertEquals("absent.drawingprogress", d["id"])
        assertEquals(listOf(0.25f, 0.8f), listOf("start", "end").map { d.floats(it)[0] })
        val t = migrate("""<effect id="com.alightcreative.effects.drawing.stroketaper"><property name="start" type="float" value="0.2" /><property name="end" type="float" value="0.9" /><property name="squeeze" type="float" value="40" /></effect>""")
        assertEquals("absent.stroketaper", t["id"])
        assertEquals(listOf(0.2f, 0.9f, 40f), listOf("start", "end", "squeeze").map { t.floats(it)[0] })
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

    @Test fun warpBlurImportsEverySetting() {
        val w = migrate("""<effect id="com.alightcreative.effects.warpblur"><property name="parr" type="vec2" value="40.000000,-25.000000" /><property name="position" type="vec2" value="12.000000,8.000000" />
            <property name="intensity" type="float" value="0.8125" /><property name="evolution" type="float" value="12.5" /><property name="direction" type="int" value="5" />
            <property name="scale" type="float" value="13.25" /><property name="stretch" type="vec2" value="2.000000,0.500000" /><property name="seed" type="float" value="1.25" />
            <property name="singleAxis" type="bool" value="false" /><property name="screenSpace" type="bool" value="true" /><property name="octaves" type="float" value="6.75" />
            <property name="offs" type="float" value="0.25" /></effect>""")
        assertEquals("absent.warpblur", w["id"])
        assertEquals(listOf(40f, -25f), w.floats("parallax"))
        assertEquals(listOf(12f, 8f), w.floats("offset"))
        assertEquals(listOf(2f, 0.5f), w.floats("stretch"))
        assertEquals(listOf(0.8125f, 12.5f, 5f, 13.25f, 1.25f, 6.75f, 0.25f), listOf("strength", "evolution", "direction", "scale", "seed", "octaves", "bias").map { w.floats(it)[0] })
        assertEquals("false", w.children.single { it["name"] == "singleAxis" }["value"])
        assertEquals("true", w.children.single { it["name"] == "screenSpace" }["value"])
    }

    @Test fun olderAlightEffectsConvert() {
        val balance = migrate("""<effect id="com.alightcreative.effects.colorbalance"><property name="crLow" type="float" value="100" /><property name="ybHigh" type="float" value="-50" /><property name="fixLum" type="bool" value="false" /></effect>""")
        assertEquals("absent.colorbalance", balance["id"])
        assertEquals("#ffff8080", balance.children.single { it["name"] == "shadows" }["value"])
        assertEquals("#ff808080", balance.children.single { it["name"] == "midtones" }["value"])
        assertEquals("#ff808040", balance.children.single { it["name"] == "highlights" }["value"])
        assertEquals("false", balance.children.single { it["name"] == "preserve" }["value"])
        assertEquals(1f, balance.floats("style")[0], 0f)
        val mixer = migrate("""<effect id="com.alightcreative.effects.rgb-mixer"><property name="gamma" type="float" value="2" /><property name="rmix" type="color" value="#ff00ff00" /></effect>""")
        assertEquals("absent.channelmixer", mixer["id"])
        assertEquals(listOf(2f, 2f, 2f), listOf("gammaRed", "gammaGreen", "gammaBlue").map { mixer.floats(it)[0] })
        assertEquals("#ff00ff00", mixer.children.single { it["name"] == "red" }["value"])
        val adjust = migrate("""<effect id="com.alightcreative.effects.coloradj"><property name="saturation" type="float" value="0.5" /><property name="vib" type="float" value="-1" /></effect>""")
        assertEquals(listOf(1.5f, -2f), listOf("saturation", "vibrance").map { adjust.floats(it)[0] })
        val key = migrate("""<effect id="com.alightcreative.effects.chromakey-adv"><property name="c1active" type="bool" value="false" /><property name="c1color" type="color" value="#ffe04545" /></effect>""")
        assertEquals("#00000000", key.children.single { it["name"] == "key" }["value"])
        assertEquals(1f, key.floats("style")[0], 0f)
        val outlines = listOf("outlinebasic", "outlinebad", "outline").map { migrate("""<effect id="com.alightcreative.effects.$it"><property name="radius" type="float" value="40" /></effect>""") }
        assertEquals(listOf(0f, 1f, 2f), outlines.map { it.floats("style")[0] })
        assertEquals(listOf(37.25f, 40f, 38f), outlines.map { it.floats("radius")[0] })
        assertEquals("absent.difference", migrate("""<effect id="com.alightcreative.effects.diff" />""")["id"])
        assertEquals("absent.vortexblur", migrate("""<effect id="com.alightcreative.effects.chromaticvortexblur"><property name="colorAberration" type="float" value="0.3" /></effect>""")["id"])
        assertEquals("absent.movealongpath", migrate("""<effect id="com.alightcreative.effect.movealongpath"><property name="progress" type="float" value="0.5" /></effect>""")["id"])
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
