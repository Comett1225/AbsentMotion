package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GlowEdgeShadowImportTest {
    private val pack = File("../shared/assets/effects")
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), EffectCatalog.parse { File(pack, it).inputStream() })

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode = migration.migrate(Scene.parse(
        """<scene title="g" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
        <effect id="com.alightcreative.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
        <property name="size" type="vec2" value="110,82.5" /></shape></scene>""",
    )).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.text(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.number(name: String): Float = text(name)!!.split(',').first().trim().toFloat()

    private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-3f)

    @Test fun shadowsKeepAlightsFillAsTheLayerOpacity() {
        for (id in listOf("effects.longshadow", "effects.longshadow2", "effects.radialshadow", "effects.radialshadow2")) {
            close(0.75f, migrate(id, "fill" to "0.75").number("fill"))
            assertEquals(null, migrate(id).text("fill"))
        }
    }

    @Test fun longShadowTakesOneSamplePerPixelOfLength() {
        for (id in listOf("effects.longshadow", "effects.longshadow2")) {
            val set = migrate(id, "size" to "0.3625")
            close(391.5f, set.number("reach"))
            close(362.5f, set.number("samples"))
            close(64f, migrate(id, "size" to "0.01").number("samples"))
            close(512f, migrate(id, "size" to "2").number("samples"))
            close(300f, migrate(id).number("samples"))
        }
    }

    @Test fun findEdgesDrawsInkLinesAtAlightsSpacing() {
        val plain = migrate("effects.findedges")
        assertEquals("absent.edgedetect", plain["id"])
        close(1f, plain.number("style"))
        close(4f, plain.number("strength"))
        val set = migrate("effects.findedges", "smoothing" to "3.25", "threshold" to "0.25", "invert" to "true")
        close(3.25f, set.number("spacing"))
        close(0.25f, set.number("threshold"))
        assertEquals("true", set.text("invert"))
    }

    @Test fun innerGlowTurnsHardnessIntoGainAndKeepsItsBlend() {
        val plain = migrate("inner-glow")
        close(52f, plain.number("blur"))
        close(1.2f, plain.number("intensity"))
        close(1f, plain.number("corners"))
        assertEquals("#ffbfe680", plain.text("color"))
        val set = migrate("inner-glow", "radius" to "137.5", "alpha" to "0.8125", "hardness" to "0.4", "blendMode" to "0")
        close(137.5f, set.number("blur"))
        close(1f, set.number("style"))
        close(0.8125f, set.number("opacity"))
        close(1f / 0.6f, set.number("intensity"))
        close(1f, set.number("blend"))
        close(0f, migrate("inner-glow", "blendMode" to "2").number("blend"))
    }

    @Test fun edgeGlowImportsAlightsSettingsOneToOne() {
        val glow = migrate("effects.edgeglow", "smoothing" to "7.5", "strength" to "0.25", "spread" to "4", "blend" to "0.5", "threshold" to "0.7")
        close(1f, glow.number("style"))
        close(7.5f, glow.number("smoothing"))
        close(0.25f, glow.number("strength"))
        close(4f, glow.number("spread"))
        close(0.5f, glow.number("blend"))
        close(0.7f, glow.number("threshold"))
        assertEquals(null, glow.text("mask"))
    }

    @Test fun contoursAreMeasuredAsADistanceFromTheLayer() {
        val line = migrate("contour")
        close(1f, line.number("spread"))
        close(10f, line.number("offset"))
        close(23.4f, line.number("blur"))
        close(4f, line.number("width"))
        close(0f, line.number("fill"))
        assertEquals("#ff2d1ef6", line.text("color"))
        val lines = migrate("contour", "offset" to "45", "width" to "28.75")
        close(48.015f, lines.number("offset"))
        close(1.333f * 28.75f + 1.667f * 45f, lines.number("blur"))
        close(0.9f * 28.75f, lines.number("width"))
        val gradient = migrate("contourgrad")
        close(1f, gradient.number("spread"))
        close(42.5f, gradient.number("blur"))
        close(0f, gradient.number("fill"))
        assertEquals("#fffae965", gradient.text("color1"))
        assertEquals("#fff16b52", gradient.text("color2"))
        val band = migrate("contourgrad", "offset" to "37.5", "width" to "43.75")
        close(40.0125f, band.number("offset"))
        close(1.69f * 81.25f, band.number("blur"))
    }

    @Test fun lightGlowHaloSoftensThePictureAndTheGlowStaysOnTheLayer() {
        close(1f, migrate("effects.lightglow", "halo" to "1.25").number("soften"))
        assertEquals(null, migrate("effects.lightglow").text("soften"))
        assertEquals("true", migrate("effects.lightglow").text("mask"))
    }

    @Test fun softGlowFadesWithTheLayer() {
        val plain = migrate("effects.softglow")
        close(1f, plain.number("falloff"))
        close(1f, plain.number("style"))
        close(0.5f, plain.number("brightness"))
        close(1.25f, migrate("effects.softglow", "brightness" to "1.25").number("brightness"))
    }

    @Test fun contourStripsStackFromHalfAGapLower() {
        val plain = migrate("effects.contourstrips")
        close(1f, plain.number("style"))
        close(0.5f, plain.number("phase"))
        close(250.5f, migrate("effects.contourstrips", "phase" to "250").number("phase"))
    }

    @Test fun cmykDotsAreHardAndSizedByTheScene() {
        val plain = migrate("effects.halftonedotscmyk")
        close(1f, plain.number("style"))
        close(200f * 220f / 1080f, migrate("effects.halftonedotscmyk", "amount" to "200").number("amount"))
    }
}
