package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WarpMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode {
        val effect = """<effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>"""
        val scene = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.numbers(name: String): List<Float> = value(name)!!.split(',').map { it.trim().toFloat() }

    private fun XmlNode.number(name: String): Float = numbers(name).first()

    private fun options(effect: String, param: String) = catalog.find(effect)!!.params.single { it.id == param }.let { it.default to it.options.size }

    @Test fun swirlMeasuresOnTheScreenWithAlightsCenters() {
        for (id in listOf("swirl", "swirl2", "swirl3", "swirl4")) {
            val d = migrate(id)
            assertEquals("absent.twirl", d["id"])
            assertEquals(1f, d.number("reach"), 1e-6f)
            assertNull(d.value("radius"))
            assertEquals(0.425f, migrate(id, "radius" to "0.425").number("radius"), 1e-6f)
        }
        for (id in listOf("swirl2", "swirl3")) {
            val shifted = migrate(id, "centerPoint" to "40,-25").numbers("center")
            assertEquals(0.5f + 43.2f / 220, shifted[0], 1e-5f)
            assertEquals(0.5f - 27f / 165, shifted[1], 1e-5f)
        }
        val flipped = migrate("swirl4", "centerPoint" to "40,-25").numbers("center")
        assertEquals(0.5f + 40f / 220, flipped[0], 1e-5f)
        assertEquals(0.5f + 25f / 165, flipped[1], 1e-5f)
        assertEquals("0.000000" to 2, options("absent.twirl", "reach"))
    }

    @Test fun vortexBlurCentresLikeAlightsSwirl() {
        for (id in listOf("vortexblur", "chromaticvortexblur")) {
            assertEquals(listOf(0.5f, 0.5f), migrate(id, "centerPoint" to "0,0").numbers("center"))
            val moved = migrate(id, "centerPoint" to "40,-25").numbers("center")
            assertEquals(0.5f + 40f / 220, moved[0], 1e-5f)
            assertEquals(0.5f + 25f / 165, moved[1], 1e-5f)
        }
    }

    @Test fun anAnimatedWaveDirectionKeepsTheWarpAngleTurningWithIt() {
        val effect = """<effect id="com.alightcreative.effects.wavewarp2"><property name="a1d" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="180.000000" /></property></effect>"""
        val scene = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        val warp = migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
            .children.single { it["name"] == "warpAngle" }
        assertEquals(listOf(0f to 90f, 1f to 270f), warp.keyframes.map { it.t to it.v.toFloat() })
    }

    @Test fun pinchBulgeKeepsAlightsStrengthAndRadius() {
        for (id in listOf("pinchbulge", "pinchbulge2")) {
            val v = migrate(id, "strength" to "-0.425", "radius" to "0.85", "centerPoint" to "40,-25")
            assertEquals("absent.bulge", v["id"])
            assertEquals(-0.425f, v.number("amount"), 1e-6f)
            assertEquals(0.85f, v.number("radius"), 1e-6f)
            assertEquals(1f, v.number("reach"), 1e-6f)
            assertEquals(0.5f + 43.2f / 220, v.numbers("center")[0], 1e-5f)
            assertEquals(0.5f - 27f / 165, v.numbers("center")[1], 1e-5f)
            assertEquals(1f, migrate(id).number("reach"), 1e-6f)
            assertEquals(1f, v.number("curve"), 1e-6f)
        }
        assertEquals("0.000000" to 2, options("absent.bulge", "reach"))
        assertEquals("0.000000" to 2, options("absent.bulge", "curve"))
    }

    @Test fun innerPinchBulgeKeepsEveryAlightSetting() {
        val d = migrate("pinchbulgeinside")
        assertEquals("absent.innerbulge", d["id"])
        assertEquals(0.5f, d.number("strength"), 1e-6f)
        assertEquals(1f, d.number("style"), 1e-6f)
        val v = migrate("pinchbulgeinside", "centerPoint" to "40,-25", "strength" to "0.625", "radius" to "0.85", "feather" to "0.25", "gaussian" to "true")
        assertEquals(0.625f, v.number("strength"), 1e-6f)
        assertEquals(0.85f, v.number("radius"), 1e-6f)
        assertEquals(0.25f, v.number("feather"), 1e-6f)
        assertEquals("true", v.value("gaussian"))
        assertEquals(0.5f + 40f * 0.165f / 220, v.numbers("center")[0], 1e-5f)
        assertEquals(0.5f - 25f * 0.165f / 165, v.numbers("center")[1], 1e-5f)
        assertEquals("0.000000", catalog.find("absent.innerbulge")!!.params.single { it.id == "style" }.missingDefault)
    }

    @Test fun waveWarpKeepsItsDamping() {
        val v = migrate("wavewarp2", "damping" to "0.25", "dampingSpace" to "-0.5", "dampingOrigin" to "0.625")
        assertEquals(0.25f, v.number("damping"), 1e-6f)
        assertEquals(-0.5f, v.number("dampingSpace"), 1e-6f)
        assertEquals(0.625f, v.number("dampingOrigin"), 1e-6f)
    }

    @Test fun oldWaveWarpCrossesTwoPixelWavesFromTheScreenCorner() {
        val d = migrate("wavewarp")
        assertEquals("absent.wavewarp", d["id"])
        assertEquals(10f / 10.8f, d.number("magnitude"), 1e-5f)
        assertEquals(10f / 10.8f, d.number("crossMagnitude"), 1e-5f)
        assertEquals(20f, d.number("spacing"), 1e-3f)
        assertEquals(20f, d.number("crossSpacing"), 1e-3f)
        assertEquals(270f, d.number("angle"), 1e-6f)
        assertEquals(0f, d.number("warpAngle"), 1e-6f)
        assertEquals(1f, d.number("origin"), 1e-6f)
        assertEquals(1f, d.number("style"), 1e-6f)
        val v = migrate("wavewarp", "xamp" to "32.5", "xspace" to "40", "yamp" to "20", "yspace" to "60", "phase" to "250")
        assertEquals(20f / 10.8f, v.number("magnitude"), 1e-5f)
        assertEquals(60f, v.number("spacing"), 1e-3f)
        assertEquals(32.5f / 10.8f, v.number("crossMagnitude"), 1e-5f)
        assertEquals(40f, v.number("crossSpacing"), 1e-3f)
        assertEquals(250f / 1080f, v.number("phase"), 1e-5f)
        assertEquals("0.000000" to 2, options("absent.wavewarp", "origin"))
        assertEquals("0.000000", catalog.find("absent.wavewarp")!!.params.single { it.id == "crossMagnitude" }.default)
    }

    @Test fun circularRippleUsesAlightsDefaultStrengthOnTheScreen() {
        for (id in listOf("circularripple", "circularripple2")) {
            val d = migrate(id)
            assertEquals(0.025f, d.number("strength"), 1e-6f)
            assertEquals(1f, d.number("reach"), 1e-6f)
            val v = migrate(id, "strength" to "0.26875", "centerPoint" to "40,-25", "frequency" to "40")
            assertEquals(0.26875f, v.number("strength"), 1e-6f)
            assertEquals(40f, v.number("frequency"), 1e-6f)
            assertEquals(0.5f + 43.2f / 220, v.numbers("center")[0], 1e-5f)
        }
    }

    @Test fun squeezeRgbSplitAndPolarPickTheirAlightChoices() {
        assertEquals(2f, migrate("squeeze", "strength" to "0.5").number("shape"), 1e-6f)
        assertEquals(0.5f, migrate("squeeze", "strength" to "0.5").number("amount"), 1e-6f)
        assertEquals(3, catalog.find("absent.squeeze")!!.params.single { it.id == "shape" }.options.size)
        val rgb = migrate("rgbsep", "strength" to "2.1125", "angle" to "900", "centerChannel" to "2", "mode" to "3")
        assertEquals(4f, rgb.number("mode"), 1e-6f)
        assertEquals(2f, rgb.number("center"), 1e-6f)
        assertEquals(1f, migrate("rgbsep", "mode" to "1").number("mode"), 1e-6f)
        assertEquals(1f, migrate("polar").number("middle"), 1e-6f)
        assertEquals("0.000000" to 2, options("absent.polar", "middle"))
    }

    @Test fun offsetScalesEachAxisByItsSideAndNarrowsTheFeather() {
        val v = migrate("offset", "offset" to "40,-25", "feather" to "0.25", "mask" to "true")
        assertEquals(40f * 0.001f * 220, v.numbers("offset")[0], 1e-4f)
        assertEquals(-25f * 0.001f * 165, v.numbers("offset")[1], 1e-4f)
        assertEquals(0.0375f, v.number("feather"), 1e-6f)
    }

    @Test fun stretchSegmentMeasuresInScenePixelsWithAlightsDefault() {
        val d = migrate("stretchsegment")
        assertEquals(43.2f, d.number("stretch"), 1e-4f)
        assertNull(d.value("relative"))
        val v = migrate("stretchsegment", "stretch" to "232.5", "offset" to "500")
        assertEquals(1004.4f, v.number("stretch"), 1e-2f)
        assertEquals(-540f, v.number("offset"), 1e-3f)
    }

    @Test fun waveWarpTurnsWithItsWavesAndCanUseTheScreen() {
        val v = migrate("wavewarp2", "a1d" to "450", "a2d" to "112.5", "screenSpace" to "true", "m1" to "140")
        assertEquals(562.5f, v.number("warpAngle"), 1e-4f)
        assertEquals(450f, v.number("angle"), 1e-4f)
        assertEquals("true", v.value("screenSpace"))
        assertEquals(120f, migrate("wavewarp2", "a1d" to "30").number("warpAngle"), 1e-4f)
        assertEquals(90f, migrate("wavewarp2").number("warpAngle"), 1e-4f)
        assertEquals("false", catalog.find("absent.wavewarp")!!.params.single { it.id == "screenSpace" }.default)
    }

    @Test fun curlFillsInAlightsLooseDefaultRollAndSqueezesItsArcByStretch() {
        val d = migrate("curl")
        assertEquals(3f, d.number("radius"), 1e-6f)
        assertEquals(1.5f, d.number("stretch"), 1e-5f)
        val v = migrate("curl", "radius" to "13.25", "stretch" to "13.25")
        assertEquals(13.25f, v.number("radius"), 1e-6f)
        assertEquals(1f / 13.25f, v.number("stretch"), 1e-5f)
        assertEquals(0.01f, migrate("curl", "stretch" to "500").number("stretch"), 1e-6f)
    }
}
