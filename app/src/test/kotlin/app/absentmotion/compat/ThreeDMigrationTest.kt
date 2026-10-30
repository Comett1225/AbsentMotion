package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThreeDMigrationTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val rules = JSONObject(File(pack, "foreign.json").readText())
    private val migration = EffectMigration.parse(rules.toString(), catalog)

    private fun result(effect: String, size: String = "110,82.5"): EffectMigration.Result = migration.migrate(
        Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="$size" /></shape></scene>"""),
    )

    private fun migrate(effect: String, size: String = "110,82.5"): XmlNode =
        result(effect, size).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.floats(name: String): List<Float> = children.single { it["name"] == name }["value"]!!.split(',').map { it.trim().toFloat() }

    private fun XmlNode.has(name: String) = children.any { it["name"] == name }

    private fun alight(id: String, vararg props: Pair<String, String>) =
        """<effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" type="float" value="$v" />""" }}</effect>"""

    private val targets = mapOf(
        "box" to "box", "cube" to "cube", "cube2" to "cube", "cylinder" to "cylinder", "ellipsoid" to "ellipsoid", "heart" to "heart", "heart2" to "heart",
        "hexprism" to "hexprism", "hollowbox" to "hollowbox", "octahedron" to "octahedron", "pyramid" to "pyramid", "extrude" to "rasterextrude",
        "starpoly" to "starpolyhedron", "starprism" to "starprism", "torus" to "torus", "plus" to "cross", "tunnel" to "tunnel", "flip" to "flip",
        "flip2" to "flip", "flip3" to "flip", "spherize" to "spherize", "spherize2" to "spherize", "360-reorient-sphere" to "reorient360",
        "vrplayer" to "viewer360", "vrcombined" to "viewer360",
    )

    @Test fun everyThreeDIdBecomesItsOwnEffectWithAlightsDefaultLook() {
        for ((id, key) in targets) {
            val own = catalog.definitions.single { it.key == key }
            val e = migrate(alight(id))
            assertEquals(id, own.id, e["id"])
            assertTrue(id, e.children.filter { it["name"] != null }.all { c -> own.params.any { it.id == c["name"] } })
        }
    }

    @Test fun defaultsStandUpFacingTheCameraWithAlightsCameraAndLight() {
        val box = migrate(alight("box"))
        assertEquals(listOf(0f, 0f, 0f), listOf("rotX", "rotY", "rotZ").map { box.floats(it)[0] })
        assertEquals(listOf(0f, 0f), box.floats("position"))
        assertEquals(1f, box.floats("perspective")[0], 1e-6f)
        assertEquals(1f, box.floats("shading")[0], 1e-6f)
        assertEquals(45f, box.floats("light")[0], 1e-6f)
        assertEquals(0f, box.floats("smoothing")[0], 1e-6f)
        assertEquals(0.87f, box.floats("height")[0], 1e-6f)
    }

    @Test fun rotationAndPlacementUseAlightsUnits() {
        val e = migrate(alight("box", "width" to "2", "height" to "1", "depth" to "0.5", "scale" to "1.5"))
        assertEquals(0.44f, e.floats("width")[0], 1e-5f)
        assertEquals(0.29f, e.floats("height")[0], 1e-5f)
        assertEquals(0.15f, e.floats("depth")[0], 1e-5f)
        assertEquals(1.5f, e.floats("scale")[0], 1e-6f)
        val turned = migrate("""<effect id="com.alightcreative.effects.torus"><property name="rotate" type="vec3" value="10.000000,20.000000,30.000000" />
            <property name="position" type="vec3" value="40.000000,150.000000,600.000000" /></effect>""")
        assertEquals(listOf(-10f, 20f, 30f), listOf("rotX", "rotY", "rotZ").map { turned.floats(it)[0] })
        val at = turned.floats("position")
        assertEquals(40f * 0.275f, at[0], 1e-3f)
        assertEquals(-(150f - 100f) * 0.275f, at[1], 1e-3f)
        assertEquals(listOf(0f, 0f), migrate("""<effect id="com.alightcreative.effects.torus"><property name="position" type="vec3" value="0.000000,100.000000,550.000000" /></effect>""").floats("position"))
        val tall = migrate("""<effect id="com.alightcreative.effects.torus"><property name="position" type="vec3" value="40.000000,150.000000,600.000000" /></effect>""", "220,330")
        assertEquals(40f * 0.275f * 4f, tall.floats("position")[0], 1e-3f)
    }

    @Test fun lightingKeepsIntensityAndShineAndReportsWhatItCannotKeep() {
        val ok = result("""<effect id="com.alightcreative.effects.cylinder"><property name="shadingType" type="int" value="1" /><property name="lightIntensity" type="float" value="0.5" />
            <property name="shine" type="float" value="0.4" /><property name="spec" type="float" value="30.000000" /><property name="ambient" type="float" value="0.100000" />
            <property name="lightColor" type="color" value="#ffffffff" /><property name="lightDir" type="vec2" value="75.000000,-75.000000" /><property name="lightDepth" type="float" value="100.000000" />
            <property name="mirror" type="bool" value="false" /><property name="texRepeatX" type="float" value="1.000000" /><property name="MAX_ITER" type="float" value="60.000000" /></effect>""")
        val cylinder = ok.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        assertEquals(0.5f, cylinder.floats("shading")[0], 1e-6f)
        assertEquals(0.4f, cylinder.floats("shine")[0], 1e-6f)
        assertTrue(ok.dropped.toString(), ok.dropped.isEmpty())
        val changed = result("""<effect id="com.alightcreative.effects.cylinder"><property name="lightColor" type="color" value="#FF3366CC" /><property name="texRepeatX" type="float" value="5.75" /></effect>""")
        assertEquals(setOf("Cylinder: lightColor", "Cylinder: texRepeatX"), changed.dropped)
    }

    @Test fun boxFamilySizesFollowTheObservedDefaultsAndStaySharpUntilSmoothed() {
        val cylinder = migrate(alight("cylinder", "radius" to "1", "height" to "3", "smooth" to "0.4"))
        assertEquals(0.58f, cylinder.floats("radius")[0], 1e-5f)
        assertEquals(0.9f, cylinder.floats("height")[0], 1e-5f)
        assertEquals(0.4f, cylinder.floats("smoothing")[0], 1e-6f)
        assertEquals(0.28f, migrate(alight("cube2")).floats("edge")[0], 1e-6f)
        assertEquals(0.58f, migrate(alight("cube2", "width" to "2")).floats("edge")[0], 1e-5f)
        assertEquals(0.78f, migrate(alight("spherize2", "radius" to "2.5")).floats("radius")[0], 1e-5f)
        val torus = migrate(alight("torus", "radius" to "2", "thickness" to "1"))
        assertEquals(0.6f, torus.floats("radius")[0], 1e-5f)
        assertEquals(0.3f, torus.floats("thickness")[0], 1e-5f)
        val star = migrate(alight("starprism", "pointCount" to "7"))
        assertEquals(7f, star.floats("points")[0], 1e-6f)
        val ratios = migrate(alight("ellipsoid", "squeeze" to "0.8", "flatten" to "0.5"))
        assertEquals(0.8f, ratios.floats("squeezeRatio")[0], 1e-6f)
        assertEquals(0.5f, ratios.floats("flattenRatio")[0], 1e-6f)
        val cross = migrate(alight("plus", "size" to "2", "ratio" to "0.25"))
        assertEquals(0.6f, cross.floats("reach")[0], 1e-5f)
        assertEquals(0.075f, cross.floats("thickness")[0], 1e-5f)
    }

    @Test fun flipTurnsAroundItsAxisTheOtherWayRound() {
        val flip = migrate(alight("flip", "angle" to "40", "axis" to "30"))
        assertEquals(-40f, flip.floats("rotY")[0], 1e-6f)
        assertEquals(30f, flip.floats("axis")[0], 1e-6f)
        assertEquals(1f, flip.floats("back")[0], 1e-6f)
        val solid = migrate("""<effect id="com.alightcreative.effects.flip3"><property name="solid" type="bool" value="true" /><property name="solidColor" type="color" value="#ffdd4455" />
            <property name="enableLight" type="bool" value="false" /></effect>""")
        assertEquals(2f, solid.floats("back")[0], 1e-6f)
        assertEquals(0f, solid.floats("shading")[0], 1e-6f)
        assertEquals("#ffdd4455", solid.children.single { it["name"] == "backColor" }["value"])
    }

    @Test fun panoramaEffectsTurnTheSphereByAlightsRotation() {
        val r = migrate("""<effect id="com.alightcreative.effects.360-reorient-sphere"><property name="rotate" type="vec3" value="10.000000,20.000000,30.000000" /></effect>""")
        assertEquals(listOf(30f, -20f, -10f), listOf("yaw", "pitch", "roll").map { r.floats(it)[0] })
        val v = migrate("""<effect id="com.alightcreative.effects.vrplayer"><property name="rotate" type="vec3" value="-10.000000,0.000000,5.000000" /></effect>""")
        assertEquals(listOf(5f, 0f, 10f), listOf("yaw", "pitch", "roll").map { v.floats(it)[0] })
    }

    @Test fun tunnelKeepsPhaseTwirlAndFog() {
        val t = migrate("""<effect id="com.alightcreative.effects.tunnel"><property name="phase" type="float" value="2.7" /><property name="twirl" type="float" value="45" />
            <property name="fog" type="float" value="2" /><property name="wrap" type="float" value="4" /></effect>""")
        assertEquals(listOf(2.7f, 45f, 0.8f, 4f, 0f), listOf("phase", "twist", "fog", "repeat", "speed").map { t.floats(it)[0] })
    }

    @Test fun migratedEffectsStayMigrated() {
        val first = result(alight("octahedron", "size" to "3")).scene
        assertFalse(migration.migrate(first).changed)
    }
}
