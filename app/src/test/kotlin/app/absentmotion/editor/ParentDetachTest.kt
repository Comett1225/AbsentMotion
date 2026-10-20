package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentDetachTest {
    private fun prop(name: String, vararg keys: Pair<Float, String>, e: String? = null): XmlNode =
        if (keys.size == 1) XmlNode(name, mapOf("value" to keys[0].second))
        else XmlNode(name, children = keys.mapIndexed { i, (t, v) ->
            XmlNode("kf", linkedMapOf("t" to "$t", "v" to v) + (if (i > 0 && e != null) mapOf("e" to e) else emptyMap()))
        })

    private fun layer(id: Long, parent: Long?, vararg props: XmlNode, start: Int = 0, end: Int = 1000) = XmlNode(
        "shape", linkedMapOf("id" to "$id", "startTime" to "$start", "endTime" to "$end") + (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
        listOf(XmlNode("transform", children = props.toList())),
    )

    private fun scene(vararg layers: XmlNode) = Scene(Scene.create("t", 400, 400, 30, 0).node.copy(children = layers.toList()))

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
        val s = scene(staticParent, animatedChild)
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
        val s = scene(
            layer(1, null, prop("location", 0f to "100,50,0"), prop("scale", 0f to "2,2"), prop("rotation", 0f to "90")),
            layer(2, 1, prop("location", 0f to "10,0,0", 1f to "30,0,0", e = curve)),
        )
        val keys = s.unparentChildrenOf(setOf(1)).layer(2)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(listOf(null, curve), keys.map { it.e })
        assertEquals(listOf("100.000000,70.000000,0.000000", "100.000000,110.000000,0.000000"), keys.map { it.v })
    }

    @Test fun detachingFromAnAnimatedParentBakesTheCombinedMotion() {
        val s = scene(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "100,0,0"), prop("rotation", 0f to "0", 1f to "90")),
            layer(2, 1, prop("location", 0f to "20,0,0", 1f to "40,0,0"), prop("scale", 0f to "1,1", 1f to "2,2")),
        )
        val out = s.unparentChildrenOf(setOf(1)).remove(1)
        assertSameWorld(s, 2, out)
        assertEquals(100f, out.layerAt(2, 1000)!!.location[0], 1e-3f)
        assertEquals(40f, out.layerAt(2, 1000)!!.location[1], 1e-3f)
    }

    @Test fun aParentThatOutlivesTheChildIsSampledAtTheChildsTimes() {
        val s = scene(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "200,0,0"), start = 0, end = 2000),
            layer(2, 1, prop("location", 0f to "5,5,0"), start = 500, end = 1500),
        )
        val out = s.unparentChildrenOf(setOf(1)).remove(1)
        assertSameWorld(s, 2, out)
        assertEquals(55f, out.layerAt(2, 500)!!.location[0], 1e-3f)
        assertEquals(155f, out.layerAt(2, 1500)!!.location[0], 1e-3f)
    }

    @Test fun reparentingKeepsTheWorldMotion() {
        val s = scene(
            layer(1, null, prop("location", 0f to "100,0,0")),
            layer(3, null, prop("location", 0f to "0,0,0", 1f to "0,80,0"), prop("scale", 0f to "2,2")),
            animatedChild,
        )
        val moved = s.rebased(s.layer(2)!!, 3)!!
        assertEquals("3", moved.node["parent"])
        assertSameWorld(s, 2, s.update(2) { moved })
    }

    @Test fun groupingAcrossAnAnimatedParentKeepsTheChildsMotion() {
        val s = scene(
            layer(1, null, prop("location", 0f to "0,0,0", 1f to "100,0,0")),
            layer(2, 1, prop("location", 0f to "20,0,0", 1f to "50,0,0"), start = 200, end = 800),
        )
        val out = s.grouped(setOf(2), null, 9, "G")
        val inner = out.layers.single { it.kind == "embedScene" }.nested!!
        assertEquals(200f, out.layer(9)!!.location[0])
        assertSameWorld(s, 2, inner, shift = 200)
    }

    @Test fun copyingAndSavingAPresetKeepTheChildsMotion() {
        val s = scene(staticParent, animatedChild)
        val copied = s.unparentChildrenOf(setOf(1)).layer(2)!!
        assertEquals(150f, copied.at(1000, 30).location[0], 1e-3f)
        val preset = presetScene(s, s.layer(2)!!, "P").layers.single()
        assertEquals(120f, preset.at(0, 30).location[0], 1e-3f)
        assertEquals(150f, preset.at(1000, 30).location[0], 1e-3f)
        assertEquals(copied.node, preset.node)
    }

    @Test fun draggingAChildMovesItWithTheFingerInsideATurnedOrScaledParent() {
        for (parent in listOf(prop("rotation", 0f to "90"), prop("scale", 0f to "2,2"))) {
            val s = scene(layer(1, null, prop("location", 0f to "200,200,0"), parent), layer(2, 1, prop("location", 0f to "40,10,0")))
            val child = s.layerAt(2, 500)!!
            val before = s.place(child, 500)
            val dragged = s.update(2, 500) { s.withWorldLocation(it, 500) { x, y -> x + 30f to y - 20f } }
            val after = dragged.place(dragged.layer(2)!!, 500)
            assertEquals(before.x + 30f, after.x, 1e-3f)
            assertEquals(before.y - 20f, after.y, 1e-3f)
        }
        val alone = scene(layer(3, null, prop("location", 0f to "40,10,0")))
        val moved = alone.withWorldLocation(alone.layerAt(3, 0)!!, 0) { x, y -> x + 30f to y - 20f }
        assertEquals(listOf(70f, -10f, 0f), moved.location.toList())
    }
}
