package app.absentmotion.effects

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.steppedMs
import app.absentmotion.model.videoTimeUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MoveEffectsTest {
    private val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }

    private fun stepped(fps: XmlNode, hidden: Boolean = false) = Layer(XmlNode("shape", mapOf("id" to "3", "startTime" to "1000", "endTime" to "3000",
        "fillType" to "media", "fillVideo" to "file:///v.mp4", "inTime" to "0", "outTime" to "5000"),
        listOf(XmlNode("effect", mapOf("id" to "absent.timequant", "hidden" to "$hidden"), listOf(fps)))))

    private fun EffectParam.range() = Triple(min, max, default.toFloat())

    private fun fps(value: String) = XmlNode("property", mapOf("name" to "fps", "type" to "float", "value" to value))

    @Test fun theMoveCategoryHoldsTheMotionEffects() {
        val moves = catalog.listed("move").map { it.key }.toSet()
        assertEquals(setOf("autoshake", "randomdisplace", "randomjitter", "oscillate", "swing", "spin", "pulsesize", "pulseopacity",
            "stretchaxis", "echo", "timequant"), moves)
        assertTrue(catalog.listed("move").all { it.native && it.sksl == null })
        assertEquals("Move", EffectCatalog.Categories["move"])
    }

    @Test fun oscillateMatchesTheGuide() {
        val oscillate = catalog.find("absent.oscillate")!!.params.associateBy { it.id }
        assertEquals(listOf("angle", "frequency", "magnitude", "wave", "phase"), catalog.find("absent.oscillate")!!.params.map { it.id })
        assertEquals(Triple(-3600f, 3600f, 45f), oscillate.getValue("angle").range())
        assertEquals(Triple(0f, 16f, 2f), oscillate.getValue("frequency").range())
        assertEquals(Triple(0f, 4000f, 25f), oscillate.getValue("magnitude").range())
        assertEquals(listOf("Sine", "Triangle"), oscillate.getValue("wave").options)
        assertEquals(Triple(0f, 1000f, 0f), oscillate.getValue("phase").range())
        val echo = catalog.find("absent.echo")!!.params.associateBy { it.id }
        assertEquals(Triple(-60f, 60f, 0.5f), echo.getValue("seconds").range())
        assertEquals(Triple(0f, 20f, 1f), echo.getValue("count").range())
        assertEquals(listOf("Behind", "Atop"), echo.getValue("composite").options)
    }

    @Test fun timeQuantizationStepsTheLayerTime() {
        val layer = stepped(fps("4"))
        assertEquals(listOf(1000.0, 1000.0, 1250.0, 1250.0, 1500.0, 2750.0), listOf(1000.0, 1249.0, 1250.0, 1499.9, 1500.0, 2999.0).map(layer::steppedMs))
        assertEquals(1700.0, stepped(fps("4"), hidden = true).steppedMs(1700.0), 0.0)
        assertEquals(1700.0, stepped(fps("0")).steppedMs(1700.0), 0.0)
        val keyed = XmlNode("property", mapOf("name" to "fps", "type" to "float"), listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "2")), XmlNode("kf", mapOf("t" to "0.5", "v" to "2")),
            XmlNode("kf", mapOf("t" to "0.5001", "v" to "10")), XmlNode("kf", mapOf("t" to "1", "v" to "10"))))
        assertEquals(1500.0, stepped(keyed).steppedMs(1900.0), 1e-9)
        assertEquals(2500.0, stepped(keyed).steppedMs(2550.0), 1e-9)
    }

    @Test fun aSteppedVideoHoldsItsFrameForTheWholeStep() {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="4000"/>""")
        val layer = stepped(fps("5"))
        val shown = (1000 until 1400 step 33).map { layer.videoTimeUs(it, scene) }
        assertEquals(setOf(shown[0]), shown.take(6).toSet())
        assertNotEquals(shown[0], shown.last())
        val plain = Layer(layer.node.copy(children = emptyList()))
        assertEquals(6, (1000 until 1200 step 33).map { plain.videoTimeUs(it, scene) }.toSet().size)
    }
}
