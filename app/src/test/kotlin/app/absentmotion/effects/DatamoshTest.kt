package app.absentmotion.effects

import app.absentmotion.model.Layer
import app.absentmotion.model.MOSH_STEPS
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.moshPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatamoshTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="12000"/>""")

    private fun property(name: String, value: String) = XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value))

    private fun layer(vararg params: Pair<String, String>, hidden: Boolean = false, fill: String = "fillVideo") =
        Layer(XmlNode("shape", mapOf("id" to "3", "startTime" to "1000", "endTime" to "12000", "fillType" to "media", fill to "file:///v.mp4",
            "inTime" to "0", "outTime" to "20000"), listOf(XmlNode("effect", mapOf("id" to "absent.datamosh", "hidden" to "$hidden"),
            params.map { (name, value) -> property(name, value) }))))

    @Test fun theEffectIsAStylizeShaderWithItsParameters() {
        val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }
        val mosh = catalog.find("absent.datamosh")!!
        assertEquals("stylize", mosh.category)
        assertEquals("3.0.0", mosh.since)
        assertTrue(!mosh.native && mosh.sksl!!.contains("uniform float duration;"))
        assertEquals(listOf("Start", "Length", "Intensity", "Block Size", "Decay", "Glitch", "Bloom"), mosh.params.map { it.label })
    }

    @Test fun theWindowStartsAtStartAndLastsItsLength() {
        val mosh = layer("start" to "0.5", "duration" to "1")
        assertNull(mosh.moshPlan(1499.0, scene))
        assertEquals(listOf(1500), mosh.moshPlan(1500.0, scene)!!.frames)
        val plan = mosh.moshPlan(1800.0, scene)!!
        assertEquals((45..54).map { scene.frameStart(it) }, plan.frames)
        assertArrayEquals(floatArrayOf(1f, 16f, 0.05f, 0.1f, 0.3f), plan.params, 1e-6f)
        assertNull(mosh.moshPlan(2500.0, scene))
        assertEquals(30, mosh.moshPlan(2499.0, scene)!!.frames.size)
    }

    @Test fun longWindowsAreSampledInAtMostSixtySteps() {
        val mosh = layer("duration" to "10", "intensity" to "2", "block" to "8")
        val last = mosh.moshPlan(10999.0, scene)!!
        assertEquals(MOSH_STEPS, last.frames.size)
        assertEquals(scene.frameStart(30), last.frames.first())
        assertEquals(setOf(5), last.frames.zipWithNext { a, b -> scene.frameAt(b) - scene.frameAt(a) }.toSet())
        assertEquals(mosh.moshPlan(10999.0, scene)!!.frames, last.frames)
        assertEquals(2f, last.params[0])
        assertEquals(8f, last.params[1])
    }

    @Test fun onlyVisibleVideoLayersAreMoshed() {
        assertNull(layer(hidden = true).moshPlan(1500.0, scene))
        assertNull(layer(fill = "fillImage").moshPlan(1500.0, scene))
        assertEquals(listOf(1000), layer().moshPlan(1000.0, scene)!!.frames)
    }
}
