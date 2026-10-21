package app.absentmotion.effects

import app.absentmotion.shapeLayer
import app.absentmotion.compat.EffectMigration
import app.absentmotion.model.Layer
import app.absentmotion.model.MOSH_STEPS
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.moshPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatamoshTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="12000"/>""")
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }

    private fun property(name: String, value: String) = XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value))

    private fun layer(vararg params: Pair<String, String>, hidden: Boolean = false, fill: String = "fillVideo") =
        shapeLayer(id = 3, start = 1000, end = 12000, attrs = mapOf("fillType" to "media", fill to "file:///v.mp4", "inTime" to "0", "outTime" to "20000"),
            children = listOf(XmlNode("effect", mapOf("id" to "absent.datamosh", "hidden" to "$hidden"), params.map { (name, value) -> property(name, value) })))

    @Test fun theEffectIsAStylizeShaderWithItsParameters() {
        val mosh = catalog.find("absent.datamosh")!!
        assertEquals("stylize", mosh.category)
        assertEquals("3.0.0", mosh.since)
        assertTrue(!mosh.native && mosh.sksl!!.contains("uniform float end;") && mosh.sksl!!.contains("uniform float duration;"))
        assertEquals(listOf("Start", "End", "Intensity", "Block Size", "Decay", "Glitch", "Bloom"), mosh.params.map { it.label })
        assertEquals(listOf("s", "s"), mosh.params.take(2).map { it.unit })
    }

    @Test fun theWindowRunsFromStartToEnd() {
        val mosh = layer("start" to "0.5", "end" to "1.5")
        assertNull(mosh.moshPlan(1499.0, scene))
        assertEquals(listOf(1500), mosh.moshPlan(1500.0, scene)!!.frames)
        val plan = mosh.moshPlan(1800.0, scene)!!
        assertEquals((45..54).map { scene.frameStart(it) }, plan.frames)
        assertArrayEquals(floatArrayOf(1f, 16f, 0.05f, 0.1f, 0.3f), plan.params, 1e-6f)
        assertNull(mosh.moshPlan(2500.0, scene))
        assertEquals(30, mosh.moshPlan(2499.0, scene)!!.frames.size)
    }

    @Test fun theWindowStaysInsideTheLayerAndEndsNoEarlierThanItStarts() {
        val late = layer("start" to "9", "end" to "50")
        assertTrue(late.moshPlan(11999.0, scene) != null)
        assertEquals(scene.frameStart(300), late.moshPlan(11999.0, scene)!!.frames.first())
        val past = layer("start" to "40", "end" to "60")
        assertNull(past.moshPlan(11999.0, scene))
        val backwards = layer("start" to "2", "end" to "1")
        assertTrue(listOf(1500.0, 2000.0, 2999.0, 3000.0, 3500.0).all { backwards.moshPlan(it, scene) == null })
    }

    @Test fun longWindowsAreSampledInAtMostSixtySteps() {
        val mosh = layer("end" to "10", "intensity" to "2", "block" to "8")
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

    @Test fun projectsWithTheOldLengthOpenWithStartPlusLengthAsTheEnd() {
        val keyed = XmlNode("property", mapOf("name" to "duration", "type" to "float"), listOf(
            XmlNode("kf", mapOf("t" to "0.000000", "v" to "1.000000")), XmlNode("kf", mapOf("t" to "1.000000", "v" to "3.000000", "e" to "cubicBezier 0.42 0 0.58 1"))))
        val effects = listOf(
            XmlNode("effect", mapOf("id" to "absent.datamosh", "hidden" to "false"),
                listOf(property("start", "0.5"), property("duration", "1"), property("intensity", "2"))),
            XmlNode("effect", mapOf("id" to "absent.datamosh", "hidden" to "true"), listOf(property("start", "0.25"), keyed)),
            XmlNode("effect", mapOf("id" to "absent.datamosh", "hidden" to "false"), listOf(property("duration", "2.5"))),
        )
        val old = Scene(XmlNode("scene", mapOf("width" to "100", "height" to "100", "fps" to "30", "totalTime" to "12000"),
            listOf(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "12000", "s" to ".rect"), effects))))
        val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)
        val result = migration.migrate(old)
        val converted = result.scene.node.children.single().children
        fun values(i: Int) = converted[i].children.associate { it["name"]!! to it["value"]?.toFloat() }
        assertEquals(mapOf("start" to 0.5f, "end" to 1.5f, "intensity" to 2f), values(0))
        assertEquals(listOf(1.25f, 3.25f), converted[1].children[1].keyframes.map { it.v.toFloat() })
        assertEquals(listOf("start", "end"), converted[1].children.map { it["name"] })
        assertEquals("cubicBezier 0.42 0 0.58 1", converted[1].children[1].keyframes[1].e)
        assertEquals("true", converted[1]["hidden"])
        assertEquals(mapOf("end" to 2.5f), values(2))
        assertTrue(result.dropped.isEmpty())
        assertEquals(result.scene.node, migration.migrate(result.scene).scene.node)
        val plan = Layer(result.scene.node.children.single().copy(attrs = mapOf("id" to "1", "startTime" to "0", "endTime" to "12000", "fillType" to "media",
            "fillVideo" to "file:///v.mp4", "inTime" to "0", "outTime" to "20000"), children = listOf(converted[0])))
        assertNull(plan.moshPlan(499.0, scene))
        assertTrue(plan.moshPlan(1499.0, scene) != null)
        assertNull(plan.moshPlan(1500.0, scene))
    }

    @Test fun thePanelKeepsStartAndEndInsideTheLayerAndEndAfterStart() {
        val mosh = catalog.find("absent.datamosh")!!
        val (start, end) = mosh.params
        val values = mapOf("start" to 1.2f, "end" to 0.4f)
        val shownStart = mosh.bounded(start, 5f) { values.getValue(it.id) }
        val shownEnd = mosh.bounded(end, 5f) { values.getValue(it.id) }
        assertEquals(0f to 5f, shownStart.min to shownStart.max)
        assertEquals(1.2f to 5f, shownEnd.min to shownEnd.max)
        assertEquals(3f to 3f, mosh.bounded(end, 3f) { if (it.id == "start") 7f else 0f }.let { it.min to it.max })
        val intensity = mosh.params.first { it.id == "intensity" }
        assertSame(intensity, mosh.bounded(intensity, 5f) { 0f })
    }
}
