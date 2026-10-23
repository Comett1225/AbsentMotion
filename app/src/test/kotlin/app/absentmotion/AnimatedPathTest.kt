package app.absentmotion

import app.absentmotion.model.Contour
import app.absentmotion.model.Knot
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import app.absentmotion.model.isShapeAnimated
import app.absentmotion.model.keyframes
import app.absentmotion.model.shapeKeyIndex
import app.absentmotion.model.shapeKeys
import app.absentmotion.model.withContours
import app.absentmotion.model.withContoursAt
import app.absentmotion.model.withEveryShape
import app.absentmotion.model.withPointInserted
import app.absentmotion.model.withShapeKeyToggled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedPathTest {
    private val square = listOf(Contour(listOf(Knot(Pt(0f, 0f)), Knot(Pt(100f, 0f)), Knot(Pt(100f, 100f)), Knot(Pt(0f, 100f))), closed = true))
    private val bigger = listOf(Contour(listOf(Knot(Pt(-20f, -20f)), Knot(Pt(140f, 0f), Pt(140f, -30f), Pt(140f, 30f)), Knot(Pt(100f, 100f)), Knot(Pt(0f, 140f))), closed = true))
    private val base = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "1000"))).withContours(square)

    private fun near(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    private fun animated(): Layer {
        val first = base.copy(t = 0.2f, snap = 0.2f - 0.01f..0.2f + 0.01f).withShapeKeyToggled()
        return first.copy(t = 0.6f, snap = 0.6f - 0.01f..0.6f + 0.01f).withContoursAt(bigger)
    }

    @Test fun aKeyframeToggleTurnsAStaticPathIntoOneShapeKey() {
        assertFalse(base.isShapeAnimated)
        val keyed = base.copy(t = 0.2f, snap = 0.2f - 0.01f..0.2f + 0.01f).withShapeKeyToggled()
        assertTrue(keyed.isShapeAnimated)
        assertEquals(listOf(0.2f), keyed.shapeKeys.map { it.t })
        assertEquals(square, keyed.contours)
        val path = keyed.node.child("path")!!
        assertEquals("true", path["closed"])
        val knots = path.children.filter { it.name == "knot" }
        assertEquals(4, knots.size)
        assertEquals(listOf(0.2f), knots[1].child("p")!!.keyframes.map { it.t })
        assertEquals("100.000000,0.000000", knots[1].child("p")!!.keyframes.single().v)
        assertNull(knots[1].child("in"))
        assertEquals(0, keyed.copy(t = 0.2f, snap = 0.2f - 0.01f..0.2f + 0.01f).shapeKeyIndex())
    }

    @Test fun movingPointsAtANewTimeAddsAShapeKeyAndInterpolatesPointByPoint() {
        val layer = animated()
        assertEquals(listOf(0.2f, 0.6f), layer.shapeKeys.map { it.t })
        assertEquals(square, layer.copy(t = 0f).contours)
        assertEquals(bigger, layer.copy(t = 0.9f).contours)
        val middle = layer.copy(t = 0.4f).contours!!.single().knots
        near(Pt(-10f, -10f), middle[0].p)
        near(Pt(120f, 0f), middle[1].p)
        near(Pt(120f, -15f), middle[1].inHandle!!)
        near(Pt(120f, 15f), middle[1].outHandle!!)
        near(Pt(0f, 120f), middle[3].p)
        assertNull(layer.copy(t = 0.2f).contours!!.single().knots[1].inHandle)
        val edited = layer.copy(t = 0.6f, snap = 0.6f - 0.01f..0.6f + 0.01f).withContoursAt(square)
        assertEquals(2, edited.shapeKeys.size)
        assertEquals(square, edited.copy(t = 0.6f).contours)
    }

    @Test fun removingEveryKeyLeavesTheLastShapeAsAStaticPath() {
        val one = animated().copy(t = 0.2f, snap = 0.2f - 0.01f..0.2f + 0.01f).withShapeKeyToggled()
        assertEquals(listOf(0.6f), one.shapeKeys.map { it.t })
        val plain = one.copy(t = 0.6f, snap = 0.6f - 0.01f..0.6f + 0.01f).withShapeKeyToggled()
        assertFalse(plain.isShapeAnimated)
        assertNotNull(plain.node.child("path")!!["d"])
        assertEquals(bigger, plain.contours)
    }

    @Test fun addingAPointReachesEveryShapeKeyAndKeepsEachShape() {
        val layer = animated().withEveryShape { list -> list.map { it.withPointInserted(0, 0.5f) } }
        val keys = layer.shapeKeys
        assertEquals(2, keys.size)
        assertEquals(Pt(50f, 0f), keys[0].contours.single().knots[1].p)
        assertEquals(5, keys[1].contours.single().knots.size)
        assertEquals(5, layer.copy(t = 0.4f).contours!!.single().knots.size)
    }

    @Test fun shapeKeysMoveWithTheLayerTimesLikeOtherKeyframes() {
        val layer = animated()
        assertEquals(listOf(200, 600), layer.keyframeTimes())
        val moved = layer.movedTo(1000)
        assertEquals(listOf(1200, 1600), moved.keyframeTimes())
        assertEquals(bigger, moved.copy(t = 0.6f).contours)
    }

    @Test fun anImportedPathWithUnalignedKnotKeysStaysReadableAndEditable() {
        fun vec(name: String, vararg keys: Pair<Float, String>) = if (keys.size == 1 && keys[0].first < 0) XmlNode(name, mapOf("value" to keys[0].second))
            else XmlNode(name, children = keys.map { (t, v) -> XmlNode("kf", linkedMapOf("t" to "$t", "v" to v)) })
        val path = XmlNode("path", linkedMapOf("closed" to "false"), listOf(
            XmlNode("knot", children = listOf(vec("p", 0f to "0,0", 1f to "100,0"))),
            XmlNode("knot", children = listOf(vec("p", -1f to "50,50"), vec("out", 0.5f to "10,0"))),
            XmlNode("knot", children = listOf(vec("p", 0.25f to "0,100", 0.75f to "0,200"))),
        ))
        val layer = Layer(XmlNode("shape", linkedMapOf("id" to "2", "startTime" to "0", "endTime" to "1000"), listOf(path)))
        assertTrue(layer.isShapeAnimated)
        val atHalf = layer.copy(t = 0.5f).contours!!.single()
        near(Pt(50f, 0f), atHalf.knots[0].p)
        near(Pt(60f, 50f), atHalf.knots[1].outHandle!!)
        near(Pt(0f, 150f), atHalf.knots[2].p)
        assertFalse(atHalf.closed)
        val keys = layer.shapeKeys
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), keys.map { it.t })
        val rewritten = layer.withEveryShape { it }
        for (key in keys) assertEquals(key.contours, rewritten.copy(t = key.t).contours)
        assertEquals(keys.map { it.t }, rewritten.shapeKeys.map { it.t })
    }

    @Test fun staticKnotPathsReadAsBefore() {
        val path = XmlNode("path", linkedMapOf("closed" to "false"), listOf(
            XmlNode("knot", children = listOf(XmlNode("p", mapOf("value" to "1,2")), XmlNode("in", mapOf("value" to "0,0")))),
            XmlNode("knot", children = listOf(XmlNode("p", mapOf("value" to "5,6")))),
        ))
        val layer = Layer(XmlNode("shape", mapOf("id" to "3"), listOf(path)))
        assertFalse(layer.isShapeAnimated)
        assertEquals(listOf(Knot(Pt(1f, 2f), Pt(1f, 2f)), Knot(Pt(5f, 6f))), layer.contours!!.single().knots)
        assertTrue(layer.shapeKeys.isEmpty())
    }
}
