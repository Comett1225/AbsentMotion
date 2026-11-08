package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveTogetherTest {
    private fun layer(id: Long, parent: Long?, location: String, scale: String = "1,1") = XmlNode(
        "shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "1000") + (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
        listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to location)), XmlNode("scale", mapOf("value" to scale))))),
    )

    private fun scene(vararg layers: XmlNode) = Scene(Scene.create("t", 1000, 1000, 30, 0).node.copy(children = layers.toList()))

    private fun world(s: Scene, id: Long) = s.place(s.layer(id)!!, 0)

    @Test fun chosenLayersMoveByTheSameAmount() {
        val out = scene(layer(1, null, "100,100,0"), layer(2, null, "400,300,0"), layer(3, null, "50,50,0")).movedTogether(setOf(1, 2), 0, 0f, 0f, 1f, 30f, -20f)
        assertEquals(130f, world(out, 1).x, 1e-3f)
        assertEquals(80f, world(out, 1).y, 1e-3f)
        assertEquals(430f, world(out, 2).x, 1e-3f)
        assertEquals(280f, world(out, 2).y, 1e-3f)
        assertEquals(50f, world(out, 3).x, 1e-3f)
    }

    @Test fun scalingKeepsTheMiddleOfTheSelectionInPlace() {
        val out = scene(layer(1, null, "100,100,0"), layer(2, null, "300,100,0", "2,2")).movedTogether(setOf(1, 2), 0, 200f, 100f, 0.5f, 0f, 0f)
        assertEquals(150f, world(out, 1).x, 1e-3f)
        assertEquals(250f, world(out, 2).x, 1e-3f)
        assertEquals(0.5f, out.layer(1)!!.scale[0], 1e-4f)
        assertEquals(1f, out.layer(2)!!.scale[1], 1e-4f)
    }

    @Test fun aChildOfAChosenParentMovesOnlyOnceWithIt() {
        val out = scene(layer(1, null, "100,100,0"), layer(2, 1, "50,0,0")).movedTogether(setOf(1, 2), 0, 0f, 0f, 1f, 10f, 0f)
        assertEquals(110f, world(out, 1).x, 1e-3f)
        assertEquals(160f, world(out, 2).x, 1e-3f)
    }

    @Test fun aNewGroupGetsItsAnchorInTheMiddleOfItsContentsWithoutMoving() {
        val made = scene(layer(1, null, "800,200,0"), layer(2, null, "900,300,0")).grouped(setOf(1, 2), null, 10, "G")
        val before = made.place(made.layer(10)!!, 0)
        val centred = made.withAnchorAt(10, 850f, 250f)
        val group = centred.layer(10)!!
        assertEquals(850f, group.location[0], 1e-3f)
        assertEquals(250f, group.location[1], 1e-3f)
        assertEquals(350f, group.pivot[0], 1e-3f)
        assertEquals(-250f, group.pivot[1], 1e-3f)
        assertEquals(before.x - before.px, centred.place(group, 0).x - centred.place(group, 0).px, 1e-3f)
        assertEquals(before.y - before.py, centred.place(group, 0).y - centred.place(group, 0).py, 1e-3f)
    }
}
