package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.textSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultsMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun result(id: String, vararg props: Pair<String, String>) =
        migration.migrate(Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            <effect id="$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
            <property name="size" type="vec2" value="110,82.5" /></shape></scene>"""))

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode =
        result(id, *props).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun fx(id: String, vararg props: Pair<String, String>) = migrate("com.alightcreative.effects.$id", *props)

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.numbers(name: String): List<Float> = value(name)!!.split(',').map { it.trim().toFloat() }

    private fun XmlNode.number(name: String): Float = numbers(name).first()

    @Test fun leftOutValuesTakeAlightsOwnDefaults() {
        assertEquals(6700f, fx("colortemperature").number("kelvin"), 1e-2f)
        assertEquals(14875f, fx("colortemperature", "temperature" to "14.875").number("kelvin"), 1e-1f)
        assertEquals(29.4f, fx("gaussianblur").number("strength"), 1e-3f)
        assertEquals(120.05f, fx("gaussianblur", "strength" to "0.6125").number("strength"), 1e-2f)
        assertEquals(0.85f, fx("voronoi2").number("scale"), 1e-5f)
        assertEquals(21.8875f, fx("voronoi2", "scale" to "25.75").number("scale"), 1e-3f)
        assertEquals(12f / 62f, fx("voronoi", "scale" to "62").number("scale"), 1e-4f)
        val cells = fx("voronoi2")
        assertEquals("#ffffffff", cells.value("color1"))
        assertEquals("#ff000000", cells.value("color2"))
        assertEquals("#ff0000ff", cells.value("lineColor"))
        val older = fx("voronoi")
        assertEquals(0.0001f, older.number("lineWidth"), 1e-7f)
        assertEquals("#360000ff", older.value("lineColor"))
        assertEquals("#FF3366CC", fx("voronoi", "lineColor" to "#FF3366CC").value("lineColor"))
    }

    @Test fun roughenEdgesTakesAlightsRangeAndFinerDetail() {
        val d = fx("roughenedges")
        assertEquals(0.08f, d.number("roughness"), 1e-6f)
        val v = fx("roughenedges", "strength" to "0.775", "detail" to "13.25")
        assertEquals(0.31f, v.number("roughness"), 1e-6f)
        assertEquals(1f / 13.25f, v.number("scale"), 1e-5f)
        assertEquals(1f, fx("roughenedges", "strength" to "2.5").number("roughness"), 1e-6f)
    }

    @Test fun halftoneDotsCountCellsAcrossTheScreenNotTheLayer() {
        val d = fx("halftonedots")
        assertEquals("absent.halftone", d["id"])
        assertEquals(10.8f, d.number("cell"), 1e-3f)
        assertEquals(5.4f, fx("halftonedots", "amount" to "200").number("cell"), 1e-3f)
    }

    @Test fun legacyProjectControlsSurviveAlightConversion() {
        for (id in listOf("ridges", "ridges2")) {
            assertEquals(listOf(40f, -25f), fx(id, "parr" to "40,-25").numbers("parallax"))
        }
        val halftone = fx("halftonedots", "strength" to "0.625", "phase" to "250.375")
        assertEquals(0.625f, halftone.number("strength"), 1e-5f)
        assertEquals(250.375f, halftone.number("phase"), 1e-5f)
        assertEquals(0.85f, fx("pinchbulgeinside", "radius" to "0.85").number("radius"), 1e-5f)
        for (id in listOf("pixelate", "pixelate2")) {
            assertEquals(0.25f, fx(id, "threshold" to "0.25").number("threshold"), 1e-5f)
        }
        val solid = result("com.alightcreative.solidcolor", "alphab" to "0", "bright" to "0", "alpha" to "0.75")
        assertTrue(solid.dropped.isEmpty())
        assertEquals(0.75f, migrate("com.alightcreative.solidcolor", "alpha" to "0.75").number("amount"), 1e-5f)
        assertEquals(0.3f, fx("texttransform", "stretch" to "0.3").number("stretch"), 1e-5f)
    }

    @Test fun legacyCollabMotionControlsKeepTheirScaleAndPlacement() {
        val oscillate = fx("oscillate", "angle" to "0", "freq" to "2", "mag" to "244.51")
        assertEquals("absent.oscillate", oscillate["id"])
        assertEquals(90f, oscillate.number("angle"), 1e-5f)
        assertEquals(1f, oscillate.number("frequency"), 1e-5f)
        assertEquals(244.51f, oscillate.number("magnitude"), 1e-4f)
        val newer = fx("oscillate3", "angle" to "0", "freq" to "2")
        assertEquals(0f, newer.number("angle"), 1e-5f)
        assertEquals(2f, newer.number("frequency"), 1e-5f)
        val move = migrate("com.alightcreative.effect.movealongpath2", "progress" to "0.12", "angle" to "-180",
            "tangent" to "true", "inset" to "-500", "offset" to "17,0")
        assertEquals("absent.movealongpath", move["id"])
        assertEquals(0.12f, move.number("progress"), 1e-5f)
        assertEquals(-500f, move.number("inset"), 1e-5f)
        assertEquals(listOf(17f, 0f), move.numbers("offset"))
        assertEquals("true", move.value("legacyCoordinates"))
        val modernMove = migrate("com.alightcreative.effect.movealongpath3", "progress" to "0.12")
        assertNull(modernMove.value("legacyCoordinates"))
    }

    @Test fun collabSphereAxesBlurPassesAndDisabledLightingAreRecognized() {
        val roll = fx("360-reorient-sphere", "orient" to "0.382683432,0,0,0.923879533")
        val pitch = fx("360-reorient-sphere", "orient" to "0,0.382683432,0,0.923879533")
        val yaw = fx("360-reorient-sphere", "orient" to "0,0,0.382683432,0.923879533")
        assertEquals(45f, roll.number("roll"), 1e-4f)
        assertEquals(45f, pitch.number("pitch"), 1e-4f)
        assertEquals(-45f, yaw.number("yaw"), 1e-4f)
        val blur = fx("fastboxblur", "strength" to "0.15", "iter" to "5")
        assertEquals(48f, blur.number("strength"), 1e-5f)
        assertEquals(5f, blur.number("iterations"), 1e-5f)
        assertEquals(2f, blur.number("shape"), 1e-5f)
        val fields = arrayOf("lightIntensity" to "0.3", "lightColor" to "#ffdd4455", "lightDepth" to "0.3")
        assertTrue(result("com.alightcreative.effects.flip2", *fields, "enableLight" to "false").dropped.isEmpty())
        assertEquals(3, result("com.alightcreative.effects.flip2", *fields, "enableLight" to "true").dropped.size)
    }

    @Test fun gradientMapRunsFromItsFirstColorToItsSecond() {
        val d = migrate("com.alightcreative.gradientmap")
        assertEquals("absent.gradientmap", d["id"])
        assertEquals("#ffee7234", d.value("color1"))
        assertEquals("#ffffff88", d.value("color2"))
        assertEquals(0.5f, d.number("repeat"), 0f)
        val v = migrate("com.alightcreative.gradientmap", "color1" to "#FF3366CC", "color2" to "#FF112233")
        assertEquals("#FF3366CC", v.value("color1"))
        assertEquals("#FF112233", v.value("color2"))
        assertTrue(result("com.alightcreative.gradientmap", "color1" to "#FF3366CC").dropped.isEmpty())
    }

    @Test fun lensBlurZoomsOutwardFromAlightsCenter() {
        val d = fx("lensblur2")
        assertEquals("absent.lensblur", d["id"])
        assertEquals(1f, d.number("blur"), 0f)
        assertEquals(1.2f, d.number("feather"), 1e-5f)
        val v = fx("lensblur2", "centerPoint" to "40,-25", "strength" to "0.3625", "radius" to "0.3625")
        assertEquals(16.3125f, v.number("sweep"), 1e-3f)
        assertEquals(0.725f, v.number("radius"), 1e-5f)
        assertEquals(0.5f + 0.45f * 40f / 220f, v.numbers("center")[0], 1e-5f)
        assertEquals(0.5f - 0.37f * 25f / 165f, v.numbers("center")[1], 1e-4f)
        val old = fx("lensblur", "cx" to "0.625", "cy" to "0.625", "strength" to "0.3625")
        assertEquals(listOf(0.625f, 0.375f), old.numbers("center"))
        assertEquals(16.3125f, old.number("sweep"), 1e-3f)
        assertTrue(result("com.alightcreative.effects.lensblur", "cx" to "0.3", "cy" to "0.7", "radius" to "0.2").dropped.isEmpty())
    }

    @Test fun lensBlurStillSpinsInProjectsMadeInTheApp() {
        val blur = catalog.find("absent.lensblur")!!.params.single { it.id == "blur" }
        assertEquals(0f, blur.default.toFloat(), 0f)
        assertEquals(blur.default, blur.missingDefault)
    }

    @Test fun glassKeepsItsRimInProjectsMadeInTheApp() {
        val light = catalog.find("absent.glass")!!.params.single { it.id == "light" }
        assertEquals(0f, light.default.toFloat(), 0f)
        assertEquals(light.default, light.missingDefault)
    }

    @Test fun glassAndGlowScanBecomeOurOwn() {
        val glass = migrate("com.alightcreative.effect.glass2", "radius" to "87.5", "angle" to "933.75", "refract" to "0.6875", "intensity" to "1.25",
            "shine" to "0.85", "fill" to "0.25", "color_d" to "#FF3366CC")
        assertEquals("absent.glass", glass["id"])
        assertEquals(1f, glass.number("light"), 0f)
        assertEquals(87.5f, glass.number("blur"), 1e-4f)
        assertEquals(326.25f, glass.number("angle"), 1e-3f)
        assertEquals(27.5f, glass.number("refraction"), 1e-4f)
        assertEquals(0.15625f, glass.number("tint"), 1e-5f)
        assertEquals(0.62475f, glass.number("highlight"), 1e-5f)
        assertEquals(0.16f, glass.number("fill"), 1e-5f)
        assertEquals("#FF3366CC", glass.value("color"))
        val older = migrate("com.alightcreative.effect.glass", "angle_s" to "123.75", "intensity_d" to "0.325", "intensity_s" to "0.925", "fill" to "0.25")
        assertEquals("absent.glass", older["id"])
        assertEquals(1f, older.number("light"), 0f)
        assertEquals(56.25f, older.number("angle"), 1e-4f)
        assertEquals(0.229125f, older.number("tint"), 1e-5f)
        assertEquals(0.531875f, older.number("highlight"), 1e-5f)
        assertEquals(0.16f, older.number("fill"), 1e-5f)
        val scan = fx("glowscan", "threshold" to "0.625", "radius" to "0.325", "strength" to "0.5375", "fillColor" to "#FF3366CC")
        assertEquals("absent.glowscan", scan["id"])
        assertEquals(0.625f, scan.number("position"), 1e-5f)
        assertEquals(81.25f, scan.number("blur"), 1e-3f)
        assertEquals(0.215f, scan.number("intensity"), 1e-5f)
        assertEquals(0f, scan.number("width"), 0f)
        assertEquals(0.02f, scan.number("softness"), 1e-6f)
        assertEquals("#FF3366CC", scan.value("color"))
    }

    @Test fun displacementMapThreeMovesTheLayersBelowLikeTwo() {
        val map = fx("displacemap3", "offs" to "40,-25", "fromctr" to "true", "typex" to "0", "typey" to "3")
        assertEquals("absent.displacementmap", map["id"])
        assertTrue(map.value("map")?.toFloat()?.equals(0f) ?: true)
        assertEquals(listOf(40f, -25f), map.numbers("offset"))
        assertEquals(1f, map.number("style"), 0f)
        assertEquals(3f, map.number("xchannel"), 0f)
        assertEquals(2f, map.number("ychannel"), 0f)
        assertEquals(listOf(40f, -25f), fx("displacemap2", "offs" to "40,-25").numbers("offset"))
        assertTrue(fx("displacemap2").value("map")?.toFloat()?.equals(0f) ?: true)
    }

    @Test fun cloudsAreSofterOnlyWhenImported() {
        listOf("clouds3", "clouds2", "clouds").forEach { id ->
            val c = fx(id)
            assertEquals(1f, c.number("contrast"), 0f)
            assertEquals(0.525f, c.number("intensity"), 1e-6f)
        }
        assertEquals(0.5875f, fx("clouds3", "intensity" to "0.5625").number("intensity"), 1e-6f)
        val contrast = catalog.find("absent.clouds")!!.params.single { it.id == "contrast" }
        assertEquals(2.6f, contrast.default.toFloat(), 0f)
        assertEquals(contrast.default, contrast.missingDefault)
    }

    @Test fun olderIdsConvertToo() {
        val map = fx("displacemap", "offs" to "40,-25", "edges" to "2", "fromctr" to "true")
        assertEquals("absent.displacementmap", map["id"])
        assertEquals(listOf(40f, -25f), map.numbers("offset"))
        assertEquals(2f, map.number("edges"), 0f)
        assertTrue(result("com.alightcreative.effects.displacemap", "type" to "1").dropped.isNotEmpty())
        val clouds = fx("clouds", "offsx" to "1.25", "offsy" to "-2", "parry" to "3", "octaves" to "6.75")
        assertEquals("absent.clouds", clouds["id"])
        assertEquals(listOf(1.25f, -2f), clouds.numbers("offset"))
        assertEquals(listOf(0f, 3f), clouds.numbers("parallax"))
        assertEquals(listOf(40f, -25f), fx("clouds3", "offs" to "40,-25").numbers("offset"))
        val tune = fx("colortune")
        assertEquals("absent.colorwheels", tune["id"])
        listOf("liftAmount", "gammaAmount", "gainAmount").forEach { assertEquals(0f, tune.number(it), 0f) }
    }

    @Test fun textSpacingStaysAsAlightWroteItAndSpacesTheText() {
        val id = ForeignEffectIds.TEXT_SPACING
        val scene = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><text id="1" startTime="0" endTime="1000"><content>AB</content>
            <effect id="$id"><property name="letterspacing" type="float" value="0.18" /><property name="linespacing" type="float" value="1.6" /></effect></text></scene>""")
        val result = migration.migrate(scene)
        assertNull(result.converted.keys.firstOrNull { it.startsWith("textspacing") })
        val layer = result.scene.layers.single()
        assertEquals(0.18f, layer.textSpacing("letterspacing", 0f), 1e-6f)
        assertEquals(1.6f, layer.textSpacing("linespacing", 1f), 1e-6f)
    }
}
