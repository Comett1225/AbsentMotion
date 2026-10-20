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
    }

    private fun older(effect: String, vararg values: Pair<String, String>) = XmlNode("effect", mapOf("id" to "absent.$effect", "hidden" to "false"),
        values.map { (name, value) -> XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value)) })

    @Test fun projectsMadeBeforeTheRecalibrationStillLoad() {
        val made = listOf(
            older("wavewarp", "amplitude" to "21.6", "wavelength" to "100", "direction" to "30", "speed" to "2"),
            older("rgbsplit", "splitDistance" to "13.5", "angle" to "0"),
            older("radialblur", "strength" to "0.5"),
            older("unsharpmask", "strength" to "0.5", "amount" to "1", "threshold" to "0"),
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
        assertTrue(result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
    }
}
