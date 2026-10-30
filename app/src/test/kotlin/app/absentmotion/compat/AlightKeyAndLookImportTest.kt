package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightKeyAndLookImportTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun migrate(effect: String): Pair<XmlNode, Set<String>> {
        val scene = Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        val result = migration.migrate(scene)
        return result.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" } to result.dropped
    }

    private fun XmlNode.value(name: String) = children.single { it["name"] == name }["value"]!!
    private fun XmlNode.floats(name: String) = value(name).split(',').map { it.trim().toFloat() }

    @Test fun energyBeamKeepsItsEndsColorsAndLength() {
        val (beam, _) = migrate("""<effect id="com.alightcreative.effects.energybeam"><property name="start" type="vec2" value="-160.000000,-225.000000" /><property name="end" type="vec2" value="240.000000,175.000000" />
            <property name="color1" type="color" value="#FF3366CC" /><property name="color2" type="color" value="#FF112233" /><property name="spread" type="float" value="40" />
            <property name="alpha" type="float" value="0.75" /><property name="glowAlpha" type="float" value="0.625" /><property name="haloAlpha" type="float" value="0.475" />
            <property name="haloSpread" type="float" value="42.5" /><property name="clip" type="float" value="0.5" /><property name="progress" type="float" value="0.75" />
            <property name="fstr" type="float" value="0.265" /><property name="freq" type="float" value="20" /><property name="blendMode" type="int" value="1" /></effect>""")
        assertEquals("absent.energybeam", beam["id"])
        assertEquals(-160f / 6f, beam.floats("from")[0], 0.02f)
        assertEquals(-225f / 6f, beam.floats("from")[1], 0.02f)
        assertEquals(240f / 6f, beam.floats("to")[0], 0.02f)
        assertEquals(175f / 6f, beam.floats("to")[1], 0.02f)
        assertEquals("#FF3366CC", beam.value("color"))
        assertEquals("#FF112233", beam.value("haloColor"))
        assertEquals(listOf(6f, 0.75f, 1f, 0.475f, 42.5f, 0.5f, 0.75f, 0.265f, 6f, 0f),
            listOf("width", "dim", "glow", "halo", "haloWidth", "span", "progress", "wiggle", "frequency", "blend").map { beam.floats(it)[0] })
    }

    @Test fun lumaStamperTurnsClipsIntoBlackAndWhitePoints() {
        val (stamp, dropped) = migrate("""<effect id="com.alightcreative.effects.lumastamper"><property name="color" type="color" value="#FF3366CC" /><property name="clipLow" type="float" value="0.25" />
            <property name="clipHigh" type="float" value="0.25" /><property name="gamma" type="float" value="4" /><property name="invert" type="bool" value="true" /></effect>""")
        assertEquals("absent.lumastamp", stamp["id"])
        assertEquals("#FF3366CC", stamp.value("color"))
        assertEquals(listOf(0.25f, 0.75f, 2f), listOf("low", "high", "gamma").map { stamp.floats(it)[0] })
        assertEquals("true", stamp.value("invert"))
        assertTrue(dropped.isEmpty())
    }

    @Test fun matteFringeKeepsThicknessAndProtectedColors() {
        val (fringe, dropped) = migrate("""<effect id="com.alightcreative.effects.mattefringe"><property name="thickness" type="float" value="53.75" /><property name="feather" type="float" value="0.625" />
            <property name="excludeColor1" type="color" value="#FF3366CC" /><property name="threshold1" type="float" value="0.25" />
            <property name="excludeColor2" type="color" value="#FF00FF00" /><property name="threshold2" type="float" value="0.5" />
            <property name="excludeColor3" type="color" value="#FFFF0000" /><property name="threshold3" type="float" value="0" /></effect>""")
        assertEquals("absent.fringeclean", fringe["id"])
        assertEquals(listOf(53.75f, 0.625f, 0.25f, 0.5f, 0f), listOf("blur", "feather", "threshold1", "threshold2", "threshold3").map { fringe.floats(it)[0] })
        assertEquals(listOf("#FF3366CC", "#FF00FF00", "#FFFF0000"), listOf("color1", "color2", "color3").map { fringe.value(it) })
        assertTrue(dropped.isEmpty())
    }

    @Test fun keySpillCleanerKeepsItsStrength() {
        val (spill, dropped) = migrate("""<effect id="com.alightcreative.effects.spill-cleanup"><property name="strength" type="float" value="0.5" /></effect>""")
        assertEquals("absent.spillclean", spill["id"])
        assertEquals(0.25f, spill.floats("strength")[0], 1e-6f)
        assertTrue(dropped.isEmpty())
    }

    @Test fun hexagonArrayKeepsItsLatticeAndColors() {
        val (hex, dropped) = migrate("""<effect id="com.alightcreative.effects.hexarray"><property name="position" type="vec2" value="40.000000,-25.000000" /><property name="size" type="float" value="20" />
            <property name="stretch" type="float" value="2" /><property name="colorMode" type="int" value="1" /><property name="color1" type="color" value="#FF3366CC" /><property name="color2" type="color" value="#FF112233" />
            <property name="color3" type="color" value="#FF445566" /><property name="alpha" type="float" value="0.75" /><property name="blendMode" type="int" value="1" />
            <property name="lineWidth" type="float" value="0.5" /><property name="feather" type="float" value="0.25" /><property name="angle" type="float" value="900" /></effect>""")
        assertEquals("absent.hexgrid", hex["id"])
        assertEquals(listOf(40f, -25f), hex.floats("offset"))
        assertEquals(listOf(60f, 2f, 1f, 0.75f, 1f, 0.15f, 0.25f, 180f),
            listOf("cell", "stretch", "colors", "opacity", "blend", "gap", "feather", "angle").map { hex.floats(it)[0] })
        assertEquals(listOf("#FF3366CC", "#FF112233", "#FF445566"), listOf("color1", "color2", "color3").map { hex.value(it) })
        assertTrue(dropped.isEmpty())
    }

    @Test fun grungeKeepsStrengthAndTintAndSilentlyDropsTheTextureLayers() {
        fun grunge(active: Boolean) = migrate("""<effect id="com.alightcreative.effects.grunge"><property name="strength" type="float" value="0.75" /><property name="c1active" type="bool" value="$active" />
            <property name="fillColor" type="color" value="#FF3366CC" /><property name="fillColorBlend" type="float" value="0.25" /><property name="t1active" type="bool" value="true" />
            <property name="t3grain" type="float" value="4.75" /><property name="d2angle" type="float" value="900" /></effect>""")
        val (on, dropped) = grunge(true)
        assertEquals("absent.grunge", on["id"])
        assertEquals(listOf(0.75f, 0.25f), listOf("strength", "tint").map { on.floats(it)[0] })
        assertEquals("#FF3366CC", on.value("color"))
        assertTrue(dropped.isEmpty())
        assertEquals("#00000000", grunge(false).first.value("color"))
    }
}
