package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
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
            "stretchaxis", "offset", "movealongpath", "echo", "timequant", "shutterblur"), moves)
        assertEquals(setOf("stretchaxis", "offset"), catalog.listed("move").filterNot { it.native && it.sksl == null }.map { it.key }.toSet())
        assertEquals("Move", EffectCatalog.Categories["move"])
    }

    private fun params(key: String) = catalog.find("absent.$key")!!.params.map { p ->
        listOf(p.label, p.kind, p.default, if (p.kind in setOf("float", "angle", "point")) "${p.min}..${p.max}" else p.options.joinToString("/"), p.unit + if (p.percent) "%" else "")
    }

    private fun row(label: String, kind: String, default: String, range: String = "", unit: String = "") = listOf(label, kind, default, range, unit)

    @Test fun theMoveEffectsUseAlightsParameters() {
        val wave = row("Wave", "choice", "0.000000", "Sine/Triangle")
        assertEquals(listOf(row("Magnitude", "float", "50.000000", "0.0..2000.0"), row("Frequency", "float", "2.000000", "0.0..16.0", "Hz"),
            row("Evolution", "float", "0.000000", "0.0..2000.0"), row("Seed", "float", "0.000000", "0.0..5.0"), row("Angle", "angle", "45.000000", "-3600.0..3600.0"),
            row("Slack", "float", "0.250000", "0.0..1.0", "%"), row("Z Shake", "float", "0.000000", "0.0..2000.0")), params("autoshake"))
        assertEquals(listOf(row("Magnitude", "float", "50.000000", "0.0..2000.0"), row("Evolution", "float", "0.000000", "0.0..2000.0"),
            row("Seed", "float", "0.000000", "0.0..5.0"), row("Scatter", "float", "0.500000", "0.0..2.0", "%")), params("randomdisplace"))
        assertEquals(listOf(row("Angle", "angle", "45.000000", "-3600.0..3600.0"), row("Frequency", "float", "30.000000", "1.0..60.0"),
            row("Magnitude", "float", "25.000000", "1.0..2000.0"), row("Seed", "float", "0.000000", "0.0..1.0"), row("Slack", "float", "0.000000", "0.0..1.0", "%"),
            row("Z Jitter", "float", "0.000000", "0.0..2000.0")), params("randomjitter"))
        assertEquals(listOf(row("Direction", "choice", "0.000000", "Angle/Depth/Orbit"), row("Angle", "angle", "45.000000", "-3600.0..3600.0"),
            row("Frequency", "float", "2.000000", "0.0..16.0", "Hz"), row("Magnitude", "float", "25.000000", "0.0..4000.0"), wave,
            row("Phase", "float", "0.000000", "0.0..1000.0")), params("oscillate"))
        assertEquals(listOf(row("Frequency", "float", "2.000000", "0.1..16.0", "Hz"), row("Angle 1", "angle", "-30.000000", "-3600.0..3600.0"),
            row("Angle 2", "angle", "30.000000", "-3600.0..3600.0"), row("Phase", "float", "0.000000", "0.0..2.0"), wave), params("swing"))
        assertEquals(listOf(row("RPM", "float", "60.000000", "-3600.0..3600.0")), params("spin"))
        assertEquals(listOf(row("Frequency", "float", "2.000000", "0.0..16.0", "Hz"), row("Shrink", "float", "0.900000", "0.0..1.0"),
            row("Grow", "float", "1.100000", "1.0..2.0"), row("Starting Phase", "float", "0.000000", "0.0..2.0"), wave), params("pulsesize"))
        assertEquals(listOf(row("Frequency", "float", "2.000000", "0.1..16.0", "Hz"), row("Strength", "float", "1.000000", "0.0..1.0", "%"),
            row("Starting Phase", "float", "0.000000", "0.0..2.0"), wave), params("pulseopacity"))
        assertEquals(listOf(row("Scale", "float", "1.000000", "0.01..50.0"), row("Angle", "angle", "0.000000", "-3600.0..3600.0"),
            row("Mask To Layer", "switch", "false")), params("stretchaxis"))
        assertEquals(listOf(row("Offset", "point", "0.000000,0.000000", "-5000.0..5000.0"), row("Feather", "float", "0.000000", "0.0..1.0"),
            row("Mask To Layer", "switch", "false")), params("offset"))
        assertEquals(listOf(row("Progress", "float", "0.000000", "0.0..1.0", "%"), row("Orientation", "angle", "0.000000", "-3600.0..3600.0"),
            row("Rotate Automatically", "switch", "true"), row("Inset/Outset", "float", "0.000000", "-500.0..500.0"),
            row("Offset", "point", "0.000000,0.000000", "-5000.0..5000.0")), params("movealongpath"))
    }

    private fun older(effect: String, vararg values: Pair<String, String>) = XmlNode("effect", mapOf("id" to "absent.$effect", "hidden" to "false"),
        values.map { (name, value) -> XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value)) })

    @Test fun projectsMadeWithTheFirstMoveEffectsStillLoad() {
        val migration = EffectMigration.parse(File("src/main/assets/effects/foreign.json").readText(), catalog)
        val made = listOf(
            older("autoshake", "magnitude" to "20", "rotation" to "3", "frequency" to "6", "seed" to "1"),
            older("randomdisplace", "magnitude" to "50", "speed" to "1", "seed" to "1"),
            older("randomjitter", "angle" to "10", "magnitude" to "25", "rate" to "8", "seed" to "1"),
            older("swing", "angle" to "15", "frequency" to "1", "wave" to "1", "phase" to "0.25"),
            older("spin", "speed" to "90", "direction" to "0"),
            older("pulsesize", "amount" to "0.2", "frequency" to "1", "wave" to "0", "phase" to "0.5"),
            older("stretchaxis", "angle" to "30", "amount" to "1.5"),
            older("oscillate", "angle" to "45", "frequency" to "2", "magnitude" to "25", "wave" to "0", "phase" to "0"),
            older("pulseopacity", "minimum" to "0.2", "frequency" to "1", "wave" to "1", "phase" to "0.25"),
        )
        val scene = Scene(XmlNode("scene", mapOf("width" to "100", "height" to "100", "fps" to "30", "totalTime" to "1000"),
            listOf(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "1000", "s" to ".rect"), made))))
        val result = migration.migrate(scene)
        val effects = result.scene.node.children.single().children
        fun values(i: Int) = effects[i].children.associate { it["name"]!! to it["value"]!!.toFloat() }
        assertEquals(mapOf("magnitude" to 20f, "frequency" to 6f, "seed" to 1f), values(0))
        assertEquals(mapOf("magnitude" to 50f, "seed" to 1f), values(1))
        assertEquals(mapOf("angle" to 10f, "frequency" to 8f, "magnitude" to 25f, "seed" to 1f), values(2))
        assertEquals(mapOf("frequency" to 1f, "angle1" to -15f, "angle2" to 15f, "phase" to 0.5f, "wave" to 1f), values(3))
        assertEquals(15f, values(4).getValue("rpm"), 1e-4f)
        assertEquals(mapOf("frequency" to 1f, "shrink" to 0.8f, "grow" to 1.2f, "phase" to 1f, "wave" to 0f), values(5).mapValues { Math.round(it.value * 1000) / 1000f })
        assertEquals(mapOf("scale" to 1.5f, "angle" to 30f), values(6))
        assertEquals(made[7], effects[7])
        assertEquals(mapOf("frequency" to 1f, "strength" to 0.8f, "phase" to 1f, "wave" to 1f), values(8).mapValues { Math.round(it.value * 1000) / 1000f })
        assertEquals(setOf("Auto-Shake: rotation", "Random Displacement: speed"), result.dropped.toSet())
        assertTrue(result.converted.isEmpty())
        val again = migration.migrate(result.scene)
        assertEquals(result.scene.node, again.scene.node)
        assertTrue(again.dropped.isEmpty())
        val fresh = migration.migrate(Scene(XmlNode("scene", scene.node.attrs, listOf(XmlNode("shape", scene.node.children.single().attrs,
            catalog.listed("move").map { it.instance() })))))
        assertTrue(fresh.dropped.isEmpty())
        assertEquals(catalog.listed("move").map { it.instance() }, fresh.scene.node.children.single().children)
    }

    @Test fun oscillateMatchesTheGuide() {
        val oscillate = catalog.find("absent.oscillate")!!.params.associateBy { it.id }
        assertEquals(listOf("direction", "angle", "frequency", "magnitude", "wave", "phase"), catalog.find("absent.oscillate")!!.params.map { it.id })
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

    @Test fun trueMotionBlurDefaultsToAHalfOpenShutterAndEightSamples() {
        val blur = catalog.find("absent.shutterblur")!!
        assertEquals(listOf("shutter", "samples"), blur.params.map { it.id })
        val params = blur.params.associateBy { it.id }
        assertEquals(Triple(0f, 360f, 180f), params.getValue("shutter").range())
        assertEquals(Triple(2f, 32f, 8f), params.getValue("samples").range())
        assertEquals(1f, params.getValue("samples").step)
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
