package app.absentmotion.editor

import app.absentmotion.model.Contour
import app.absentmotion.model.Knot
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.ui.editor.panels.snapAxesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentedLayoutTest {
    private fun layer(id: Long, parent: Long?, location: String, scale: String? = null, rotation: String? = null) = XmlNode(
        "shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "1000") + (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
        listOf(XmlNode("transform", children = listOfNotNull(
            XmlNode("location", mapOf("value" to location)),
            scale?.let { XmlNode("scale", mapOf("value" to it)) },
            rotation?.let { XmlNode("rotation", mapOf("value" to it)) },
        ))),
    )

    private fun scene(size: Int, vararg layers: XmlNode) = Scene(Scene.create("t", size, size, 30, 0).node.copy(children = layers.toList()))

    private fun world(s: Scene, id: Long) = s.place(s.layer(id)!!, 0)

    @Test fun fitToCanvasKeepsAChildOfACentredNullInPlace() {
        val s = scene(1080, layer(1, null, "540,540,0"), layer(2, 1, "0,0,0"))
        val out = s.fittedToCanvas(2, floatArrayOf(490f, 490f, 590f, 590f), "fit", 0)!!
        val child = out.layerAt(2, 0)!!
        assertEquals(0f, child.location[0], 1e-3f)
        assertEquals(0f, child.location[1], 1e-3f)
        assertEquals(10.8f, child.scale[0], 1e-3f)
        assertEquals(540f, world(out, 2).x, 1e-3f)
        assertEquals(540f, world(out, 2).y, 1e-3f)
    }

    @Test fun fitToCanvasConvertsTheMoveIntoAScaledParentsSpace() {
        val s = scene(400, layer(1, null, "100,100,0", scale = "2,2"), layer(2, 1, "50,0,0"))
        val out = s.fittedToCanvas(2, floatArrayOf(150f, 50f, 250f, 150f), "fit", 0)!!
        assertEquals(50f, out.layerAt(2, 0)!!.location[1], 1e-3f)
        assertEquals(200f, world(out, 2).x, 1e-3f)
        assertEquals(200f, world(out, 2).y, 1e-3f)
    }

    @Test fun fitToCanvasIsUnchangedForATopLevelLayer() {
        val s = scene(400, layer(1, null, "100,100,0"))
        val out = s.fittedToCanvas(1, floatArrayOf(50f, 50f, 150f, 150f), "fit", 0)!!
        val l = out.layerAt(1, 0)!!
        assertEquals(200f, l.location[0], 1e-3f)
        assertEquals(200f, l.location[1], 1e-3f)
        assertEquals(4f, l.scale[0], 1e-3f)
    }

    @Test fun aligningAParentAndItsChildMovesEachOnlyOnce() {
        val s = scene(400, layer(1, null, "100,100,0"), layer(2, 1, "50,0,0"))
        val out = s.shiftedInWorld(listOf(s.layer(2)!! to -150f, s.layer(1)!! to -100f), horizontal = true, timeMs = 0)
        assertEquals(0f, world(out, 1).x, 1e-3f)
        assertEquals(0f, world(out, 2).x, 1e-3f)
        assertEquals(100f, world(out, 2).y, 1e-3f)
    }

    @Test fun thePenStillAddsPointsWhenTheSelectedPointWasUndone() {
        val two = Contour(listOf(Knot(Pt(0f, 0f)), Knot(Pt(10f, 0f))), closed = false)
        assertTrue(two.endsAt(1))
        assertTrue(two.endsAt(2))
        assertFalse(two.endsAt(0))
        assertFalse(two.copy(closed = true).endsAt(2))
        assertTrue(Contour(emptyList(), closed = false).endsAt(-1))
    }

    @Test fun positionSnapsUseWorldPlacesAndTheParentsScale() {
        val s = scene(400, layer(1, null, "100,100,0", scale = "2,2"), layer(2, 1, "50,0,0"))
        val (x, y) = s.snapAxesFor(s.layerAt(2, 0)!!, 0, null, emptyList())
        assertEquals(0f, x.map(10f), 1e-3f)
        assertEquals(50f, y.map(60f), 1e-3f)
        val turned = scene(400, layer(1, null, "100,100,0", rotation = "30"), layer(2, 1, "100,100,0"))
        val (tx, ty) = turned.snapAxesFor(turned.layerAt(2, 0)!!, 0, null, emptyList())
        assertEquals(10f, tx.map(10f), 1e-3f)
        assertEquals(10f, ty.map(10f), 1e-3f)
        val tilted = scene(400, Layer(layer(1, null, "100,100,0")).withTilt(0f, 60f).node, layer(2, 1, "0,0,0"), layer(3, 2, "50,0,0"))
        for ((id, pastCentre) in listOf(2L to 105f, 3L to 55f)) {
            val (ax, ay) = tilted.snapAxesFor(tilted.layerAt(id, 0)!!, 0, null, emptyList())
            assertEquals(pastCentre, ax.map(pastCentre), 1e-3f)
            assertEquals(105f, ay.map(105f), 1e-3f)
        }
        val flat = scene(400, layer(1, null, "100,100,0"), layer(2, 1, "0,0,0"))
        assertEquals(100f, flat.snapAxesFor(flat.layerAt(2, 0)!!, 0, null, emptyList()).first.map(105f), 1e-3f)
    }
    @Test fun resetPositionCentresAParentedLayerOnTheCanvas() {
        val s = scene(1080, layer(1, null, "100,200,0", scale = "2,2", rotation = "30"), layer(2, 1, "50,0,0"))
        val centred = s.update(2, 0) { s.centred(it, 0) }
        assertEquals(540f, world(centred, 2).x, 1e-2f)
        assertEquals(540f, world(centred, 2).y, 1e-2f)
        val alone = scene(1080, layer(3, null, "10,20,5"))
        assertEquals(listOf(540f, 540f, 0f), alone.centred(alone.layer(3)!!, 0).location.toList())
    }

    @Test fun flippingAKeyframedScaleFlipsEveryKey() {
        val keyed = Layer(layer(1, null, "0,0,0")).copy(node = layer(1, null, "0,0,0").let { n ->
            n.withChild("transform", n.child("transform")!!.withChild("scale", XmlNode("scale").withKeyframes(listOf(Keyframe(0f, "1,1"), Keyframe(1f, "2,3")))))
        })
        assertEquals(listOf("-1.000000,1.000000", "-2.000000,3.000000"), keyed.flipped(true).keyframes(PropRef.Scale).map { it.v })
        assertEquals(listOf("1.000000,-1.000000", "2.000000,-3.000000"), keyed.flipped(false).keyframes(PropRef.Scale).map { it.v })
        assertEquals(listOf(-1f, 1f), Layer(layer(2, null, "0,0,0")).flipped(true).scale.toList())
    }
}
