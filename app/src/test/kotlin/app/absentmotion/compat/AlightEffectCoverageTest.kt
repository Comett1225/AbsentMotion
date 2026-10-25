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
