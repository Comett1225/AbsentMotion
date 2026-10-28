package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.keyframes
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class PivotTest {
    private fun prop(name: String, vararg keys: Pair<Float, String>): XmlNode =
        if (keys.size == 1) XmlNode(name, mapOf("value" to keys[0].second))
        else XmlNode(name, children = keys.map { (t, v) -> XmlNode("kf", linkedMapOf("t" to "$t", "v" to v)) })

    private fun layer(id: Long, vararg props: XmlNode, parent: Long? = null) =
        shapeNode(id = id, start = 0, end = 1000, parent = parent, children = listOf(XmlNode("transform", children = props.toList())))

    private fun world(l: Layer, px: Float, py: Float): Pair<Float, Float> {
        val loc = l.location
        val piv = l.pivot
        val s = l.scale
        val skew = l.transform("skew", 0f, 0f)
        val r = Math.toRadians(l.transform("rotation", 0f)[0].toDouble())
        val x1 = (px - piv[0]) * s[0]
        val y1 = (py - piv[1]) * s[1]
        val x2 = x1 + tan(skew[0]) * y1
        val y2 = tan(skew[1]) * x1 + y1
        return (loc[0] + x2 * cos(r) - y2 * sin(r)).toFloat() to (loc[1] + x2 * sin(r) + y2 * cos(r)).toFloat()
    }

    private fun assertSamePicture(before: Scene, after: Scene, id: Long, frames: List<Int>, slack: Float = 2e-3f) {
        for (ms in frames) for ((px, py) in listOf(0f to 0f, 40f to -25f, -60f to 80f)) {
            val a = world(before.layerAt(id, ms)!!, px, py)
            val b = world(after.layerAt(id, ms)!!, px, py)
            assertEquals("x at $ms", a.first, b.first, slack)
            assertEquals("y at $ms", a.second, b.second, slack)
        }
    }

    private val frames = listOf(0, 250, 500, 750, 1000)

    @Test fun movingThePivotKeepsATransformedLayerWhereItIs() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,50,0"), prop("rotation", 0f to "30"), prop("scale", 0f to "2,0.5"), prop("skew", 0f to "0.2,0")))
        val out = s.withPivotMoved(1, 500, 10f, -20f)
        assertEquals(listOf(10f, -20f), out.layer(1)!!.pivot.take(2))
        assertSamePicture(s, out, 1, frames)
    }

    @Test fun anAnimatedLocationMovesEveryKeyTogether() {
        val s = sceneOf(layer(1, prop("location", 0f to "0,0,0", 1f to "100,0,0"), prop("rotation", 0f to "90")))
        val out = s.withPivotMoved(1, 500, 5f, 0f)
        assertEquals(2, out.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes.size)
        assertSamePicture(s, out, 1, frames)
    }

    @Test fun anAnimatedPivotGetsAKeyAndTheLocationFollowsItsKeys() {
        val s = sceneOf(layer(1, prop("location", 0f to "50,50,0"), prop("pivot", 0f to "0,0,0", 1f to "20,0,0"), prop("scale", 0f to "1.5,1.5")))
        val out = s.withPivotMoved(1, 500, 30f, 10f)
        val transform = out.layer(1)!!.node.child("transform")!!
        assertEquals(listOf(0f, 0.5f, 1f), transform.child("pivot")!!.keyframes.map { it.t })
        assertEquals(listOf(0f, 0.5f, 1f), transform.child("location")!!.keyframes.map { it.t })
        assertSamePicture(s, out, 1, listOf(0, 500, 1000))
    }

    private fun locationTimes(scene: Scene, id: Long) = scene.layer(id)!!.node.child("transform")!!.child("location")!!.keyframes.map { it.t }

    @Test fun childrenOfAStillParentGetOneShiftAndNoFrameKeys() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "90"), prop("scale", 0f to "2,1")),
            layer(2, prop("location", 0f to "30,0,0", 1f to "60,10,0"), parent = 1),
        )
        val out = s.withPivotMoved(1, 500, 10f, 10f)
        assertEquals(listOf(0f, 1f), locationTimes(out, 2))
        assertTrue(locationTimes(out, 1).isEmpty())
        assertEquals("1", out.layer(2)!!.node["parent"])
    }

    @Test fun childrenOfAnAnimatedParentGetKeysAtTheParentsKeyTimes() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "0", 0.5f to "40", 1f to "90")),
            layer(2, prop("location", 0f to "30,0,0", 1f to "60,10,0"), parent = 1),
        )
        val out = s.withPivotMoved(1, 500, 10f, 10f)
        assertTrue(locationTimes(out, 2).containsAll(listOf(0f, 0.5f, 1f)))
        assertTrue(locationTimes(out, 1).containsAll(listOf(0f, 0.5f, 1f)))
        assertEquals("1", out.layer(2)!!.node["parent"])
    }

    @Test fun anAnimatedRotationKeepsEveryKeyedFrameAndStaysCloseBetween() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,50,0"), prop("rotation", 0f to "0", 1f to "90"), prop("scale", 0f to "2,1")))
        val out = s.withPivotMoved(1, 500, 12f, -8f)
        assertTrue(locationTimes(out, 1).containsAll(listOf(0f, 1f)))
        assertSamePicture(s, out, 1, listOf(0, 1000))
        assertSamePicture(s, out, 1, (0..1000 step 50).toList(), 0.6f)
    }

    @Test fun anAnimatedScaleNeedsOnlyItsTwoKeysAndKeepsEveryFrame() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,50,0"), prop("rotation", 0f to "35"), prop("scale", 0f to "1,1", 1f to "3,2")))
        val out = s.withPivotMoved(1, 250, -20f, 14f)
        assertEquals(listOf(0f, 1f), locationTimes(out, 1))
        assertSamePicture(s, out, 1, (0..1000 step 50).toList())
    }

    @Test fun aLayerThatDoesNotMoveKeepsAPlainLocation() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,50,0"), prop("rotation", 0f to "30"), prop("scale", 0f to "2,0.5")))
        val out = s.withPivotMoved(1, 500, 10f, -20f)
        assertTrue(locationTimes(out, 1).isEmpty())
        assertSamePicture(s, out, 1, frames)
    }

    @Test fun aPivotWithNonFiniteKeyframesIsEditedWithoutThrowing() {
        fun kf(t: String, v: String) = XmlNode("kf", linkedMapOf("t" to t, "v" to v))
        val only = XmlNode("pivot", mapOf("value" to "3,4,0"), listOf(kf("NaN", "9,9,0")))
        assertFalse(only.isKeyframed)
        val s = sceneOf(layer(1, prop("location", 0f to "10,10,0"), only))
        assertEquals(listOf(12f, -4f), s.withPivotMoved(1, 500, 12f, -4f).layer(1)!!.pivot.take(2))
        val mixed = XmlNode("pivot", children = listOf(kf("0", "0,0,0"), kf("Infinity", "9,9,0"), kf("1", "20,0,0")))
        assertTrue(mixed.isKeyframed)
        val moved = sceneOf(layer(1, prop("location", 0f to "10,10,0"), mixed)).withPivotMoved(1, 500, 30f, 10f)
        assertEquals(listOf(0f, 0.5f, 1f), moved.layer(1)!!.node.child("transform")!!.child("pivot")!!.keyframes.map { it.t })
        val badLocation = XmlNode("location", mapOf("value" to "10,10,0"), listOf(kf("-Infinity", "5,5,0")))
        val turning = sceneOf(layer(1, badLocation, prop("pivot", 0f to "0,0,0", 1f to "20,0,0"), prop("rotation", 0f to "0", 1f to "90")))
        assertTrue(turning.withPivotMoved(1, 500, 30f, 10f).layer(1)!!.location.all { it.isFinite() })
    }

    @Test fun aHugeTypedValueIsClampedAndNeverWritesInfinity() {
        val s = sceneOf(layer(1, prop("location", 0f to "10,10,0"), prop("scale", 0f to "2,2")))
        val out = s.withPivotMoved(1, 0, 1e38f, -1e38f)
        assertEquals(listOf(PIVOT_LIMIT, -PIVOT_LIMIT), out.layer(1)!!.pivot.take(2))
        assertTrue(out.layer(1)!!.location.all { it.isFinite() && abs(it) < 1e6f })
        assertFalse(out.toXml().contains("Infinity"))
    }

    @Test fun thePivotIsSavedAndALaterPivotEditIsNamedTransform() {
        val s = sceneOf(layer(1, prop("location", 0f to "10,10,0")), layer(2, prop("location", 0f to "5,5,0"), parent = 1))
        val out = s.withPivotMoved(1, 0, 12f, -4f)
        assertEquals(listOf(12f, -4f), Scene.parse(out.toXml()).layer(1)!!.pivot.take(2))
        assertEquals(app.absentmotion.R.string.history_transform, describeEdit(s, out).id)
    }

    @Test fun aRejectedOrUnchangedPivotLeavesTheSceneAlone() {
        val s = sceneOf(layer(1, prop("location", 0f to "10,10,0")))
        assertTrue(s.withPivotMoved(1, 0, 0f, 0f).node === s.node)
        assertTrue(s.withPivotMoved(1, 0, Float.NaN, 3f).node === s.node)
        assertTrue(s.withPivotMoved(9, 0, 3f, 3f).node === s.node)
    }
}
