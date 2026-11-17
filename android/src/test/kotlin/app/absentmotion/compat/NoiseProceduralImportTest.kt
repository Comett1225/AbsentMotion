package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoiseProceduralImportTest {
    private val pack = File("../shared/assets/effects")
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), EffectCatalog.parse { File(pack, it).inputStream() })

    private fun result(id: String, vararg props: Pair<String, String>) = migration.migrate(Scene.parse(
        """<scene title="n" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
        <effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>
        <property name="size" type="vec2" value="110,82.5" /></shape></scene>""",
    ))

    private fun migrate(id: String, vararg props: Pair<String, String>): XmlNode =
        result(id, *props).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.text(name: String): String? = children.firstOrNull { it["name"] == name }?.get("value")

    private fun XmlNode.numbers(name: String): List<Float> = text(name)!!.split(',').map { it.trim().toFloat() }

    private fun close(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-3f) }
    }

    @Test fun oldNoiseStartsFromAlightsHalfStrengthGrain() {
        val plain = migrate("noise")
        assertEquals("absent.noise", plain["id"])
        close(listOf(0.5f), plain.numbers("amount"))
        close(listOf(1f), plain.numbers("overshoot"))
        assertEquals("true", plain.text("mono"))
        val set = migrate("noise", "amount" to "0.625", "overshoot" to "1.25", "mono" to "false")
        close(listOf(0.625f), set.numbers("amount"))
        close(listOf(1.25f), set.numbers("overshoot"))
        assertEquals("false", set.text("mono"))
    }

    @Test fun blockNoiseKeepsTheSizeOfANegativeStretch() {
        close(listOf(10f, -10f), migrate("blocknoise2", "stretch" to "41,-24").numbers("stretch"))
        close(listOf(2f, 0.5f), migrate("blocknoise2", "stretch" to "2,0.5").numbers("stretch"))
    }

    @Test fun untouchedLightningRunsAlightsThinDiagonalBolt() {
        val plain = migrate("lightning")
        close(listOf(0.194f, 0.088f), plain.numbers("from"))
        close(listOf(0.775f, 0.864f), plain.numbers("to"))
        close(listOf(1f), plain.numbers("width"))
        close(listOf(0.02f), plain.numbers("jag"))
        close(listOf(0.35f), plain.numbers("glow"))
        assertEquals("#ff12ff00", plain.text("color"))
        val set = migrate("lightning", "start" to "-55,0", "glow" to "2", "color1" to "#FF3366CC")
        close(listOf(0.25f, 0.5f), set.numbers("from"))
        close(listOf(0.5f), set.numbers("glow"))
        assertEquals("#FF3366CC", set.text("color"))
    }

    @Test fun oldStarfieldBecomesOurStarfield() {
        val converted = result("starfield", "zoom" to "0.4725", "speed" to "0.1325", "darkmatter" to "0.45", "brightness" to "0.002375")
        assertTrue(converted.dropped.isEmpty())
        val star = converted.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        assertEquals("absent.starfield", star["id"])
        close(listOf(0.4725f), star.numbers("scale"))
        close(listOf(0.45f), star.numbers("darkMatter"))
        close(listOf(0.02375f), star.numbers("brightness"))
    }

    @Test fun fractalWarpTwoBecomesFractalWarp() {
        val warp = migrate("fractalwarp2", "mag" to "0.65", "octaves" to "6.75", "intensity" to "0.5625", "scale" to "1.75", "offsx" to "0")
        assertEquals("absent.fractalwarp", warp["id"])
        close(listOf(0.65f), warp.numbers("strength"))
        close(listOf(6.75f), warp.numbers("complexity"))
        close(listOf(1.75f), warp.numbers("scale"))
        close(listOf(1f), warp.numbers("screenSize"))
    }

    @Test fun screenSpaceWarpsAreSizedByTheScreen() {
        val warp = migrate("fractalwarp4", "mag" to "1.4", "screenSpace" to "true")
        assertEquals("true", warp.text("screenSpace"))
        close(listOf(1f), warp.numbers("screenSize"))
        listOf("turbulentdisplace", "turbulentdisplace2", "turbulentdisplace3").forEach { id ->
            val converted = result(id, "intensity" to "0.8125", "screenSpace" to "true")
            assertTrue(converted.dropped.none { it.contains("screenSpace") })
            val turb = converted.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
            assertEquals("true", turb.text("screenSpace"))
        }
    }

    @Test fun electricEdgesContoursAreThinLinesAtAlightsSpacing() {
        val edges = migrate("electricedges", "renderContours" to "true", "contourCount" to "65")
        close(listOf(17.81f), edges.numbers("count"))
        close(listOf(1f), edges.numbers("contourStyle"))
    }
}
