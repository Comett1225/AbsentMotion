package app.absentmotion.editor

import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.model.XmlNode
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class ParentRigidTest {
    private fun prop(name: String, value: String) = XmlNode(name, mapOf("value" to value))

    private fun layer(id: Long, parent: Long?, location: String, pivot: String, rotation: String = "0", scale: String = "1,1") =
        shapeNode("shape", id = id, start = 0, end = 1000, parent = parent,
            children = listOf(XmlNode("transform", children = listOf(prop("location", location), prop("pivot", pivot), prop("rotation", rotation), prop("scale", scale)))))

    @Test fun aChildWithItsOwnPivotStaysAttachedWhenTheParentTurnsAndScales() {
        val still = sceneOf(layer(1, null, "300,310,0", "0,60"), layer(2, 1, "-25,20,0", "0,-70"))
        val turned = sceneOf(layer(1, null, "300,310,0", "0,60", rotation = "50", scale = "1.5,1.5"), layer(2, 1, "-25,20,0", "0,-70"))
        val rest = still.place(still.layer(2)!!, 0)
        val moved = turned.place(turned.layer(2)!!, 0)
        assertEquals(275f, rest.x, 1e-3f)
        assertEquals(330f, rest.y, 1e-3f)
        val a = Math.toRadians(50.0)
        val dx = -25.0 * 1.5
        val dy = 20.0 * 1.5
        assertEquals((300 + dx * cos(a) - dy * sin(a)).toFloat(), moved.x, 1e-2f)
        assertEquals((310 + dx * sin(a) + dy * cos(a)).toFloat(), moved.y, 1e-2f)
        assertEquals(hypot(25.0, 20.0) * 1.5, hypot(moved.x - 300.0, moved.y - 310.0), 1e-2)
        assertEquals(50f, moved.rot, 1e-3f)
    }

    @Test fun linkingAndUnlinkingKeepsWhereTheLayerIsWhenPivotsDiffer() {
        val scene = sceneOf(layer(1, null, "300,310,0", "0,60", rotation = "35", scale = "1.2,0.8"), layer(2, null, "120,80,0", "10,-40", rotation = "12"))
        val before = scene.place(scene.layer(2)!!, 0)
        val linked = scene.rebased(scene.layer(2)!!, 1)!!
        val withParent = sceneOf(scene.layer(1)!!.node, linked.node)
        val after = withParent.place(withParent.layer(2)!!, 0)
        assertEquals(before.x, after.x, 1e-2f)
        assertEquals(before.y, after.y, 1e-2f)
        assertEquals(before.rot, after.rot, 1e-2f)
    }
}
