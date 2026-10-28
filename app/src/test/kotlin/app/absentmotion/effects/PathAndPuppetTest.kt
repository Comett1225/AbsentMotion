package app.absentmotion.effects

import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.PuppetPin
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.allPuppetPins
import app.absentmotion.model.effects
import app.absentmotion.model.leansOnBelow
import app.absentmotion.model.onTextPath
import app.absentmotion.model.pinnedPath
import app.absentmotion.model.puppetFalloff
import app.absentmotion.model.puppetPins
import app.absentmotion.model.unwarp
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.withPuppetPin
import app.absentmotion.model.withPuppetPinAt
import app.absentmotion.model.withoutPuppetPin
import app.absentmotion.sceneOf
import app.absentmotion.ui.editor.panels.arcGuide
import app.absentmotion.ui.editor.panels.circleGuide
import app.absentmotion.ui.editor.panels.missesPathBelow
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathAndPuppetTest {
    private val pack = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val path = catalog.find("absent.textpath")!!
    private val puppet = catalog.find("absent.puppet")!!

    private fun near(a: Pt, b: Pt, e: Float = 1e-2f) {
        assertEquals(a.x, b.x, e)
        assertEquals(a.y, b.y, e)
    }

    @Test fun bothEffectsAreNativeNewAndListed() {
        assertTrue(path.native && path.category == "text" && path.since == "4.2.0")
        assertTrue(puppet.native && puppet.category == "distort" && puppet.since == "4.2.0")
        assertTrue(catalog.isNew(path) && catalog.isNew(puppet))
        assertEquals(listOf("Arc", "Circle", "Layer Below"), path.params.first { it.id == "shape" }.options)
        assertEquals("1.000000", puppet.params.single().default)
    }

    @Test fun textPathShowsOnlyTheSettingsOfTheChosenPath() {
        val arc = path.instance()
        val shown = { node: XmlNode -> path.params.filter { path.shown(it, node, 0f) }.map { it.id } }
        assertEquals(listOf("shape", "position", "align", "baseline", "rotate", "reverse", "start", "control1", "control2", "end"), shown(arc))
        val circle = arc.withEffectProperty("shape", "float", "1.000000", 0f, 0f..0f)
        assertEquals(listOf("shape", "position", "align", "baseline", "rotate", "reverse", "center", "radius"), shown(circle))
        val below = arc.withEffectProperty("shape", "float", "2.000000", 0f, 0f..0f)
        assertEquals(listOf("shape", "position", "align", "baseline", "rotate", "reverse"), shown(below))
    }

    @Test fun textOnTheLayerBelowStaysWithItsPath() {
        val text = Layer(shapeNode(kind = "text", id = 2)).addEffect(path.instance().withEffectProperty("shape", "float", "2.000000", 0f, 0f..0f))
        val scene = sceneOf(shapeNode(id = 1), text.node)
        assertTrue(scene.layer(2)!!.leansOnBelow)
        assertEquals("1", scene.pinnedPath(scene.layer(2)!!).node["pathLayer"])
        val arc = Layer(shapeNode(kind = "text", id = 3)).addEffect(path.instance())
        assertFalse(arc.leansOnBelow)
        val hidden = Layer(shapeNode(kind = "text", id = 4)).addEffect(path.instance().withEffectProperty("shape", "float", "2", 0f, 0f..0f).with("hidden", "true"))
        assertFalse(hidden.leansOnBelow)
    }

    private fun below(kind: String, id: Long) = Layer(shapeNode(kind = kind, id = id)).addEffect(path.instance().withEffectProperty("shape", "float", "2.000000", 0f, 0f..0f))

    @Test fun onlyTextLeansOnTheLayerBelow() {
        val shape = below("shape", 2)
        assertFalse(shape.leansOnBelow)
        val scene = sceneOf(shapeNode(id = 1), shape.node)
        assertNull(scene.pinnedPath(scene.layer(2)!!).node["pathLayer"])
        assertEquals(listOf(1L, 3L, 2L), scene.addAbove(Layer(shapeNode(id = 3)), 1).layers.map { it.id })
    }

    @Test fun movingAPinnedCopyInTheLayerListLetsItFollowTheLayerBelowAgain() {
        val scene = sceneOf(shapeNode(id = 1), shapeNode(id = 5), below("text", 2).node.let { it.with("pathLayer", "1") })
        val moved = scene.reorder(2, 1)
        assertEquals(listOf(1L, 2L, 5L), moved.layers.map { it.id })
        assertNull(moved.layer(2)!!.node["pathLayer"])
        assertEquals("1", scene.move(2, 1).layer(2)!!.node["pathLayer"])
    }

    @Test fun theEffectSaysWhenNothingLiesBeneathToFollow() {
        val text = below("text", 2)
        val effect = text.effects.single()
        assertTrue(sceneOf(text.node).missesPathBelow(text, effect))
        assertTrue(sceneOf(shapeNode(kind = "nullobj", id = 1), text.node).missesPathBelow(text, effect))
        assertFalse(sceneOf(shapeNode(id = 1), text.node).missesPathBelow(text, effect))
        assertFalse(sceneOf(text.node, shapeNode(id = 1)).missesPathBelow(text.copy(node = text.node.with("pathLayer", "1")), effect))
        val arc = Layer(shapeNode(kind = "text", id = 3)).addEffect(path.instance())
        assertFalse(sceneOf(arc.node).missesPathBelow(arc, arc.effects.single()))
    }

    @Test fun pathTextIsNotWrappedToItsBox() {
        assertTrue(below("text", 2).onTextPath)
        assertFalse(below("shape", 2).onTextPath)
        assertFalse(Layer(shapeNode(kind = "text", id = 2)).onTextPath)
        assertFalse(Layer(shapeNode(kind = "text", id = 2)).addEffect(path.instance().with("hidden", "true")).onTextPath)
    }

    @Test fun theCanvasShowsTheArcAndCircleTheTextFollows() {
        val arc = arcGuide(Pt(-300f, 80f), Pt(-200f, -120f), Pt(200f, -120f), Pt(300f, 80f))
        near(Pt(-300f, 80f), arc.first())
        near(Pt(300f, 80f), arc.last())
        near(Pt(0f, -70f), arc[arc.size / 2])
        val circle = circleGuide(Pt(10f, 20f), 100f)
        assertTrue(circle.all { kotlin.math.abs(it.distanceTo(Pt(10f, 20f)) - 100f) < 1e-2f })
        near(circle.first(), circle.last())
    }

    @Test fun pinsAreAddedNumberedAndRemovedInPairs() {
        var effect = puppet.instance()
        assertTrue(effect.puppetPins(0f).isEmpty())
        effect = effect.withPuppetPin(Pt(10f, 20f), Pt(10f, 20f)).withPuppetPin(Pt(50f, 0f), Pt(40f, 0f))
        assertEquals(listOf(1, 2), effect.puppetPins(0f).map { it.number })
        effect = effect.withoutPuppetPin(1).withPuppetPin(Pt(0f, 0f), Pt(0f, 0f))
        assertEquals(listOf(2, 1), effect.puppetPins(0f).map { it.number })
        assertEquals(Pt(40f, 0f), effect.puppetPins(0f).first().rest)
        assertNull(effect.withoutPuppetPin(2).withoutPuppetPin(1).children.firstOrNull { it["name"]?.startsWith("rest") == true })
        val reopened = Scene.parse(sceneOf(shapeNode(id = 1, children = listOf(effect))).toXml()).layer(1)!!.effects.single()
        assertEquals(effect.puppetPins(0f), reopened.puppetPins(0f))
        assertEquals(1f, reopened.puppetFalloff(0f), 0f)
    }

    @Test fun pinPositionsAnimateWithKeyframes() {
        val base = Layer(shapeNode(id = 1, children = listOf(puppet.instance().withPuppetPin(Pt(0f, 0f), Pt(0f, 0f)))))
        val ref = app.absentmotion.model.PropRef.effect(0, "pin1")
        val keyed = base.at(0, 30).withKeyframeToggled(ref, "0,0")
        val moved = keyed.at(2000, 30).let { l -> l.withEffect(0) { it.withEffectProperty("pin1", "vec2", "100.000000,0.000000", l.t, l.snap) } }
        val effect = moved.effects.single()
        assertEquals(2, moved.keyframes(ref).size)
        assertEquals(Pt(50f, 0f), effect.puppetPins(0.5f).single().at)
        assertEquals(Pt(0f, 0f), effect.puppetPins(0.5f).single().rest)
    }

    @Test fun theKotlinWarpMatchesTheFixtureTheEngineTestAlsoChecks() {
        assertEquals(Pt(12f, 34f), unwarp(emptyList(), 1f, Pt(12f, 34f)))
        val rest = listOf(PuppetPin(1, Pt(10f, 10f), Pt(10f, 10f)), PuppetPin(2, Pt(90f, 20f), Pt(90f, 20f)))
        near(Pt(33f, 41f), unwarp(rest, 1f, Pt(33f, 41f)))
        near(Pt(80f, 100f), unwarp(listOf(PuppetPin(1, Pt(60f, 50f), Pt(40f, 50f))), 1f, Pt(100f, 100f)))
        near(Pt(50f, 0f), unwarp(listOf(PuppetPin(1, Pt(0f, 0f), Pt(0f, 0f)), PuppetPin(2, Pt(0f, 100f), Pt(100f, 0f))), 1f, Pt(0f, 50f)))
        near(Pt(30f, 30f), unwarp(listOf(PuppetPin(1, Pt(30f, 30f), Pt(10f, 10f)), PuppetPin(2, Pt(30f, 30f), Pt(10f, 10f))), 1f, Pt(50f, 50f)))
        val bent = pins(0f, 0f, 0f, 0f, 150f, 30f, 100f, 0f, 50f, 120f, 40f, 100f, 200f, 200f, 210f, 190f)
        val pair = pins(10f, 10f, 10f, 10f, 90f, 20f, 95f, 30f, 50f, 80f, 50f, 80f, 50.2f, 80.1f, 40f, 70f)
        near(Pt(45.8442f, 21.5051f), unwarp(bent, 1f, Pt(70f, 40f)), 2e-3f)
        near(Pt(-5.7997f, 154.4276f), unwarp(bent, 0.1f, Pt(-30f, 160f)), 2e-3f)
        near(Pt(93.4713f, 66.2163f), unwarp(bent, 4f, Pt(120f, 90f)), 2e-3f)
        near(Pt(57.8627f, 59.4605f), unwarp(pair, 2f, Pt(60f, 60f)), 2e-3f)
        near(unwarp(bent, 1f, Pt(70f, 40f)), unwarp(bent, Float.NaN, Pt(70f, 40f)), 0f)
    }

    @Test fun unmovedPinsHoldThePictureAndMovedPinsCarryItAtEveryFalloff() {
        val held = pins(0f, 0f, 0f, 0f, 400f, 0f, 400f, 0f, 200f, 300f, 200f, 200f)
        for (falloff in listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f)) {
            for (d in listOf(0f, 0.5f, 2f)) {
                near(Pt(d, 0f), unwarp(held, falloff, Pt(d, 0f)), 0.1f)
                near(Pt(400f - d, 0f), unwarp(held, falloff, Pt(400f - d, 0f)), 0.1f)
            }
            near(Pt(200.3f, 200f), unwarp(held, falloff, Pt(200.3f, 300f)), 0.05f)
        }
    }

    @Test fun aNewPinOnABentLayerLeavesEveryPointOfThePictureWhereItIs() {
        for (falloff in listOf(0.1f, 1f, 4f)) {
            var effect = puppet.instance().withEffectProperty("falloff", "float", "$falloff", 0f, 0f..0f)
            for (p in pins(0f, 0f, 0f, 0f, 150f, 30f, 100f, 0f, 50f, 120f, 40f, 100f)) effect = effect.withPuppetPin(p.at, p.rest)
            val before = app.absentmotion.model.PuppetBend(effect.puppetPins(0f), effect.puppetFalloff(0f))
            effect = effect.withPuppetPinAt(Pt(70f, 40f), 0f)!!
            val after = app.absentmotion.model.PuppetBend(effect.puppetPins(0f), effect.puppetFalloff(0f))
            assertEquals(4, effect.puppetPins(0f).size)
            for (x in -100..300 step 20) for (y in -100..300 step 20) near(before.source(Pt(x.toFloat(), y.toFloat())), after.source(Pt(x.toFloat(), y.toFloat())), 1e-3f)
        }
    }

    @Test fun oddPinDataIsReadTheWayTheEngineReadsIt() {
        fun prop(name: String, value: String) = XmlNode("property", linkedMapOf("name" to name, "type" to "vec2", "value" to value))
        val effect = puppet.instance().copy(children = listOf(
            prop("rest0", "1,1"), prop("pin0", "5,5"), prop("rest01", "1,1"), prop("pin01", "5,5"), prop("rest1", "1,1"), prop("pin1", "nan,5"),
            prop("rest2", "1,1"), prop("pin2", "1e40,5"), prop("rest3", "2,2"), prop("pin3", "7"), prop("rest4", "3,3"), prop("pin4", "4 , 6"),
            XmlNode("property", linkedMapOf("name" to "falloff", "type" to "float", "value" to "nan"))))
        assertEquals(listOf(PuppetPin(4, Pt(4f, 6f), Pt(3f, 3f))), effect.puppetPins(0f))
        assertEquals(1f, effect.puppetFalloff(0f), 0f)
        var many = puppet.instance()
        repeat(33) { many = many.withPuppetPin(Pt(it * 3f, 0f), Pt(it * 3f, 0f)) }
        assertEquals(33, many.allPuppetPins(0f).size)
        assertEquals((1..32).toList(), many.puppetPins(0f).map { it.number })
        assertNull(many.withPuppetPinAt(Pt(1f, 1f), 0f))
        assertNull(many.withoutPuppetPin(33).withPuppetPinAt(Pt(1f, 1f), 0f))
        assertEquals(32, many.withoutPuppetPin(33).withoutPuppetPin(5).withPuppetPinAt(Pt(1f, 1f), 0f)!!.puppetPins(0f).size)
    }

    private fun pins(vararg v: Float) = (v.indices step 4).mapIndexed { i, k -> PuppetPin(i + 1, Pt(v[k], v[k + 1]), Pt(v[k + 2], v[k + 3])) }
}
