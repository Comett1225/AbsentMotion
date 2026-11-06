package app.absentmotion.editor

import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.floats
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentDetachTest {
    private fun prop(name: String, vararg keys: Pair<Float, String>, e: String? = null): XmlNode =
        if (keys.size == 1) XmlNode(name, mapOf("value" to keys[0].second))
        else XmlNode(name, children = keys.mapIndexed { i, (t, v) ->
            XmlNode("kf", linkedMapOf("t" to "$t", "v" to v) + (if (i > 0 && e != null) mapOf("e" to e) else emptyMap()))
        })

    private fun layer(id: Long, parent: Long?, vararg props: XmlNode, start: Int = 0, end: Int = 1000, kind: String = "shape") =
        shapeNode(kind, id = id, start = start, end = end, parent = parent, children = listOf(XmlNode("transform", children = props.toList())))

    private fun Scene.keyed(id: Long): Map<String, Int> =
        layer(id)!!.node.child("transform")?.children.orEmpty().filter { it.isKeyframed }.associate { it.name to it.keyframes.size }

    private fun assertSameAt(before: Scene, after: Scene, id: Long, ms: Int) {
        val p = before.place(before.layer(id)!!, ms)
        val q = after.place(after.layer(id)!!, ms)
        for ((x, y) in listOf(p.x to q.x, p.y to q.y, p.sx to q.sx, p.sy to q.sy, p.rot to q.rot, p.z to q.z)) assertEquals("at $ms", x, y, 1e-2f)
    }

    private fun undoOnce(before: Scene, after: Scene) {
        var current = before
        val history = EditHistory({ current }) { next, _ -> current = next }
        history.edit(null, after)
        assertEquals(before, history.undo())
        assertFalse(history.canUndo)
    }

    private val square = layer(1, null, prop("location", 0f to "100,100,0", 1f to "300,100,0"))
    private val circle = layer(2, null, prop("location", 0f to "200,300,0"))

    private fun kf(t: Float, v: String, e: String? = null) = XmlNode("kf", linkedMapOf("t" to "$t", "v" to v) + (e?.let { mapOf("e" to it) } ?: emptyMap()))

    private val staticParent = layer(1, null, prop("location", 0f to "100,0,0"))
    private val animatedChild = layer(2, 1, prop("location", 0f to "20,100,0", 1f to "50,100,0"))

    private fun assertSameWorld(before: Scene, beforeId: Long, after: Scene, afterId: Long = beforeId, shift: Int = 0) {
        val a = before.layer(beforeId)!!
        val frames = (before.frameAt(a.startTime)..before.frameAt(a.endTime)).map(before::frameTime).filter { it in a.startTime..a.endTime }
        for (ms in frames) {
            val p = before.place(a, ms)
            val q = after.place(after.layer(afterId)!!, ms - shift)
            for ((x, y) in listOf(p.x to q.x, p.y to q.y, p.sx to q.sx, p.sy to q.sy, p.rot to q.rot, p.z to q.z)) assertEquals("at $ms", x, y, 1e-2f)
        }
    }

    @Test fun deletingAStaticParentKeepsEveryChildKeyInPlace() {
        val s = sceneOf(staticParent, animatedChild)
        val out = s.unparentChildrenOf(setOf(1)).remove(1)
        assertEquals(120f, out.layerAt(2, 0)!!.location[0], 1e-3f)
        assertEquals(135f, out.layerAt(2, 500)!!.location[0], 1e-3f)
        assertEquals(150f, out.layerAt(2, 1000)!!.location[0], 1e-3f)
        assertNull(out.layer(2)!!.node["parent"])
        assertEquals(2, out.layer(2)!!.node.child("transform")!!.child("location")!!.keyframes.size)
        assertSameWorld(s, 2, out)
    }

    @Test fun easingSurvivesDetachingFromAStaticParent() {
        val curve = "cubicBezier 0.42 0.0 1.0 1.0"
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "100,50,0"), prop("scale", 0f to "2,2"), prop("rotation", 0f to "90")),
            layer(2, 1, prop("location", 0f to "10,0,0", 1f to "30,0,0", e = curve)),
        )
        val keys = s.unparentChildrenOf(setOf(1)).layer(2)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(listOf(null, curve), keys.map { it.e })
        assertEquals(listOf("100.000000,70.000000,0.000000", "100.000000,110.000000,0.000000"), keys.map { it.v })
    }

    @Test fun easingAndAnimationMarksSurviveDetachingFromAnAnimatedParent() {
        val curve = "cubicBezier 0.42 0.0 0.58 1.0"
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "0,0,0", 0.25f to "100,0,0")),
            layer(2, 1, XmlNode("location", children = listOf(
                XmlNode("kf", linkedMapOf("t" to "0.5", "v" to "10,0,0", "gen" to "in")),
                XmlNode("kf", linkedMapOf("t" to "1", "v" to "60,0,0", "e" to curve)),
            ))),
        )
        val keys = s.unparentChildrenOf(setOf(1), 500).remove(1).layer(2)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(listOf(0.5f, 1f), keys.map { it.t })
        assertEquals(listOf("110.000000,0.000000,0.000000", "160.000000,0.000000,0.000000"), keys.map { it.v })
        assertEquals(listOf(null, curve), keys.map { it.e })
        assertEquals(listOf("in", null), keys.map { it.gen })
    }

    @Test fun detachingFromAnAnimatedParentAddsNoKeysAndKeepsThePlayheadPlace() {
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "100,0,0"), prop("rotation", 0f to "0", 1f to "90")),
            layer(2, 1, prop("location", 0f to "20,0,0", 1f to "40,0,0"), prop("scale", 0f to "1,1", 1f to "2,2")),
        )
        val out = s.unparentChildrenOf(setOf(1), 500).remove(1)
        assertNull(out.layer(2)!!.node["parent"])
        assertEquals(mapOf("location" to 2, "scale" to 2), out.keyed(2))
        assertSameAt(s, out, 2, 500)
    }

    @Test fun withoutAPlayheadTheChildIsConvertedAtItsStartAndAPlayheadOutsideItIsClamped() {
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "200,0,0"), start = 0, end = 2000),
            layer(2, 1, prop("location", 0f to "5,5,0"), start = 500, end = 1500),
        )
        val atStart = s.unparentChildrenOf(setOf(1)).remove(1)
        assertTrue(atStart.keyed(2).isEmpty())
        assertEquals(55f, atStart.layerAt(2, 1500)!!.location[0], 1e-3f)
        assertSameAt(s, atStart, 2, 500)
        val late = s.unparentChildrenOf(setOf(1), 1900).remove(1)
        assertEquals(155f, late.layerAt(2, 500)!!.location[0], 1e-3f)
        assertSameAt(s, late, 2, 1500)
    }

    @Test fun reparentingConvertsTheChildsKeysOnceAtThePlayhead() {
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "100,0,0")),
            layer(3, null, prop("location", 0f to "0,0,0", 1f to "0,80,0"), prop("scale", 0f to "2,2")),
            animatedChild,
        )
        val moved = s.rebased(s.layer(2)!!, 3, 500)!!
        val out = s.update(2) { moved }
        assertEquals("3", moved.node["parent"])
        assertEquals(mapOf("location" to 2), out.keyed(2))
        assertSameAt(s, out, 2, 500)
    }

    @Test fun groupingAcrossAnAnimatedParentAddsNoKeysAndKeepsThePlayheadPlace() {
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "100,0,0")),
            layer(2, 1, prop("location", 0f to "20,0,0", 1f to "50,0,0"), start = 200, end = 800),
        )
        val out = s.grouped(setOf(2), null, 9, "G", 500)
        val inner = out.layers.single { it.kind == "embedScene" }.nested!!
        assertEquals(200f, out.layer(9)!!.location[0])
        assertEquals(mapOf("location" to 2), inner.keyed(2))
        val before = s.place(s.layer(2)!!, 500)
        val after = inner.place(inner.layer(2)!!, 300)
        assertEquals(before.x, after.x, 1e-2f)
        assertEquals(before.y, after.y, 1e-2f)
    }

    @Test fun linkingAStillCircleToAnAnimatedSquareAddsNoKeysAndFollowsIt() {
        val s = sceneOf(square, circle)
        val linked = s.update(2) { s.rebased(it, 1, 500)!! }
        assertEquals("1", linked.layer(2)!!.node["parent"])
        assertTrue(linked.keyed(2).isEmpty())
        assertSameAt(s, linked, 2, 500)
        for ((ms, x) in listOf(0 to 100f, 250 to 150f, 1000 to 300f)) {
            assertEquals("at $ms", x, linked.place(linked.layer(2)!!, ms).x, 1e-2f)
            assertEquals("at $ms", 300f, linked.place(linked.layer(2)!!, ms).y, 1e-2f)
        }
    }

    @Test fun unlinkingAtThePlayheadAddsNoKeysAndTheCircleStaysWhereItWasThen() {
        val s = sceneOf(square, circle)
        val linked = s.update(2) { s.rebased(it, 1, 500)!! }
        val unlinked = linked.update(2) { linked.rebased(it, null, 750)!! }
        assertNull(unlinked.layer(2)!!.node["parent"])
        assertTrue(unlinked.keyed(2).isEmpty())
        assertSameAt(linked, unlinked, 2, 750)
        for (ms in listOf(0, 500, 1000)) assertEquals(250f, unlinked.place(unlinked.layer(2)!!, ms).x, 1e-2f)
        assertEquals(mapOf("location" to 2), unlinked.keyed(1))
    }

    @Test fun aNullObjectParentBehavesTheSameForManyLayersAndForDeleting() {
        val nul = layer(1, null, prop("location", 0f to "200,200,0", 1f to "260,140,0"), prop("rotation", 0f to "0", 1f to "120"), prop("scale", 0f to "1,1", 1f to "2,0.5"), kind = "nullobj")
        val s = sceneOf(nul, circle, layer(3, null, prop("location", 0f to "40,60,0"), prop("rotation", 0f to "15")))
        val (linked, skipped) = s.parented(listOf(2L, 3L), 1L, 400)
        assertEquals(0, skipped)
        for (id in listOf(2L, 3L)) {
            assertEquals("1", linked.layer(id)!!.node["parent"])
            assertTrue(linked.keyed(id).isEmpty())
            assertSameAt(s, linked, id, 400)
        }
        val (cleared, _) = linked.parented(listOf(2L, 3L), null, 900)
        val deleted = linked.unparentChildrenOf(setOf(1), 900).remove(1)
        for (out in listOf(cleared, deleted)) for (id in listOf(2L, 3L)) {
            assertNull(out.layer(id)!!.node["parent"])
            assertTrue(out.keyed(id).isEmpty())
            assertSameAt(linked, out, id, 900)
        }
    }

    @Test fun aChildsOwnKeysKeepTheirCountAndEasingThroughLinkAndUnlink() {
        val curve = "cubicBezier 0.42 0.0 0.58 1.0"
        val parent = layer(1, null, prop("location", 0f to "150,150,0", 1f to "250,120,0"), prop("rotation", 0f to "-30", 1f to "60"), prop("scale", 0f to "1.5,1.5", 1f to "0.5,2"), prop("pivot", 0f to "10,-5,0"))
        val child = layer(2, null,
            XmlNode("location", children = listOf(kf(0f, "10,10,0"), kf(0.5f, "80,40,0"), kf(1f, "120,90,0", curve))),
            prop("rotation", 0f to "0", 1f to "200"),
            XmlNode("scale", children = listOf(kf(0f, "1,1"), kf(0.5f, "2,3"), kf(1f, "1.5,1.5", curve))), prop("pivot", 0f to "4,8,0"))
        val s = sceneOf(parent, child)
        val linked = s.update(2) { s.rebased(it, 1, 250)!! }
        val unlinked = linked.update(2) { linked.rebased(it, null, 400)!! }
        val counts = mapOf("location" to 3, "rotation" to 2, "scale" to 3)
        assertEquals(counts, linked.keyed(2))
        assertEquals(counts, unlinked.keyed(2))
        assertSameAt(s, linked, 2, 250)
        assertSameAt(linked, unlinked, 2, 400)
        for (out in listOf(linked, unlinked)) for (name in listOf("location", "scale")) {
            assertEquals(listOf(null, null, curve), out.layer(2)!!.node.child("transform")!!.child(name)!!.keyframes.map { it.e })
        }
        val turned = unlinked.layer(2)!!.node.child("transform")!!.child("rotation")!!.keyframes.map { floats(it.v, floatArrayOf(0f))[0] }
        assertEquals(200f, turned[1] - turned[0], 1e-3f)
    }

    @Test fun aKeyedPivotUnderAStretchingParentGetsNoNewKeysAndStaysAtThePlayhead() {
        val s = sceneOf(
            layer(1, null, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "30"), prop("scale", 0f to "3,0.5")),
            layer(2, 1, prop("location", 0f to "20,10,0"), prop("pivot", 0f to "0,0,0", 1f to "40,20,0")),
        )
        val out = s.unparentChildrenOf(setOf(1), 600).remove(1)
        assertEquals(mapOf("pivot" to 2), out.keyed(2))
        assertSameAt(s, out, 2, 600)
    }

    @Test fun linkingAndDeletingTheParentAreEachOneUndoStep() {
        val s = sceneOf(square, circle)
        val (linked, _) = s.parented(listOf(2L), 1L, 500)
        undoOnce(s, linked)
        undoOnce(linked, linked.unparentChildrenOf(setOf(1), 500).remove(1))
        undoOnce(linked, linked.update(2) { linked.rebased(it, null, 500)!! })
    }

    @Test fun copyingAndSavingAPresetKeepTheChildsMotion() {
        val s = sceneOf(staticParent, animatedChild)
        val copied = s.unparentChildrenOf(setOf(1)).layer(2)!!
        assertEquals(150f, copied.at(1000, 30).location[0], 1e-3f)
        val preset = presetScene(s, s.layer(2)!!, "P").layers.single()
        assertEquals(120f, preset.at(0, 30).location[0], 1e-3f)
        assertEquals(150f, preset.at(1000, 30).location[0], 1e-3f)
        assertEquals(copied.node, preset.node)
    }

    @Test fun draggingAChildMovesItWithTheFingerInsideATurnedOrScaledParent() {
        for (parent in listOf(prop("rotation", 0f to "90"), prop("scale", 0f to "2,2"))) {
            val s = sceneOf(layer(1, null, prop("location", 0f to "200,200,0"), parent), layer(2, 1, prop("location", 0f to "40,10,0")))
            val child = s.layerAt(2, 500)!!
            val before = s.place(child, 500)
            val dragged = s.update(2, 500) { s.withWorldLocation(it, 500) { x, y -> x + 30f to y - 20f } }
            val after = dragged.place(dragged.layer(2)!!, 500)
            assertEquals(before.x + 30f, after.x, 1e-3f)
            assertEquals(before.y - 20f, after.y, 1e-3f)
        }
        val alone = sceneOf(layer(3, null, prop("location", 0f to "40,10,0")))
        val moved = alone.withWorldLocation(alone.layerAt(3, 0)!!, 0) { x, y -> x + 30f to y - 20f }
        assertEquals(listOf(70f, -10f, 0f), moved.location.toList())
    }
}
