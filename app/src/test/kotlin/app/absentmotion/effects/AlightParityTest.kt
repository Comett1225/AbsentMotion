package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightParityTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun params(key: String) = catalog.find("absent.$key")!!.params.associateBy { it.id }

    private fun EffectParam.row() = listOf(label, kind, default, "$min..$max")

    @Test fun recalibratedEffectsShowAlightsSettings() {
        val wave = params("wavewarp")
        assertEquals(listOf("Phase", "float", "0.000000", "0.0..500.0"), wave.getValue("phase").row())
        assertEquals(listOf("Spacing", "float", "20.000000", "0.0..500.0"), wave.getValue("spacing").row())
        assertEquals(listOf("Magnitude", "float", "4.000000", "0.0..30.0"), wave.getValue("magnitude").row())
        assertEquals(listOf("Warp Angle", "angle", "90.000000", "-3600.0..3600.0"), wave.getValue("warpAngle").row())
        val split = params("rgbsplit")
        assertEquals(listOf("Strength", "float", "0.150000", "-8.0..8.0"), split.getValue("strength").row())
        assertEquals(listOf("R", "G", "B"), split.getValue("center").options)
        assertEquals("1.000000", split.getValue("center").default)
        assertEquals(listOf("Light", "Dark", "Mask", "Luma"), split.getValue("mode").options)
        assertEquals(listOf("Size", "float", "20.000000", "1.0..500.0"), params("pixelate").getValue("blockSize").row())
        assertEquals(listOf("Steps", "float", "10.000000", "2.0..255.0"), params("posterize").getValue("levels").row())
        assertEquals(listOf("Threshold", "float", "0.400000", "0.0..1.0"), params("threshold").getValue("level").row())
        assertEquals(listOf("Strength", "float", "0.150000", "0.0..1.0"), params("radialblur").getValue("amount").row())
        assertEquals(listOf("Strength", "float", "0.020000", "0.0..2.0"), params("unsharpmask").getValue("radius").row())
        assertEquals("5.000000", params("spinblur").getValue("sweep").default)
        assertEquals(listOf("Strength", "float", "0.100000", "-0.5..0.5"), params("twirl").getValue("strength").row())
        assertEquals(listOf("Radius", "float", "0.300000", "0.0..0.8"), params("twirl").getValue("radius").row())
        assertEquals(listOf("Strength", "float", "-0.900000", "-1.0..1.0"), params("bulge").getValue("amount").row())
        assertEquals(listOf("Radius", "float", "0.300000", "0.0..2.5"), params("bulge").getValue("radius").row())
        assertEquals(listOf("Progress", "angle", "45.000000", "0.0..360.0"), params("radialwipe").getValue("progress").row())
        assertEquals(listOf("Count", "float", "1.000000", "1.0..16.0"), params("radialwipe").getValue("count").row())
        assertEquals(listOf("Clockwise", "Counterclockwise", "Both"), params("radialwipe").getValue("direction").options)
        assertEquals(listOf("Size", "Roundness", "Feather", "Strength", "Tint", "Color", "Punch Out"), catalog.find("absent.vignette")!!.params.map { it.label })
        assertEquals(listOf("Strength", "float", "0.800000", "0.0..1.0"), params("vignette").getValue("strength").row())
        for (key in listOf("zoomstreaks", "spinstreaks")) {
            val streaks = catalog.find("absent.$key")!!.params.takeLast(4)
            assertEquals(listOf("R", "G", "B", "A"), streaks.map { it.label })
            assertTrue(streaks.all { it.options == listOf("Min", "Max", "Avg", "Off") && it.default == "1.000000" })
        }
    }

    private fun older(effect: String, vararg values: Pair<String, String>) = XmlNode("effect", mapOf("id" to "absent.$effect", "hidden" to "false"),
        values.map { (name, value) -> XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value)) })

    @Test fun projectsMadeBeforeTheRecalibrationStillLoad() {
        val made = listOf(
            older("wavewarp", "amplitude" to "21.6", "wavelength" to "100", "direction" to "30", "speed" to "2"),
            older("rgbsplit", "splitDistance" to "13.5", "angle" to "0"),
            older("radialblur", "strength" to "0.5"),
            older("unsharpmask", "strength" to "0.5", "amount" to "1", "threshold" to "0"),
            older("twirl", "radius" to "0.25", "angle" to "180"),
            older("bulge", "strength" to "0.4", "radius" to "0.5"),
            older("radialwipe", "completion" to "0.25", "start" to "30"),
            older("vignette", "amount" to "0.5", "radius" to "1", "softness" to "0.5", "roundness" to "1"),
            older("spinstreaks", "sweep" to "5", "mode" to "2"),
        )
        val scene = Scene(XmlNode("scene", mapOf("width" to "100", "height" to "100", "fps" to "30", "totalTime" to "1000"),
            listOf(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "1000", "s" to ".rect"), made))))
        val result = migration.migrate(scene)
        val effects = result.scene.node.children.single().children
        fun values(i: Int) = effects[i].children.associate { it["name"]!! to Math.round(it["value"]!!.toFloat() * 1000) / 1000f }
        assertEquals(mapOf("angle" to 30f, "spacing" to 67.858f, "magnitude" to 2f, "warpAngle" to 120f, "speed" to 2f), values(0))
        assertEquals(mapOf("strength" to 0.1f, "angle" to 180f, "center" to 1f, "mode" to 2f), values(1))
        assertEquals(mapOf("amount" to 0.1f), values(2))
        assertEquals(mapOf("radius" to 0.056f, "amount" to 1f, "threshold" to 0f), values(3))
        assertEquals(mapOf("strength" to 0.066f, "radius" to 0.25f), values(4))
        assertEquals(mapOf("amount" to 0.1f, "radius" to 0.5f), values(5))
        assertEquals(mapOf("progress" to 90f, "start" to 30f), values(6))
        assertEquals(mapOf("extent" to 1f, "rounding" to 2f, "feather" to 0.5f, "strength" to 0.5f, "tint" to 0f), values(7))
        assertEquals(mapOf("sweep" to 5f, "r" to 0f, "g" to 0f, "b" to 0f, "a" to 1f), values(8))
        assertTrue(result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }

    private fun alight(id: String, vararg props: Triple<String, String, String>) =
        "<effect id=\"com.alightcreative.effects.$id\" locallyApplied=\"true\">" +
            props.joinToString("") { (n, t, v) -> "<property name=\"$n\" type=\"$t\" value=\"$v\" />" } + "</effect>"

    private fun f(n: String, v: String) = Triple(n, "float", v)

    @Test fun colorAndNoiseControlsExposeReferenceSettingsAndLegacyFallbacks() {
        assertEquals(listOf("progress", "seed", "grain"), catalog.find("absent.dissolve")!!.params.map { it.id })
        assertEquals("0.750000", params("dissolve").getValue("progress").default)
        assertEquals("0.500000", params("dissolve").getValue("progress").missingDefault)
        assertEquals("1.000000", params("dissolve").getValue("grain").default)
        assertEquals("6.000000", params("dissolve").getValue("grain").missingDefault)
        assertEquals("0.000000", params("dissolve").getValue("seed").default)
        assertEquals(listOf("level", "softness", "invert", "blend"), catalog.find("absent.threshold")!!.params.map { it.id })
        assertEquals(1f, params("threshold").getValue("softness").max)
        assertEquals("0.000000", params("threshold").getValue("blend").default)
        assertEquals(listOf("source", "target", "threshold", "feather", "alpha", "lockLuminance", "choke"), catalog.find("absent.replacecolor")!!.params.map { it.id })
        assertEquals("false", params("replacecolor").getValue("lockLuminance").default)
        assertEquals("true", params("replacecolor").getValue("lockLuminance").missingDefault)
        assertEquals("0.250000", params("replacecolor").getValue("threshold").default)
        assertEquals("0.350000", params("replacecolor").getValue("threshold").missingDefault)
        assertEquals("1.000000", params("replacecolor").getValue("alpha").default)
        assertEquals(4f, params("replacecolor").getValue("threshold").max)
        assertEquals(listOf("colors") + (1..8).map { "color$it" } + listOf("dither", "alpha"), catalog.find("absent.palettemap")!!.params.map { it.id })
        assertEquals(8f, params("palettemap").getValue("colors").max)
        assertEquals("5.000000", params("palettemap").getValue("colors").default)
        assertEquals("1.000000", params("palettemap").getValue("alpha").default)
        assertEquals(listOf("Add", "Multiply"), params("blocknoise").getValue("blending").options)
        assertEquals("1.000000", params("blocknoise").getValue("blending").default)
        assertEquals("0.000000", params("blocknoise").getValue("blending").missingDefault)
        assertEquals(listOf("blur", "mode"), catalog.find("absent.feather")!!.params.map { it.id })
        assertEquals("1.000000", params("feather").getValue("mode").default)
        assertEquals("0.000000", params("feather").getValue("mode").missingDefault)
        assertEquals(listOf("mix", "mask"), catalog.find("absent.backdrop")!!.params.map { it.id })
        assertEquals("true", params("backdrop").getValue("mask").default)
        assertEquals("false", params("backdrop").getValue("mask").missingDefault)
        assertEquals("#ff2d1ef6", params("solid").getValue("color").default)
        assertEquals("#ff3d7bf5", params("solid").getValue("color").missingDefault)
        assertEquals("1.000000", params("solid").getValue("amount").default)
        assertEquals("0.500000", params("solid").getValue("amount").missingDefault)
    }

    @Test fun newInstancesStoreNewDefaultsWhileRuntimeUsesLegacyFallbacks() {
        for (key in listOf("dissolve", "blocknoise", "feather", "backdrop", "replacecolor", "solid")) {
            val definition = catalog.find("absent.$key")!!
            val fresh = definition.instance().children.associate { it["name"]!! to it["value"]!! }
            val runtime = XmlNode.parse(definition.runtimeXml()).children.associate { it["name"]!! to it["value"]!! }
            for (p in definition.params) {
                assertEquals(p.default, fresh.getValue(p.id))
                assertEquals(p.missingDefault, runtime.getValue(p.id))
            }
        }
    }

    @Test fun newlySupportedImportControlsKeepKeysAndDoNotRepeatMigration() {
        val xml = """<scene width="720" height="720" fps="30" totalTime="2000">
            <shape id="1" startTime="0" endTime="2000" s=".rect">
                <effect id="com.alightcreative.effects.dissolve">
                    <property name="seed" type="float"><kf t="0" v="0"/><kf t="1" v="5" e="ease-out"/></property>
                </effect>
                <effect id="com.alightcreative.effects.threshold">
                    <property name="feather" type="float" value="0.8"/><property name="blendMode" type="int" value="2"/>
                </effect>
                <effect id="com.alightcreative.effects.clouds3">
                    <property name="parr" type="vec2"><kf t="0" v="0,0"/><kf t="1" v="12,34"/></property>
                    <property name="scale" type="float" value="3.7"/><property name="color1" type="color" value="#ff102030"/>
                </effect>
                <effect id="com.alightcreative.effects.lift"/>
                <effect id="com.alightcreative.effects.feather"/>
            </shape></scene>"""
        val result = migration.migrate(Scene.parse(xml))
        val effects = result.scene.node.children.single().children
        fun value(i: Int, name: String) = effects[i].children.single { it["name"] == name }["value"]!!
        assertEquals(listOf("dissolve", "threshold", "clouds", "backdrop", "feather").map { "absent.$it" }, effects.map { it["id"] })
        assertEquals(0.75f, value(0, "progress").toFloat(), 1e-4f)
        assertEquals(1f, value(0, "grain").toFloat(), 1e-4f)
        val seed = effects[0].children.single { it["name"] == "seed" }
        assertEquals(listOf(0f, 5f), seed.children.map { it["v"]!!.toFloat() })
        assertEquals("ease-out", seed.children.last()["e"])
        assertEquals(0.8f, value(1, "softness").toFloat(), 1e-4f)
        assertEquals(2f, value(1, "blend").toFloat(), 1e-4f)
        assertEquals("12.000000,34.000000", effects[2].children.single { it["name"] == "parallax" }.children.last()["v"])
        assertEquals(3.7f, value(2, "scale").toFloat(), 1e-4f)
        assertEquals("#ff102030", value(2, "color1"))
        assertEquals("true", value(3, "mask"))
        assertEquals(1f, value(4, "mode").toFloat(), 1e-4f)
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }

    @Test fun alightRepeatOpacityAndTextEffectsImportWithTheirSettings() {
        val range = arrayOf(f("angle", "15"), f("scale", "1.5"), f("alpha", "0.5"), Triple("fillColor", "color", "#ff2d1ef6"), f("blend", "0.25"),
            Triple("colorAltCopies", "bool", "false"), f("start", "0.1"), f("end", "0.9"), f("phase", "0.5"), f("easeIn", "0"), f("easeOut", "0"),
            f("overlap", "0"), Triple("shape", "int", "0"), Triple("invert", "bool", "false"), Triple("randomOrder", "bool", "true"), f("seed", "2"))
        val effects = listOf(
            alight("checkerdissolve2", f("amount", "0.75"), f("size", "10"), Triple("stretch", "vec2", "10.000000,1.000000"), f("seed", "0"), f("fade", "0")),
            alight("dissolve", f("amount", "0.37"), f("seed", "0")),
            alight("fade", f("inTime", "3.7"), f("outTime", "0.5")),
            alight("feather", f("strength", "0.15"), f("iter", "3")),
            alight("channelmaphsv", Triple("hue", "int", "0"), Triple("sat", "int", "1"), Triple("val", "int", "2"), Triple("alpha", "int", "3")),
            alight("repeat.echokf", f("seconds", "-15.6"), f("count", "1"), f("alpha", "1"), Triple("mode", "int", "1")),
            alight("counter", f("offset", "2.7"), f("scale", "1")),
            alight("repeat", f("count", "3699.63"), f("time", "0"), Triple("offset", "vec2", "12.000000,0.000000"), f("angle", "0"), f("scale", "1"), f("alpha", "1")),
            alight("repeat.line", f("count", "55.5"), Triple("position", "vec2", "12.000000,0.000000"), *range),
            alight("textrand", f("amount", "0.027"), f("evo", "0"), f("seed", "0"), f("start", "0"), f("end", "1"), Triple("charset", "int", "0"),
                Triple("preserveSpace", "bool", "true")),
        )
        val xml = "<scene title=\"t\" width=\"1080\" height=\"1080\" fps=\"30\" totalTime=\"1000\">" +
            "<shape id=\"1\" startTime=\"0\" endTime=\"1000\" s=\".rect\">${effects.joinToString("")}</shape></scene>"
        val result = migration.migrate(Scene.parse(xml))
        val all = result.scene.node.children.single().children.filter { it.name == "effect" }
        assertEquals(listOf("blockdissolve", "dissolve", "fade", "feather", "channelremaphsv", "echo", "countupdown", "repeat", "linearrepeat", "textrandomizer")
            .map { "absent.$it" }, all.map { it["id"] })
        fun values(i: Int) = all[i].children.associate { it["name"]!! to it["value"]!! }
        assertEquals("10.000000,1.000000", values(0)["stretch"])
        assertEquals(0.37f, values(1).getValue("progress").toFloat(), 1e-4f)
        assertEquals(3.7f, values(2).getValue("fadeIn").toFloat(), 1e-4f)
        assertEquals(20f, values(3).getValue("blur").toFloat(), 0.01f)
        assertEquals(listOf("0", "1", "2", "3"), listOf("h", "s", "v", "a").map { values(4).getValue(it).toFloat().toInt().toString() })
        assertEquals(-15.6f, values(5).getValue("seconds").toFloat(), 1e-4f)
        assertEquals(2.7f, values(6).getValue("add").toFloat(), 1e-4f)
        assertEquals("12.000000,0.000000", values(7)["offset"])
        val line = values(8)
        assertEquals(55.5f, line.getValue("count").toFloat(), 1e-4f)
        assertEquals(15f, line.getValue("rangeRotation").toFloat(), 1e-4f)
        assertEquals(0.25f, line.getValue("fillAmount").toFloat(), 1e-4f)
        assertEquals("true", line["randomOrder"])
        assertEquals(1f, values(9).getValue("charset").toFloat(), 1e-4f)
        assertEquals("true", values(9)["keepSpaces"])
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
    }
}
