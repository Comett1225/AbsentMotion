package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ColourKeyImportTest {
    private val pack = File("../shared/assets/effects")
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), EffectCatalog.parse { File(pack, it).inputStream() })
    private val cga4 = "000000 55ffff ff55ff ffffff"
    private val cga16 = "000000 0000aa 00aa00 00aaaa aa0000 aa00aa aa5500 aaaaaa 555555 5555ff 55ff55 55ffff ff5555 ff55ff ffff55 ffffff"

    private fun result(id: String, vararg props: Pair<String, String>): EffectMigration.Result = migration.migrate(Scene.parse(
        """<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
        <effect id="com.alightcreative.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
        <property name="size" type="vec2" value="110,82.5" /></shape></scene>""",
    ))

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode =
        result(id, *props).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.text(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.number(name: String): Float = text(name)!!.split(',').first().trim().toFloat()

    private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

    @Test fun brightnessContrastLeftAloneDoesNothingAndTheOldIdConvertsLikeTheNewOne() {
        for (id in listOf("effects.brightcont", "effects.brightcont2")) {
            val plain = migrate(id)
            assertEquals("absent.brightnesscontrast", plain["id"])
            close(0f, plain.number("brightness"))
            close(0f, plain.number("contrast"))
            close(1f, plain.number("style"))
            val set = migrate(id, "brightness" to "0.25", "contrast" to "0.75")
            close(0.25f, set.number("brightness"))
            close(0.75f, set.number("contrast"))
        }
    }

    @Test fun hueShiftTurnsColoursAroundKeepingVideoBrightness() {
        val e = migrate("effects.hueshift", "hue" to "120")
        close(-120f, e.number("hue"))
        close(2f, e.number("style"))
        close(1f, e.number("saturation"))
    }

    @Test fun colorTintBecomesColorizeKeepingBrightness() {
        val plain = migrate("effects.colortint")
        assertEquals("absent.colorize", plain["id"])
        close(0f, plain.number("amount"))
        close(1f, plain.number("style"))
        val tint = result("effects.colortint", "tint" to "-150,0.75,0")
        val e = tint.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        close(-150f, e.number("hue"))
        close(0.75f, e.number("amount"))
        assertTrue(tint.dropped.toString(), tint.dropped.isEmpty())
        assertEquals(setOf("Colorize: tint (3)"), result("effects.colortint", "tint" to "30,1,0.5").dropped)
    }

    @Test fun colorizeAndHotColorStartFromAlightsDefaults() {
        close(0f, migrate("effects.colorize").number("amount"))
        val hot = migrate("effects.colorhot")
        close(0f, hot.number("shadowHue"))
        close(60f, hot.number("highlightHue"))
        close(1f, hot.number("style"))
        val grey = migrate("effects.colorhot2")
        assertEquals("absent.splittone", grey["id"])
        close(0f, grey.number("shadowAmount"))
        close(0f, grey.number("highlightAmount"))
        close(1f, grey.number("style"))
    }

    @Test fun paletteMapUsesAlightsPresetPalettes() {
        val plain = migrate("effects.palettemap")
        close(1f, plain.number("source"))
        assertEquals(cga4, plain.text("palette"))
        val sixteen = migrate("effects.palettemap", "palette" to "2", "alpha" to "0.75")
        assertEquals(cga16, sixteen.text("palette"))
        close(0.75f, sixteen.number("alpha"))
        val unknown = result("effects.palettemap", "palette" to "1")
        assertEquals(cga4, unknown.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }.text("palette"))
        assertTrue(unknown.dropped.toString(), unknown.dropped.any { it.endsWith(": palette") })
    }

    @Test fun lumaKeysStartFromAlightsDefaultsAndFeatherOutsideTheirRange() {
        for (id in listOf("effects.lumakey2", "effects.lumakey3")) {
            val plain = migrate(id)
            close(0f, plain.number("threshold"))
            close(0f, plain.number("softness"))
        }
        val oldPlain = migrate("effects.lumakey")
        close(0.5f, oldPlain.number("threshold"))
        close(0.05f, oldPlain.number("softness"))
        close(1f, oldPlain.number("style"))
        val band = migrate("effects.lumakey3", "lowThreshold" to "0.25", "highThreshold" to "0.75", "feather" to "0.2875", "invert" to "true")
        close(0.25f - 0.645f * 0.2875f, band.number("threshold"))
        close(0.645f * 0.2875f, band.number("softness"))
        close(0.75f, band.number("high"))
        assertEquals("true", band.text("invert"))
        val old = migrate("effects.lumakey", "threshold" to "0.625", "feather" to "0.225")
        close(0.625f, old.number("threshold"))
        close(0.225f, old.number("softness"))
        assertFalse(old.children.any { it["name"] == "high" })
    }

    @Test fun keysAndColourMapsGetTheirImportOnlyChoices() {
        val chroma = migrate("effects.chromakey")
        close(0f, chroma.number("tolerance"))
        close(0f, chroma.number("softness"))
        val spectral = migrate("spectralmap", "saturation" to "0.25")
        close(1f, spectral.number("style"))
        close(1.5f, spectral.number("saturation"))
        assertEquals(listOf("#ff0000ff", "#ff00ff00", "#ffff0000"), listOf("color1", "color2", "color3").map { spectral.text(it) })
        val spot = migrate("spotcolor")
        close(1f, spot.number("grey"))
        close(2f, spot.number("choke"))
        val spot2 = migrate("spotcolor2", "saturation" to "0.25", "choke" to "8.75")
        close(1f, spot2.number("grey"))
        close(1f, spot2.number("saturation"))
        close(8.75f, spot2.number("choke"))
        val replace = migrate("replacecolor")
        close(1f, replace.number("match"))
        close(2f, replace.number("choke"))
        close(0f, replace.number("threshold"))
        close(0f, replace.number("feather"))
        val swap = migrate("replacecolor", "threshold" to "1.1875", "feather" to "0.6875", "lockLuminance" to "true")
        close(1.1875f * 0.4f, swap.number("threshold"))
        close(0.6875f * 0.5f, swap.number("feather"))
        close(2f, swap.number("choke"))
        assertEquals("true", swap.text("lockLuminance"))
    }

    @Test fun fourColorGradientStartsFromAlightsCornerColoursAndSmoothFalloff() {
        val e = migrate("fourcolorgradient")
        assertEquals(listOf("#ffff0000", "#ff00ff00", "#ff0000ff", "#ffff00ff"), listOf("color1", "color2", "color3", "color4").map { e.text(it) })
        assertEquals(listOf(listOf(0f, -500f), listOf(500f, -500f), listOf(500f, 0f), listOf(0f, 0f)),
            listOf("point1", "point2", "point3", "point4").map { p -> e.text(p)!!.split(',').map { it.trim().toFloat() } })
        close(1f, e.number("style"))
        close(0.75f, e.number("opacity"))
        close(1f, e.number("softness"))
        close(1f, e.number("falloff"))
        assertEquals("true", e.text("corner"))
        val varied = migrate("fourcolorgradient", "blend" to "0.5")
        close(0.5f, varied.number("softness"))
        close(1f, varied.number("falloff"))
        assertFalse(migrate("fourcolorgradientnew").children.any { it["name"] == "falloff" })
    }
}
