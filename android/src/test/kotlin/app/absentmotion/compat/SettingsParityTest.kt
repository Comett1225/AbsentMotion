package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsParityTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun scene(effect: String) = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
        $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")

    private fun effect(id: String, props: Array<out Pair<String, String>>) =
        """<effect id="$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>"""

    private fun moved(s: Scene): XmlNode = migration.migrate(s).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun own(key: String, vararg props: Pair<String, String>): XmlNode {
        val s = scene(effect("absent.$key", props))
        val once = migration.migrate(s).scene
        assertEquals(once.node, migration.migrate(once).scene.node)
        return moved(s)
    }

    private fun alight(id: String, vararg props: Pair<String, String>): XmlNode = moved(scene(effect("com.alightcreative.$id", props)))

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.number(name: String): Float = value(name)!!.split(',').first().trim().toFloat()

    private fun param(key: String, id: String): EffectParam = catalog.find("absent.$key")!!.params.single { it.id == id }

    private fun EffectParam.range() = Triple(min, max, default.toFloat())

    @Test fun echoCompositeListsAtopFirstLikeAlight() {
        assertEquals(listOf("Atop", "Behind"), param("echo", "mode").options)
        assertEquals(1f, own("echo", "composite" to "0").number("mode"), 0f)
        assertEquals(0f, own("echo", "composite" to "1").number("mode"), 0f)
        assertNull(own("echo", "composite" to "1").value("composite"))
        val keyed = moved(scene("""<effect id="absent.echo"><property name="composite"><kf t="0" v="0" /><kf t="1" v="1" /></property></effect>"""))
        assertEquals(listOf("1", "0"), keyed.children.single { it["name"] == "mode" }.keyframes.map { it.v.toFloat().toInt().toString() })
        assertEquals(1f, alight("effects.repeat.echokf", "mode" to "1").number("mode"), 0f)
        assertEquals(0f, alight("effects.repeat.echokf", "mode" to "0").number("mode"), 0f)
    }

    @Test fun offsetFeatherUsesAlightsScale() {
        assertEquals(1f, own("offset", "feather" to "0.15").number("seamFeather"), 1e-5f)
        assertEquals(0.5f, alight("effects.offset", "feather" to "0.5").number("seamFeather"), 1e-5f)
        assertEquals(Triple(0f, 1f, 0f), param("offset", "seamFeather").range())
    }

    @Test fun sharpenStrengthsUseAlightsScale() {
        assertEquals(1f, own("sharpen", "amount" to "4").number("sharpness"), 1e-5f)
        assertEquals(Triple(0f, 20f, 1f), param("sharpen", "sharpness").range())
        assertEquals(1f, param("sharpen", "radius").min, 0f)
        assertEquals(5.75f, alight("effects.sharpen", "strength" to "5.75").number("sharpness"), 1e-5f)
        assertEquals(0.02f, own("unsharpmask", "radius" to "0.05").number("blurRadius"), 1e-6f)
        assertEquals(0.0448f, own("unsharpmask", "strength" to "1").number("blurRadius"), 1e-6f)
        assertEquals(0.515f, alight("effects.unsharpmask", "strength" to "0.515").number("blurRadius"), 1e-6f)
    }

    @Test fun softGlowAndFeatherShowAlightsNumbers() {
        assertEquals(0.1f, own("softglow", "blur" to "21").number("diffusion"), 1e-5f)
        assertEquals(-3f / 180f, own("softglow", "blur" to "0").number("diffusion"), 1e-5f)
        assertEquals(0.75f, alight("effects.softglow", "strength" to "0.75").number("diffusion"), 1e-5f)
        assertEquals(20f, param("softglow", "diffusion").missingDefault.toFloat() * 180 + 3, 1e-3f)
        assertEquals(1f, param("softglow", "outside").missingDefault.toFloat(), 0f)
        assertEquals(0f, param("softglow", "outside").default.toFloat(), 0f)
        assertEquals(listOf("Diffusion", "Brightness", "Contrast", "Highlights", "Color", "Blend", "Alpha", "Outer Glow"),
            catalog.find("absent.softglow")!!.params.take(8).map { it.label })
        assertEquals(0.15f, own("feather", "blur" to "42.5").number("strength"), 1e-4f)
        assertEquals(20f, param("feather", "strength").missingDefault.toFloat() * 283.333f, 1e-3f)
        assertEquals(0.74f, alight("effects.feather", "strength" to "0.74").number("strength"), 1e-5f)
        assertEquals(Triple(0f, 2f, 0.15f), param("feather", "strength").range())
    }

    @Test fun waveWarpAngleIsMeasuredFromTheWaveAngle() {
        val v = own("wavewarp", "angle" to "30", "warpAngle" to "135")
        assertEquals(30f, v.number("angle"), 0f)
        assertEquals(105f, v.number("warp"), 1e-4f)
        val keyed = moved(scene("""<effect id="absent.wavewarp"><property name="angle"><kf t="0" v="0" /><kf t="1" v="90" /></property><property name="warpAngle" value="90" /></effect>"""))
        assertEquals(listOf(90f, 0f), keyed.children.single { it["name"] == "warp" }.keyframes.map { it.v.toFloat() })
        assertEquals(45f, alight("effects.wavewarp2", "a1d" to "30", "a2d" to "45").number("warp"), 1e-4f)
        assertEquals("90.000000", param("wavewarp", "warp").default)
    }

    @Test fun pixelateKeepsOurEdgesAfterAlightsRows() {
        assertEquals(listOf("threshold", "saturation", "edges", "style", "edgeStyle"), catalog.find("absent.pixelate")!!.params.takeLast(5).map { it.id })
    }

    @Test fun rgbSplitLumaIsAlightsLuma() {
        assertEquals(listOf("Light", "Dark", "Mask", "Luma", "Luma Alpha"), param("rgbsplit", "split").options)
        assertEquals(4f, own("rgbsplit", "mode" to "3").number("split"), 0f)
        assertEquals(3f, own("rgbsplit", "mode" to "4").number("split"), 0f)
        assertEquals(2f, own("rgbsplit", "mode" to "2").number("split"), 0f)
        for (m in 0..3) assertEquals(m.toFloat(), alight("effects.rgbsep", "mode" to "$m").number("split"), 0f)
    }

    @Test fun polarDisplacementListsLumFirstLikeAlight() {
        assertEquals("φ", param("polardisplacement", "phi").label)
        assertEquals("ρ", param("polardisplacement", "rho").label)
        assertEquals(listOf("Lum", "R", "G", "B", "Alpha"), param("polardisplacement", "phi").options)
        assertEquals("0.000000", param("polardisplacement", "rho").default)
        val old = own("polardisplacement", "angleChannel" to "0", "distanceChannel" to "3")
        assertEquals(1f, old.number("phi"), 0f)
        assertEquals(0f, old.number("rho"), 0f)
        assertEquals(4f, own("polardisplacement", "angleChannel" to "4").number("phi"), 0f)
        val imported = alight("effects.displacemaprad", "typex" to "0", "typey" to "3")
        assertEquals(0f, imported.number("phi"), 0f)
        assertEquals(3f, imported.number("rho"), 0f)
    }

    @Test fun colorKeysUseAlightsPercentages() {
        assertEquals(0.4f, own("threshold", "softness" to "0.1").number("feather"), 1e-6f)
        assertEquals(0.4f, alight("effects.threshold", "feather" to "0.4").number("feather"), 1e-6f)
        assertEquals(0.25f, own("spotcolor", "saturation" to "1").number("sat"), 1e-6f)
        assertEquals(0.6f, alight("spotcolor2", "saturation" to "0.6").number("sat"), 1e-6f)
        val replace = own("replacecolor", "threshold" to "0.1", "feather" to "0.125")
        assertEquals(0.25f, replace.number("tolerance"), 1e-6f)
        assertEquals(0.25f, replace.number("softness"), 1e-6f)
        assertEquals(0.35f, param("replacecolor", "tolerance").missingDefault.toFloat() * 0.4f, 1e-6f)
        assertEquals(0.25f, param("replacecolor", "softness").missingDefault.toFloat() * 0.5f, 1e-6f)
        assertEquals(1.5f, alight("replacecolor", "threshold" to "1.5", "feather" to "0.5").number("tolerance"), 1e-6f)
        assertEquals(listOf("R", "G", "B", "A", "Y", "0", "1"), param("channelremap", "r").options)
        assertEquals(listOf("H", "S", "V", "A", "Y", "0", "1"), param("channelremaphsv", "h").options)
    }

    @Test fun textTransformShowsAlightsRowsAndNumbers() {
        val labels = catalog.find("absent.texttransform")!!.params.associate { it.id to it.label }
        assertEquals(listOf("Phase", "Component", "Offset", "Angle", "Scale", "Stretch", "Alpha"),
            listOf("offset", "unit", "position", "rotation", "scaleBy", "stretch", "alpha").map { labels.getValue(it) })
        assertEquals(listOf("Square", "Smooth", "Triangle", "Ramp Up", "Ramp Down", "Round"), param("texttransform", "curve").options)
        assertEquals(listOf("Layer", "Component"), param("texttransform", "anchorTo").options)
        assertEquals(Triple(-1f, 10f, 0f), param("texttransform", "scaleBy").range())
        assertEquals(Triple(-1f, 1f, 0f), param("texttransform", "alpha").range())
        assertEquals(false, param("texttransform", "stretch").percent)
        val old = own("texttransform", "scale" to "1.5", "opacity" to "0.25", "anchor" to "1", "shape" to "5")
        assertEquals(0.5f, old.number("scaleBy"), 1e-6f)
        assertEquals(-0.75f, old.number("alpha"), 1e-6f)
        assertEquals(0f, old.number("anchorTo"), 0f)
        assertEquals(1f, old.number("curve"), 0f)
        val shapes = (0..5).map { own("texttransform", "shape" to "$it").number("curve").toInt() }
        assertEquals(listOf(0, 3, 4, 2, 5, 1), shapes)
        val imported = alight("effects.texttransform", "scale" to "0.5", "alpha" to "-0.4", "anchor" to "0", "shape" to "1", "phase" to "-0.25")
        assertEquals(0.5f, imported.number("scaleBy"), 1e-6f)
        assertEquals(-0.4f, imported.number("alpha"), 1e-6f)
        assertEquals(0f, imported.number("anchorTo"), 0f)
        assertEquals(1f, imported.number("curve"), 0f)
        assertEquals(-0.25f, imported.number("offset"), 1e-6f)
        val spin = catalog.find("absent.texttransform")!!.presets.single { it.name == "Spin Cascade" }.values
        assertEquals("-1.000000", spin["scaleBy"])
        assertEquals("-1.000000", spin["alpha"])
        assertEquals("3.000000", spin["curve"])
    }

    @Test fun smallerPanelsMatchAlight() {
        assertEquals("Fill", param("backdrop", "mix").label)
        assertTrue(param("backdrop", "mix").percent)
        assertEquals(listOf("light", "reach", "color", "alpha", "fill", "fade"), catalog.find("absent.radialshadow")!!.params.take(6).map { it.id })
        assertEquals("Size", param("radialshadow", "reach").label)
        assertEquals("Position", param("radialrays", "center").label)
        assertEquals(listOf("center", "reach", "intensity", "threshold"), catalog.find("absent.rays")!!.params.take(4).map { it.id })
        assertEquals(false, param("countupdown", "multiply").percent)
        assertEquals(listOf("amount", "evolution", "seed", "start", "end", "charset", "keepSpaces"), catalog.find("absent.textrandomizer")!!.params.take(7).map { it.id })
        assertEquals("Character Set", param("textrandomizer", "charset").label)
        assertEquals("Preserve Space", param("textrandomizer", "keepSpaces").label)
    }

    @Test fun wipesAndPatternsUseAlightsRowsAndNumbers() {
        assertEquals(listOf("Start", "End", "Angle", "Feather", "Softness"), catalog.find("absent.wipe")!!.params.take(5).map { it.label })
        assertEquals("softness", catalog.find("absent.wipe")!!.params[3].id)
        assertEquals("", param("wipe", "softness").unit)
        assertEquals("float", param("radialwipe", "feather").kind)
        assertEquals(100f, param("radialwipe", "feather").max, 0f)
        assertEquals(listOf("CW", "CCW", "Both"), param("radialwipe", "direction").options)
        assertEquals(10f, own("checker", "count" to "4.0404").number("cells"), 1e-3f)
        assertEquals(24.75f, param("checker", "cells").missingDefault.toFloat(), 0f)
        assertEquals(17.7f, alight("effects.checker2", "amount" to "17.7").number("cells"), 1e-4f)
        val dots = own("dots", "scale" to "2", "radius" to "0.4")
        assertEquals(6.5f, dots.number("zoom"), 1e-5f)
        assertEquals(0.2f, dots.number("dotRadius"), 1e-6f)
        assertEquals(13f, param("dots", "zoom").missingDefault.toFloat(), 0f)
        assertEquals(0.1f, param("dots", "dotRadius").missingDefault.toFloat(), 0f)
        assertEquals(3.7f, alight("effects.dots2", "scale" to "3.7", "radius" to "0.2").number("zoom"), 1e-5f)
        assertEquals(0f, param("stripes", "phase").min, 0f)
    }

    @Test fun streaksRoughenAndTaperUseAlightsNumbers() {
        assertEquals(0.15f, own("zoomstreaks", "strength" to "0.4695").number("zoom"), 1e-5f)
        assertEquals(0.15f, param("zoomstreaks", "zoom").missingDefault.toFloat() * 3.13f, 1e-5f)
        assertEquals(0.6f, alight("effects.streaks-zoom", "strength" to "0.6").number("zoom"), 1e-6f)
        val rough = own("roughenedges", "roughness" to "0.6", "scale" to "0.5")
        assertEquals(1.5f, rough.number("strength"), 1e-5f)
        assertEquals(2f, rough.number("detail"), 1e-5f)
        assertEquals(listOf("Strength", "Evolution", "Detail", "Seed", "Octaves", "Border"), catalog.find("absent.roughenedges")!!.params.map { it.label })
        assertEquals(Triple(0f, 2.5f, 0.2f), param("roughenedges", "strength").range())
        assertEquals(0.6f, param("roughenedges", "strength").missingDefault.toFloat() * 0.4f, 1e-6f)
        assertEquals(Triple(0f, 1f, 0f), param("stroketaper", "start").range())
        assertEquals("1.000000", param("stroketaper", "end").missingDefault)
    }

    @Test fun voronoiAndGlassUseAlightsNumbers() {
        assertEquals(1f, own("voronoicells", "scale" to "0.85").number("cellScale"), 1e-5f)
        assertEquals(1f, param("voronoicells", "cellScale").missingDefault.toFloat() * 0.85f, 1e-5f)
        assertEquals(0.1f, own("ominoglass", "blur" to "10").number("bevel"), 1e-6f)
        assertEquals(0.25f, alight("effects.ominoglass", "strength" to "0.25").number("bevel"), 1e-6f)
        assertEquals(Triple(0f, 3f, 0.1f), param("ominoglass", "bevel").range())
        assertEquals(listOf("Amount", "Scale", "Color"), listOf("amount", "scale", "light").map { param("ominoglass", it).label })
        assertEquals(listOf("seed", "screenSpace", "octaves", "bias"), catalog.find("absent.turbulentdisplace")!!.params.map { it.id }.dropWhile { it != "seed" }.take(4))
        assertEquals(2.5f, param("bulge", "radius").max, 0f)
    }

    @Test fun stretchSegmentShowsAlightsNumbers() {
        val v = own("stretch", "angle" to "30", "stretch" to "43.2", "offset" to "-540")
        assertEquals(-30f, v.number("cutAngle"), 1e-5f)
        assertEquals(10f, v.number("span"), 1e-4f)
        assertEquals(500f, v.number("shift"), 1e-3f)
        assertEquals(200f, param("stretch", "span").missingDefault.toFloat() * 4.32f, 1e-3f)
        assertEquals(Triple(0f, 900f, 10f), param("stretch", "span").range())
        assertEquals(Triple(-2000f, 2000f, 0f), param("stretch", "shift").range())
        val imported = alight("effects.stretchsegment", "angle" to "30", "stretch" to "50", "offset" to "100")
        assertEquals(30f, imported.number("cutAngle"), 1e-5f)
        assertEquals(50f, imported.number("span"), 1e-5f)
        assertEquals(100f, imported.number("shift"), 1e-5f)
    }

    @Test fun ribbonShowsAlightsNumbers() {
        val v = own("ribbon", "magnitude" to "0.1", "frequency" to "1.65", "offset" to "-0.5", "smoothing" to "0.001", "angle" to "-30")
        assertEquals(0.2f, v.number("amplitude"), 1e-6f)
        assertEquals(3f, v.number("waves"), 1e-5f)
        assertEquals(0.25f, v.number("shift"), 1e-6f)
        assertEquals(0.01f, v.number("soften"), 1e-6f)
        assertEquals(30f, v.number("tilt"), 1e-5f)
        assertEquals(0.2f, param("ribbon", "amplitude").missingDefault.toFloat() * 0.5f, 1e-6f)
        assertEquals(3f, param("ribbon", "waves").missingDefault.toFloat() * 0.55f, 1e-5f)
        assertEquals(0.01f, param("ribbon", "soften").missingDefault.toFloat() * 0.1f, 1e-6f)
        assertEquals("Tint Color", param("ribbon", "tintAmount").label)
        assertEquals(false, param("ribbon", "tintAmount").percent)
    }

    @Test fun motionRangesMatchAlight() {
        assertEquals(Triple(0.1f, 16f, 2f), param("blink", "frequency").range())
        assertEquals(Triple(0.05f, 10f, 7f), param("flicker", "speed").range())
        assertEquals(10f, param("flicker", "seed").max, 0f)
        assertEquals(999.99f, param("scaleassist", "scale").max, 1e-3f)
        assertNull(param("scaleassist", "damp").showWhen)
        for (id in listOf("scaleWeight", "rotateWeight", "radiusAdjust")) assertNull(param("parenthelper", id).showWhen)
        assertEquals("s", param("fade", "fadeIn").unit)
        assertTrue(param("echo", "seconds").step < 0.01f)
    }
}
