package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EffectPasteTest {
    private fun layer(kind: String, id: Long, children: List<XmlNode> = emptyList()) =
        Layer(XmlNode(kind, mapOf("id" to "$id", "startTime" to "0", "endTime" to "2000"), children))

    private fun effect(id: String, radius: String = "10") =
        XmlNode("effect", mapOf("id" to id, "hidden" to "false"), listOf(XmlNode("property", mapOf("name" to "radius", "type" to "float", "value" to radius))))

    private val glow = effect("absent.glow")
    private val shake = effect("absent.shake", "4")
    private val blur = effect("absent.blur", "7")

    private val scene = listOf(
        layer("shape", 1, listOf(effect("absent.old"))), layer("text", 2), layer("audio", 3), layer("camera", 4), layer("nullobj", 5), layer("shape", 6),
    ).fold(Scene.create("p", 100, 100, 30, 0)) { s, l -> s.add(l) }

    private fun ids(layer: Long) = scene.layer(layer)!!.effects.map { it["id"] }

    @Test fun everyVisualLayerGetsTheEffectsInOrderAndTheRestAreSkipped() {
        val ids = setOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(listOf(1L, 2L), scene.effectTargets(ids).map { it.id })
        val out = scene.withEffectsPasted(ids, listOf(glow, shake))
        assertEquals(listOf("absent.old", "absent.glow", "absent.shake"), out.layer(1)!!.effects.map { it["id"] })
        assertEquals(listOf("absent.glow", "absent.shake"), out.layer(2)!!.effects.map { it["id"] })
        for (skipped in 3L..5L) assertEquals(emptyList<XmlNode>(), out.layer(skipped)!!.effects)
        assertEquals(emptyList<XmlNode>(), out.layer(6)!!.effects)
        assertEquals(scene.layers.map { it.id }, out.layers.map { it.id })
    }

    @Test fun eachClipOwnsItsPastedEffects() {
        val out = scene.withEffectsPasted(setOf(1L, 2L), listOf(glow))
        val changed = out.update(1) { it.withEffect(1) { node -> node.withEffectProperty("radius", "float", "99", 0f, 0f) } }
        assertNotEquals(changed.layer(1)!!.effects[1], changed.layer(2)!!.effects[0])
        assertEquals(glow, changed.layer(2)!!.effects[0])
    }

    @Test fun nothingChangesWhenNoSelectedLayerCanTakeEffects() {
        assertEquals(scene, scene.withEffectsPasted(setOf(3L, 4L, 5L), listOf(glow)))
        assertEquals(scene, scene.withEffectsPasted(setOf(1L, 2L), emptyList()))
    }

    @Test fun copyingASubsetKeepsTheLayerOrder() {
        EffectClipboard.copy(listOf(glow, shake, blur), setOf(2, 0))
        assertEquals(listOf(glow, blur), EffectClipboard.effects)
        EffectClipboard.copy(listOf(glow, shake, blur), emptySet())
        assertEquals(listOf(glow, blur), EffectClipboard.effects)
        EffectClipboard.copyOne(shake)
        assertEquals(listOf(shake), EffectClipboard.effects)
    }
}
