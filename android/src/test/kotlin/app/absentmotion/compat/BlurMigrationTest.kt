package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlurMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun result(id: String, vararg props: Pair<String, String>, width: Int = 1080, height: Int = 1080) =
        migration.migrate(Scene.parse("""<scene title="p" width="$width" height="$height" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            <effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
            <property name="size" type="vec2" value="110,82.5" /></shape></scene>"""))

    private fun migrate(id: String, vararg props: Pair<String, String>, width: Int = 1080, height: Int = 1080): XmlNode =
        result(id, *props, width = width, height = height).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.value(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.numbers(name: String): List<Float> = value(name)!!.split(',').map { it.trim().toFloat() }

    private fun XmlNode.number(name: String): Float = numbers(name).first()

    @Test fun olderZoomBlurKeepsItsCenterAndDrawsEvenStreaksUnlessAdaptive() {
        val d = migrate("zoomblur")
        assertEquals("absent.radialblur", d["id"])
        assertEquals(0.15f, d.number("zoom"), 1e-5f)
        assertEquals("false", d.value("adaptive"))
        val v = migrate("zoomblur", "strength" to "0.3625", "cx" to "0.625", "cy" to "0.625", "adaptive" to "true")
        assertEquals(0.3625f, v.number("zoom"), 1e-5f)
        assertEquals(listOf(0.625f, 0.375f), v.numbers("center"))
        assertEquals("true", v.value("adaptive"))
        assertEquals(listOf(0.25f, 0.5f), migrate("zoomblur", "cx" to "0.25").numbers("center"))
        assertTrue(result("zoomblur", "cx" to "0.3", "cy" to "0.7").dropped.isEmpty())
    }

    @Test fun newerZoomBlursMoveTheCenterAFiveHundredthPerUnitAndTurnAdaptiveOffIntoEvenStreaks() {
        for (id in listOf("zoomblur2", "zoomblur3")) {
            val d = migrate(id)
            assertEquals(0.15f, d.number("zoom"), 1e-5f)
            assertNull(d.value("adaptive"))
            val v = migrate(id, "centerPoint" to "40,-25", "strength" to "0.3625", "adaptive" to "false")
            assertEquals(0.3625f, v.number("zoom"), 1e-5f)
            assertEquals(0.58f, v.numbers("center")[0], 1e-5f)
            assertEquals(0.45f, v.numbers("center")[1], 1e-5f)
            assertEquals(1f, v.number("style"), 0f)
            migrate(id, "centerPoint" to "0,0").numbers("center").forEach { assertEquals(0.5f, it, 1e-5f) }
            assertEquals("false", v.value("adaptive"))
            assertEquals("true", migrate(id, "adaptive" to "true").value("adaptive"))
            assertEquals(1f, migrate(id, "strength" to "1.5").number("zoom"), 0f)
        }
    }

    @Test fun savedZoomBlursKeepTheirLookWithAlightsSettings() {
        fun own(vararg props: Pair<String, String>) = migration.migrate(Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            <effect id="absent.radialblur">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
            <property name="size" type="vec2" value="110,82.5" /></shape></scene>""")).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        val spread = own("center" to "0.3,0.6", "amount" to "0.4", "streak" to "0")
        assertEquals(0.5f, spread.number("zoom"), 1e-5f)
        assertEquals("true", spread.value("adaptive"))
        assertEquals(listOf(0.3f, 0.6f), spread.numbers("center"))
        assertNull(spread.value("amount"))
        assertNull(spread.value("style"))
        val even = own("amount" to "1.2", "streak" to "1")
        assertEquals(0.3f, even.number("zoom"), 1e-5f)
        assertEquals("false", even.value("adaptive"))
        assertEquals(0.25f, own("amount" to "0.2").number("zoom"), 1e-5f)
        assertEquals(0.5f, own("strength" to "2").number("zoom"), 1e-5f)
        assertEquals(0.3f, own("zoom" to "0.3", "adaptive" to "true").number("zoom"), 0f)
    }

    @Test fun directionalBlurIsCenteredAndMeasuredOnTheScreen() {
        val d = migrate("dblur")
        assertEquals("absent.directionalblur", d["id"])
        assertEquals(162f, d.number("blurLength"), 1e-3f)
        assertEquals(1f, d.number("spread"), 0f)
        assertEquals(391.5f, migrate("dblur", "strength" to "0.3625").number("blurLength"), 1e-3f)
        assertEquals(108f, migrate("dblur", "strength" to "0.15", width = 1280, height = 720).number("blurLength"), 1e-3f)
    }

    @Test fun streakModesTwoLeaveTheLayerAlone() {
        val linear = migrate("streaks", "strength" to "0.3625", "rmode" to "2", "gmode" to "2", "bmode" to "2", "amode" to "2")
        assertEquals(391.5f, linear.number("reach"), 1e-3f)
        assertEquals(3f, linear.number("mode"), 0f)
        assertEquals(1f, linear.number("copies"), 0f)
        assertEquals(2f, migrate("streaks", "rmode" to "0", "gmode" to "0", "bmode" to "0", "amode" to "0").number("mode"), 0f)
        assertEquals(162f, migrate("streaks").number("reach"), 1e-3f)
        for (id in listOf("streaks-spin", "streaks-zoom")) {
            val e = migrate(id, "rmode" to "2", "gmode" to "1", "bmode" to "0", "amode" to "2")
            assertEquals(listOf(3f, 1f, 0f, 3f), listOf("r", "g", "b", "a").map { e.number(it) })
        }
        assertEquals(0.4695f, migrate("streaks-zoom").number("strength"), 1e-4f)
        assertEquals(3.5f, migrate("streaks-spin").number("sweep"), 1e-5f)
        assertEquals(12f, migrate("streaks-spin", "angle" to "12").number("sweep"), 1e-5f)
    }

    @Test fun boxBlursBecomeTheBlurWithAlightsSizes() {
        assertEquals(52.64f, migrate("boxblur3").number("strength"), 1e-3f)
        val box = migrate("boxblur3", "strength" to "0.6125")
        assertEquals(201.5125f, box.number("strength"), 1e-3f)
        assertEquals(1f, box.number("shape"), 0f)
        val plain = migrate("boxblur")
        assertEquals(3.75f, plain.number("strength"), 1e-4f)
        assertEquals(1f, plain.number("shape"), 0f)
        assertEquals(1f, plain.number("style"), 0f)
        assertEquals(1f, migrate("boxblur", "strength" to "25.75", "iter" to "1").number("shape"), 0f)
        val old = migrate("boxblur", "strength" to "25.75", "iter" to "3")
        assertEquals("absent.blur", old["id"])
        assertEquals(96.5625f, old.number("strength"), 1e-3f)
        assertEquals(0f, old.number("shape"), 0f)
        assertTrue(result("boxblur", "strength" to "25.75", "iter" to "3").dropped.isEmpty())
        assertEquals(51.1125f, migrate("boxblur2", "strength" to "0.3625", "iter" to "3").number("strength"), 1e-3f)
        assertTrue(result("boxblur2", "strength" to "0.3625", "iter" to "3").dropped.isEmpty())
        assertEquals(setOf("Blur: iter"), result("boxblur2", "strength" to "0.3625", "iter" to "5").dropped)
    }

    @Test fun sharpenKeepsTheRadiusAndUnsharpMaskShowsAlightsRedMask() {
        val s = migrate("sharpen", "strength" to "5.75", "radius" to "3.25")
        assertEquals(23f, s.number("amount"), 1e-3f)
        assertEquals(1f, s.number("style"), 0f)
        assertEquals(3.25f, s.number("radius"), 1e-5f)
        val u = migrate("unsharpmask", "strength" to "0.515", "showMask" to "true")
        assertEquals(1.2875f, u.number("radius"), 1e-5f)
        assertEquals(1f, u.number("maskColor"), 0f)
        assertEquals("true", u.value("showMask"))
    }

    @Test fun newChoicesKeepTheOldLookByDefault() {
        for ((effect, param) in listOf("directionalblur" to "spread", "unsharpmask" to "maskColor", "blur" to "shape", "linearstreaks" to "copies")) {
            val p = catalog.find("absent.$effect")!!.params.single { it.id == param }
            assertEquals(0f, p.default.toFloat(), 0f)
            assertEquals(p.default, p.missingDefault)
        }
        val adaptive = catalog.find("absent.radialblur")!!.params.single { it.id == "adaptive" }
        assertEquals("true", adaptive.default)
        assertEquals(adaptive.default, adaptive.missingDefault)
        assertEquals(listOf("Lighten", "Average", "Darken", "Off"), catalog.find("absent.linearstreaks")!!.params.single { it.id == "mode" }.options)
    }
}
