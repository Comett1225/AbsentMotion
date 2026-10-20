package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
import app.absentmotion.model.Scene
import app.absentmotion.model.TransitionKind
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MotionBlurTest {
    private val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }
    private val migration = EffectMigration.parse(File("src/main/assets/effects/foreign.json").readText(), catalog)

    private fun shape(id: Int, vararg effects: XmlNode) = XmlNode("shape", mapOf("id" to "$id", "startTime" to "${(id - 1) * 2000}", "endTime" to "${id * 2000}",
        "fillType" to "color", "s" to ".rect"), listOf(XmlNode("transform", children = listOf(
        XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(540f, 960f, 0f)))),
        XmlNode("scale", mapOf("value" to formatFloats(floatArrayOf(1f, 1f)))),
        XmlNode("rotation", mapOf("value" to formatFloats(floatArrayOf(0f)))),
        XmlNode("opacity", mapOf("value" to formatFloats(floatArrayOf(1f)))),
    ))) + effects)

    private fun scene(vararg shapes: XmlNode) = Scene(XmlNode("scene", mapOf("width" to "1080", "height" to "1920", "fps" to "30", "totalTime" to "4000"), shapes.toList()))

    private val directional = XmlNode("effect", mapOf("id" to "absent.motionblur", "hidden" to "false"), listOf(
        XmlNode("property", mapOf("name" to "angle", "type" to "float", "value" to "30.000000")),
        XmlNode("property", mapOf("name" to "length", "type" to "float"), listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "0.000000")), XmlNode("kf", mapOf("t" to "1", "v" to "300.000000", "e" to "ease-out"))))))

    private fun effectsOf(scene: Scene) = scene.node.children.filter { it.name == "shape" }.flatMap { it.children.filter { c -> c.name == "effect" } }

    @Test fun motionBlurFollowsTheLayerAndHasAlightsSettings() {
        val blur = catalog.find("absent.motionblur")!!
        assertEquals(listOf("tune", "position", "scale", "rotation"), blur.params.map { it.id })
        assertEquals(listOf("Tune", "Position", "Scale", "Angle"), blur.params.map { it.label })
        val tune = blur.params.first()
        assertEquals(Triple(0f, 4f, 1f), Triple(tune.min, tune.max, tune.default.toFloat()))
        assertEquals(listOf("switch", "switch", "switch"), blur.params.drop(1).map { it.kind })
        assertEquals(listOf("true", "true", "true"), blur.params.drop(1).map { it.default })
        assertEquals("blur", blur.category)
        assertTrue(blur.native)
    }

    @Test fun theFixedSmearKeepsTheOldDirectionAndLength() {
        val smear = catalog.find("absent.smear")!!
        assertEquals(listOf("angle", "length"), smear.params.map { it.id })
        assertEquals(listOf("Direction", "Length"), smear.params.map { it.label })
        assertEquals(listOf("0.000000", "40.000000"), smear.params.map { it.default })
        assertEquals("blur", smear.category)
    }

    @Test fun directionalMotionBlursOpenAsASmearWithTheSameValuesAndKeys() {
        val result = migration.migrate(scene(shape(1, directional)))
        val smear = effectsOf(result.scene).single()
        assertEquals("absent.smear", smear["id"])
        assertEquals("false", smear["hidden"])
        assertEquals(listOf("angle", "length"), smear.children.map { it["name"] })
        assertEquals("30.000000", smear.children[0]["value"])
        assertEquals(directional.children[1].children, smear.children[1].children)
        assertEquals(mapOf("motionblur → Smear" to 1), result.converted)
        assertTrue(result.dropped.isEmpty())
        val again = migration.migrate(result.scene)
        assertFalse(again.changed)
        assertEquals(result.scene.node, again.scene.node)
    }

    @Test fun motionBlursMadeAfterTheChangeAreLeftAlone() {
        val fresh = catalog.find("absent.motionblur")!!.instance()
        val result = migration.migrate(scene(shape(1, fresh)))
        assertFalse(result.changed)
        assertEquals(listOf(fresh), effectsOf(result.scene))
    }

    @Test fun aWhipPanUsesTheSmearAndSurvivesOpening() {
        val made = scene(shape(1), shape(2)).withTransition(1, 2, TransitionKind.WhipPan, 600).scene
        val ids = made.node.children.flatMap { it.children.filter { c -> c.name == "effect" }.map { c -> c["id"] } }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { it == "absent.smear" })
        val smear = effectsOf(made).first()
        assertTrue(smear.children.first { it["name"] == "length" }.children.isNotEmpty())
        val opened = migration.migrate(made)
        assertFalse(opened.changed)
        assertEquals(made.node, opened.scene.node)
    }

    @Test fun anOldWhipPanKeepsItsKeyedLengthAsASmear() {
        val old = scene(shape(1, directional), shape(2, directional.with("hidden", "false")))
        val opened = migration.migrate(old)
        assertEquals(listOf("absent.smear", "absent.smear"), effectsOf(opened.scene).map { it["id"] })
        assertEquals(2, opened.converted.getValue("motionblur → Smear"))
    }
}
