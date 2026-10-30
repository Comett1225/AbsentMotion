package app.absentmotion.compat

import app.absentmotion.TestData
import app.absentmotion.i18n.txt
import app.absentmotion.i18n.UiText
import app.absentmotion.R
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.library.Importer
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EffectMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val rules = JSONObject(File(pack, "foreign.json").readText())
    private val migration = EffectMigration.parse(rules.toString(), catalog)

    private fun foreign(own: String, param: String): String = rules.keys().asSequence().first { id ->
        rules.getJSONObject(id).let { it.getString("to") == own && it.getJSONObject("params").has(param) }
    }

    private fun conversion(own: String, param: String, component: Int = -1): JSONObject =
        rules.getJSONObject(foreign(own, param)).getJSONObject("params").let { p ->
            if (component < 0) p.getJSONObject(param) else p.getJSONArray(param).getJSONObject(component)
        }

    private fun expected(own: String, param: String, v: Float, component: Int = -1): Float {
        val c = conversion(own, param, component)
        val looked = c.optJSONObject("values")?.optDouble(v.toInt().toString())?.takeUnless { it.isNaN() }?.toFloat() ?: v
        val raised = Math.pow(Math.abs(looked.toDouble()), c.optDouble("power", 1.0)).toFloat().let { if (looked < 0) -it else it }
        val moved = raised * c.optDouble("scale", 1.0).toFloat() + c.optDouble("offset", 0.0).toFloat()
        val wrap = c.optDouble("wrap", 0.0).toFloat()
        return (if (wrap > 0f) moved.mod(wrap) else moved)
            .coerceIn(c.optDouble("min", -Double.MAX_VALUE).toFloat(), c.optDouble("max", Double.MAX_VALUE).toFloat())
    }

    private val scene: String get() = """
        <scene title="Mini" width="640" height="360" fps="30" totalTime="2000" bgcolor="#ff101820">
          <shape id="1" label="Backdrop card" startTime="0" endTime="1999" fillType="color" s=".rect">
            <fillColor value="#ff2a6f97" />
            <effect id="${foreign("backdrop", "fill")}" locallyApplied="false"><property name="fill" type="float" value="0.000000" /></effect>
            <effect id="${foreign("blur", "strength")}" locallyApplied="false">
              <property name="strength" type="float"><kf t="0.000000" v="0.200000" /><kf t="1.000000" v="0.800000" e="cubicBezier 0.42 0 0.58 1" /></property>
            </effect>
            <effect id="${foreign("sharpen", "strength")}" hidden="true" locallyApplied="false">
              <property name="strength" type="float" value="1.500000" /><property name="radius" type="float" value="2.000000" />
            </effect>
            <effect id="${foreign("gradientoverlay", "gradType")}" locallyApplied="false">
              <property name="color1" type="color" value="#ff102030" /><property name="color2" type="color" value="#ffa0b0c0" />
              <property name="angle" type="float" value="30.000000" /><property name="scale" type="float" value="1.500000" />
              <property name="alpha" type="float" value="0.500000" /><property name="gradType" type="int" value="0" />
              <property name="blendMode" type="int" value="2" />
            </effect>
            <effect id="${foreign("exposure", "exposure")}" locallyApplied="false">
              <property name="exposure" type="float" value="0.500000" /><property name="gamma" type="float" value="2.000000" />
              <property name="offset" type="float" value="0.100000" />
            </effect>
            <effect id="${foreign("splittone", "tint")}" locallyApplied="false">
              <property name="color" type="vec3" value="30.000000,0.500000,0.000000" />
              <property name="tint" type="vec3"><kf t="0.000000" v="60.000000,1.000000,0.000000" /><kf t="0.500000" v="90.000000,0.500000,0.000000" e="step 0.25 0" /></property>
            </effect>
            <property name="size" type="vec2" value="320.000000,180.000000" />
          </shape>
          <shape id="2" label="Tint card" startTime="0" endTime="999" fillType="color" blending="screen" s=".rect">
            <fillColor value="#ff8fc93a" />
            <effect id="${foreign("fourcolor", "offset1")}" locallyApplied="false">
              <property name="color1" type="color" value="#ff111111" /><property name="offset1" type="vec2" value="0.000000,-100.000000" />
              <property name="color2" type="color" value="#ff222222" /><property name="offset2" type="vec2" value="100.000000,-100.000000" />
              <property name="color3" type="color" value="#ff333333" /><property name="offset3" type="vec2" value="100.000000,0.000000" />
              <property name="color4" type="color" value="#ff444444" /><property name="blend" type="float" value="0.500000" />
              <property name="alpha" type="float" value="0.700000" /><property name="blendMode" type="int" value="1" />
            </effect>
            <effect id="${foreign("colorwheels", "gain")}" locallyApplied="false">
              <property name="gain" type="vec3" value="40.000000,0.020000,0.100000" /><property name="gamma" type="vec3" value="-120.000000,0.010000,0.000000" />
            </effect>
            <effect id="${foreign("lightglow", "halo")}" locallyApplied="false">
              <property name="strength" type="float" value="0.400000" /><property name="threshold" type="float" value="0.600000" />
              <property name="intensity" type="float" value="2.000000" /><property name="color" type="color" value="#ffffeedd" />
              <property name="blend" type="float" value="1.000000" /><property name="halo" type="float" value="0.000000" />
              <property name="alpha" type="float" value="0.900000" />
            </effect>
            <effect id="${foreign("softglow", "outsideAlpha")}" locallyApplied="false">
              <property name="strength" type="float" value="0.200000" /><property name="brightness" type="float" value="1.200000" />
              <property name="contrast" type="float" value="0.100000" /><property name="highlights" type="float" value="0.400000" />
              <property name="color" type="color" value="#ffffffff" /><property name="blend" type="float" value="1.000000" />
              <property name="alpha" type="float" value="0.800000" /><property name="outsideAlpha" type="float" value="0.000000" />
            </effect>
            <effect id="${foreign("huesaturation", "vib")}" locallyApplied="false">
              <property name="saturation" type="float" value="1.000000" /><property name="vib" type="float" value="0.400000" />
            </effect>
            <effect id="${foreign("huesaturation", "hue")}" locallyApplied="false">
              <property name="hue" type="float"><kf t="0.100000" v="10.000000" /><kf t="0.900000" v="200.000000" e="elastic 0.25 1 0.5 1" /></property>
            </effect>
            <property name="size" type="vec2" value="320.000000,180.000000" />
          </shape>
          <embedScene id="3" label="Group" startTime="0" endTime="1999">
            <scene width="640" height="360" fps="30" totalTime="2000">
              <shape id="4" label="Inner card" startTime="0" endTime="1999" fillType="color" s=".rect">
                <effect id="${foreign("temperature", "temperature")}" locallyApplied="false">
                  <property name="temperature" type="float" value="4.500000" /><property name="strength" type="float" value="0.600000" />
                </effect>
                <effect id="${foreign("threshold", "feather")}" locallyApplied="false">
                  <property name="threshold" type="float" value="0.400000" /><property name="feather" type="float" value="0.100000" />
                  <property name="invert" type="bool" value="true" /><property name="blendMode" type="int" value="0" />
                </effect>
                <effect id="${foreign("gradientmap", "squeeze")}" locallyApplied="false">
                  <property name="blueColor" type="color" value="#ff0000ff" /><property name="greenColor" type="color" value="#ff00ff00" />
                  <property name="redColor" type="color" value="#ffff0000" /><property name="saturation" type="float" value="1.000000" />
                  <property name="scaleBrightness" type="bool" value="false" /><property name="phase" type="float" value="0.250000" />
                  <property name="repeat" type="float" value="2.000000" /><property name="alpha" type="float" value="0.500000" />
                  <property name="squeeze" type="float" value="0.000000" /><property name="reverse" type="bool" value="true" />
                </effect>
                <effect id="${foreign("blink", "freq")}" locallyApplied="false">
                  <property name="freq" type="float" value="3.000000" /><property name="mystery" type="float" value="7.000000" />
                </effect>
                <effect id="${foreign("solid", "blendMode")}" locallyApplied="false">
                  <property name="blendMode" type="int" value="1" /><property name="color" type="color" value="#ff5ad1c4" />
                  <property name="alpha" type="float" value="0.750000" />
                </effect>
                <effect id="${foreign("stretch", "smooth")}" locallyApplied="false">
                  <property name="angle" type="float" value="15.000000" /><property name="stretch" type="float" value="120.000000" />
                  <property name="offset" type="float" value="-20.000000" /><property name="smooth" type="float" value="5.000000" />
                </effect>
                <effect id="example.effects.unknown"><property name="amount" type="float" value="1.000000" /></effect>
                <effect id="absent.blur" hidden="false"><property name="strength" type="float" value="4.000000" /></effect>
                <property name="size" type="vec2" value="100.000000,100.000000" />
              </shape>
            </scene>
          </embedScene>
        </scene>
    """.trimIndent()

    private fun effects(node: XmlNode): List<XmlNode> = (if (node.name == "effect") listOf(node) else emptyList()) + node.children.flatMap(::effects)

    private fun XmlNode.prop(name: String) = children.single { it.name == "property" && it["name"] == name }

    private fun XmlNode.number(name: String) = prop(name)["value"]!!.toFloat()

    private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

    @Test fun everyRuleNamesAnOwnEffectAndItsParameters() {
        for (id in rules.keys()) {
            val rule = rules.getJSONObject(id)
            val own = catalog.definitions.singleOrNull { it.key == rule.getString("to") }
            assertTrue("$id: no own effect ${rule.getString("to")}", own != null)
            val names = own!!.params.map { it.id }.toSet()
            val params = rule.getJSONObject("params")
            for (name in params.keys()) {
                val list = params.get(name).let { if (it is JSONArray) List(it.length()) { i -> it.optJSONObject(i) } else listOf(it as JSONObject) }
                for (c in list.filterNotNull()) if (c.has("to")) assertTrue("$id $name -> ${c.getString("to")}", c.getString("to") in names)
            }
            rule.optJSONObject("set")?.keys()?.forEach { assertTrue("$id sets $it", it in names) }
        }
    }

    @Test fun everyForeignEffectBecomesAnOwnEffectInPlace() {
        val result = migration.migrate(Scene.parse(scene))
        val ids = effects(result.scene.node).map { it["id"] }
        assertEquals(
            listOf("backdrop", "blur", "sharpen", "gradientoverlay", "exposure", "splittone", "fourcolor", "colorwheels", "lightglow", "softglow",
                "huesaturation", "huesaturation", "temperature", "threshold", "gradientmap", "blink", "solid", "stretch").map { "absent.$it" } +
                listOf("example.effects.unknown", "absent.blur"),
            ids,
        )
        assertEquals(18, result.converted.values.sum())
        assertEquals(18, result.converted.size)
        for (effect in effects(result.scene.node)) {
            val own = catalog.find(effect["id"]!!) ?: continue
            for (p in effect.children.filter { it.name == "property" }) {
                val param = own.params.firstOrNull { it.id == p["name"] }
                assertTrue("${own.key} has ${p["name"]}", param != null)
                assertEquals("${own.key}.${p["name"]} type", param!!.type, p["type"])
            }
            assertEquals(own.params.map { it.id }.filter { id -> effect.children.any { it["name"] == id } }, effect.children.mapNotNull { it["name"] })
        }
    }

    @Test fun colorizeTextTransformAndChromaticZoomBlurBecomeOwnEffects() {
        val xml = """<scene width="1080" height="1350" fps="30" totalTime="2000">
            <shape id="1" startTime="0" endTime="2000" fillType="color" s=".rect">
              <effect id="com.alightcreative.effects.colorize"><property name="tint" type="vec3" value="-179.297440,0.442514,0.000000" /></effect>
              <effect id="com.alightcreative.effects.chromaticzoomblur"><property name="strength" type="float" value="0.03" /><property name="adaptive" type="bool" value="true" /></effect>
            </shape>
            <text id="2" startTime="0" endTime="2000" size="112">
              <effect id="com.alightcreative.effects.texttransform">
                <property name="fillColor" type="color" value="#ffbebebe" /><property name="useFillColor" type="bool" value="true" />
                <property name="randomOrder" type="bool" value="true" /><property name="seed" type="float"><kf t="0" v="0" /><kf t="1" v="0.018947" /></property>
                <property name="shape" type="int" value="1" /><property name="start" type="float" value="0" /><property name="end" type="float" value="1" />
                <property name="phase" type="float" value="0" /><property name="component" type="int" value="1" /><property name="anchor" type="int" value="0" />
                <property name="angle" type="float" value="0" /><property name="scale" type="float" value="0" /><property name="stretch" type="float" value="0" />
                <property name="alpha" type="float" value="0" /><property name="easeIn" type="float" value="0" /><property name="easeOut" type="float" value="0" />
                <property name="overlap" type="float" value="0" />
              </effect>
              <effect id="com.alightcreative.effects.texttransform">
                <property name="fillColor" type="color" value="#ffbebebe" /><property name="useFillColor" type="bool" value="false" />
              </effect>
              <content>A</content>
            </text>
          </scene>"""
        val result = migration.migrate(Scene.parse(xml))
        val all = effects(result.scene.node)
        assertEquals(listOf("colorize", "chromaticzoomblur", "texttransform", "texttransform").map { "absent.$it" }, all.map { it["id"] })
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        close(89.29744f, all[0].number("hue"))
        close(0.442514f, all[0].number("saturation"))
        close(1f, all[0].number("amount"))
        close(0.15f, all[1].number("amount"))
        assertEquals("#ffbebebe", all[2].prop("tint")["value"])
        close(1f, all[2].number("scale"))
        close(1f, all[2].number("opacity"))
        close(5f, all[2].number("shape"))
        assertEquals(2, all[2].prop("seed").keyframes.size)
        assertEquals("#00000000", all[3].prop("tint")["value"])
    }

    @Test fun valuesKeyframesEasingsAndFlagsAreConverted() {
        val all = effects(migration.migrate(Scene.parse(scene)).scene.node)
        fun own(key: String, nth: Int = 0) = all.filter { it["id"] == "absent.$key" }[nth]

        close(0f, own("backdrop").number("mix"))
        val blur = own("blur").prop("strength").keyframes
        assertEquals(listOf(0f, 1f), blur.map { it.t })
        close(expected("blur", "strength", 0.2f), blur[0].v.toFloat())
        close(expected("blur", "strength", 0.8f), blur[1].v.toFloat())
        assertNull(blur[0].e)
        assertEquals("cubicBezier 0.42 0 0.58 1", blur[1].e)

        assertEquals("true", own("sharpen")["hidden"])
        assertEquals("false", own("sharpen")["locallyApplied"])
        close(expected("sharpen", "strength", 1.5f), own("sharpen").number("amount"))
        close(expected("sharpen", "radius", 2f), own("sharpen").number("radius"))

        val gradient = own("gradientoverlay")
        assertEquals("#ff102030", gradient.prop("color1")["value"])
        assertEquals("#ffa0b0c0", gradient.prop("color2")["value"])
        close(expected("gradientoverlay", "angle", 30f), gradient.number("angle"))
        close(expected("gradientoverlay", "alpha", 0.5f), gradient.number("opacity"))
        close(expected("gradientoverlay", "gradType", 0f), gradient.number("shape"))
        close(expected("gradientoverlay", "blendMode", 2f), gradient.number("blend"))

        close(expected("exposure", "exposure", 0.5f), own("exposure").number("stops"))
        close(expected("exposure", "gamma", 2f), own("exposure").number("gamma"))

        val tone = own("splittone")
        val hue = conversion("splittone", "color", 0).getString("to")
        close(expected("splittone", "color", 30f, 0), tone.number(hue))
        val tint = tone.prop(conversion("splittone", "tint", 1).getString("to")).keyframes
        assertEquals(listOf(0f, 0.5f), tint.map { it.t })
        close(expected("splittone", "tint", 1f, 1), tint[0].v.toFloat())
        close(expected("splittone", "tint", 0.5f, 1), tint[1].v.toFloat())
        assertEquals("step 0.25 0", tint[1].e)

        val four = own("fourcolor")
        assertEquals("#ff444444", four.prop("color4")["value"])
        assertEquals("0.000000,0.000000", four.prop("point4")["value"])
        val p1 = four.prop("point1")["value"]!!.split(',').map { it.toFloat() }
        close(expected("fourcolor", "offset1", 0f), p1[0])
        close(expected("fourcolor", "offset1", -100f), p1[1])
        close(expected("fourcolor", "blendMode", 1f), four.number("blend"))

        val wheels = own("colorwheels")
        close(0f, wheels.number("liftAmount"))
        close(expected("colorwheels", "gain", 40f, 0), wheels.number("gainHue"))
        close(expected("colorwheels", "gain", 0.02f, 1), wheels.number("gainAmount"))
        close(expected("colorwheels", "gain", 0.1f, 2), wheels.number("gainLevel"))
        close(expected("colorwheels", "gamma", -120f, 0), wheels.number("gammaHue"))

        close(expected("lightglow", "strength", 0.4f), own("lightglow").number("blur"))
        close(expected("softglow", "strength", 0.2f), own("softglow").number("blur"))

        val vibrance = own("huesaturation", 0)
        close(expected("huesaturation", "vib", 0.4f), vibrance.number("vibrance"))
        close(0f, vibrance.number("hue"))
        val shift = own("huesaturation", 1)
        close(1f, shift.number("saturation"))
        close(0f, shift.number("lightness"))
        val keys = shift.prop("hue").keyframes
        assertEquals(listOf(0.1f, 0.9f), keys.map { it.t })
        close(expected("huesaturation", "hue", 200f), keys[1].v.toFloat())
        assertEquals("elastic 0.25 1 0.5 1", keys[1].e)

        close(expected("temperature", "temperature", 4.5f), own("temperature").number("kelvin"))
        assertEquals("true", own("threshold").prop("invert")["value"])
        close(0.4f, own("threshold").number("level"))
        assertEquals("true", own("gradientmap").prop("reverse")["value"])
        assertEquals("false", own("gradientmap").prop("scaleBrightness")["value"])
        assertEquals("#ff0000ff", own("gradientmap").prop("color1")["value"])
        close(3f, own("blink").number("frequency"))
        close(expected("solid", "blendMode", 1f), own("solid").number("blend"))
        close(0.75f, own("solid").number("amount"))
        close(expected("stretch", "smooth", 5f), own("stretch").number("spread"))
    }

    @Test fun droppedSettingsAreReportedUnlessTheyHoldTheirNeutralValue() {
        val dropped = migration.migrate(Scene.parse(scene)).dropped
        assertFalse(dropped.toString(), dropped.any { it.endsWith(": scaleBrightness") })
        assertTrue(dropped.toString(), dropped.any { it.endsWith(": mystery") })
        assertFalse(dropped.toString(), dropped.any { it.endsWith(": blendMode") || it.endsWith(": squeeze") || it.endsWith(": halo") })
        assertFalse(dropped.toString(), dropped.any { it.contains("(3)") })
    }

    @Test fun aSecondMigrationChangesNothing() {
        val once = migration.migrate(Scene.parse(scene))
        val twice = migration.migrate(once.scene)
        assertFalse(twice.changed)
        assertTrue(twice.dropped.isEmpty())
        assertEquals(once.scene.node, twice.scene.node)
        assertEquals(once.scene.node, migration.migrate(Scene.parse(once.scene.node.toXml())).scene.node)
    }

    private val notImported = listOf(R.string.library_report_unknown, R.string.library_report_cannot_run, R.string.library_report_dropped,
        R.string.library_report_media, R.string.library_report_fonts).map { txt(it) }.toSet()

    @Test fun theImporterConvertsAndReports() {
        val store = ProjectStore(tmp.newFolder())
        val report = Importer(store, migration = migration, canRun = { catalog.find(it) != null }).import { scene.byteInputStream() }.single()
        val sections = report.sections.toMap()
        assertEquals(listOf(UiText.plural(R.plurals.library_report_effect_layers, 1, "unknown", "1")), sections[txt(R.string.library_report_cannot_run)])
        assertTrue(sections.keys.toString(), sections.keys.all { it in notImported })
        assertTrue(sections[txt(R.string.library_report_dropped)]!!.isNotEmpty())
        assertEquals(20, effects(store.load(report.id)!!.node).size)
    }

    private val alightMoves: String get() = File(TestData.root, "alight/move-transform.xml").readText()

    @Test fun alightMoveEffectsImportWithTheirSettings() {
        val result = migration.migrate(Scene.parse(alightMoves))
        val all = effects(result.scene.node)
        val moves = listOf("autoshake", "movealongpath", "offset", "oscillate", "pulsesize", "randomdisplace", "randomjitter", "swing", "spin",
            "stretchaxis", "pulseopacity")
        assertEquals(moves.map { "absent.$it" } + listOf("absent.rastertransform", "com.alightcreative.effects.scaleassist"), all.map { it["id"] })
        assertTrue(result.dropped.toString(), result.dropped.isEmpty())
        assertEquals(moves.size + 1, result.converted.values.sum())
        assertTrue(all.take(moves.size).all { it["hidden"] == "true" && it["locallyApplied"] == "true" })
        fun own(key: String) = all.single { it["id"] == "absent.$key" }
        fun numbers(key: String) = own(key).children.filter { it["type"] == "float" }.associate { it["name"]!! to it["value"]!!.toFloat() }
        for (effect in all.take(moves.size)) {
            val definition = catalog.find(effect["id"]!!)!!
            for (p in effect.children) assertEquals("${definition.key}.${p["name"]}", definition.params.single { it.id == p["name"] }.type, p["type"])
        }
        assertEquals(mapOf("magnitude" to 50f, "frequency" to 2f, "evolution" to 0f, "seed" to 0f, "angle" to 45f, "slack" to 0.25f, "zshake" to 0f, "pattern" to 1f), numbers("autoshake"))
        assertEquals(mapOf("progress" to 0f, "orientation" to 0f, "inset" to 0f), numbers("movealongpath"))
        assertEquals("true", own("movealongpath").prop("rotate")["value"])
        assertEquals(mapOf("feather" to 0f), numbers("offset"))
        assertEquals("false", own("offset").prop("mask")["value"])
        assertEquals(mapOf("direction" to 0f, "angle" to 45f, "frequency" to 2f, "magnitude" to 25f, "wave" to 0f, "phase" to 0f), numbers("oscillate"))
        assertEquals(mapOf("frequency" to 2f, "shrink" to 0.9f, "grow" to 1.1f, "phase" to 0f, "wave" to 0f), numbers("pulsesize"))
        assertEquals(mapOf("magnitude" to 50f, "evolution" to 0f, "seed" to 0f, "scatter" to 0.5f, "speed" to 0f), numbers("randomdisplace"))
        assertEquals(mapOf("angle" to 45f, "frequency" to 30f, "magnitude" to 25f, "seed" to 0f, "slack" to 0f, "zjitter" to 0f), numbers("randomjitter"))
        assertEquals(mapOf("frequency" to 5.98f, "angle1" to -30f, "angle2" to 30f, "phase" to 0f, "wave" to 0f), numbers("swing"))
        assertEquals(mapOf("rpm" to -936f), numbers("spin"))
        assertEquals(mapOf("scale" to 18.51f, "angle" to 0f), numbers("stretchaxis"))
        assertEquals("false", own("stretchaxis").prop("mask")["value"])
        assertEquals(mapOf("frequency" to 5.98f, "strength" to 1f, "phase" to 0f, "wave" to 0f), numbers("pulseopacity"))
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }

    @Test fun anAlightProjectWithMoveEffectsImportsWithMotion() {
        val store = ProjectStore(tmp.newFolder())
        val report = Importer(store, migration = migration, canRun = { catalog.find(it) != null }).import { alightMoves.byteInputStream() }.single()
        val sections = report.sections.toMap()
        assertEquals(setOf(UiText.plural(R.plurals.library_report_effect_layers, 1, "scaleassist", "1")), sections[txt(R.string.library_report_cannot_run)]!!.toSet())
        assertTrue(sections.keys.toString(), sections.keys.all { it in notImported })
        assertNull(sections[txt(R.string.library_report_dropped)])
        assertEquals(13, effects(store.load(report.id)!!.node).size)
    }

    @Test fun anAlightFlickerOnlyCarriesOverItsTimeBasis() {
        fun flicker(basis: Int) = migration.migrate(Scene.parse("""<scene width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" s=".rect">""" +
            """<effect id="com.alightcreative.effects.flicker2" locallyApplied="true"><property name="freq" type="float" value="3.73" /><property name="basis" type="int" value="$basis" /></effect></shape></scene>"""))
        val time = flicker(0)
        assertTrue(time.dropped.isEmpty())
        assertEquals(listOf("speed", "intensity", "seed"), time.scene.node.children.single().children.single().children.map { it["name"] })
        assertEquals(setOf("Flicker: basis"), flicker(1).dropped)
    }
}
