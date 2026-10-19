package app.absentmotion

import app.absentmotion.editor.canToggleClosed
import app.absentmotion.model.Contour
import app.absentmotion.model.Knot
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import app.absentmotion.model.parseSvgPath
import app.absentmotion.model.toSvg
import app.absentmotion.model.withContours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorPathsTest {
    private fun roundTrips(c: Contour, times: Int = 5): List<Contour> =
        generateSequence(c) { parseSvgPath(it.toSvg()).single() }.drop(1).take(times).toList()

    @Test fun closedPathStartingWithACurveAndEndingWithAStraightLineKeepsItsPoints() {
        val c = Contour(listOf(Knot(Pt(0f, 0f), outHandle = Pt(10f, 0f)), Knot(Pt(20f, 0f)), Knot(Pt(20f, 20f))), closed = true)
        assertEquals("M 0 0C 10 0, 20 0, 20 0L 20 20L 0 0Z", c.toSvg())
        roundTrips(c).forEach { assertEquals(c, it) }
    }

    @Test fun closingAPathWhoseFirstPointOnlyHasAnOutHandleAddsNoPoint() {
        assertEquals(3, parseSvgPath("M 0 0C 10 0, 40 40, 50 50L 100 0L 0 0Z").single().knots.size)
        val twoPoints = Contour(listOf(Knot(Pt(0f, 0f), outHandle = Pt(5f, 5f)), Knot(Pt(10f, 0f))), closed = true)
        roundTrips(twoPoints).forEach { assertEquals(twoPoints, it) }
    }

    @Test fun aCurvedClosingSegmentStillMovesItsHandleToTheFirstPoint() {
        val c = Contour(listOf(Knot(Pt(0f, 0f), Pt(-10f, 5f), Pt(10f, 0f)), Knot(Pt(20f, 0f)), Knot(Pt(20f, 20f))), closed = true)
        roundTrips(c).forEach { assertEquals(c, it) }
    }

    @Test fun aOnePointContourKeepsItsHandles() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1")))
        val pulled = listOf(Contour(listOf(Knot(Pt(3f, 4f), Pt(-7f, 4f), Pt(13f, 4f))), closed = false))
        assertEquals(pulled, layer.withContours(pulled).contours)
        val sharp = listOf(Contour(listOf(Knot(Pt(3f, 4f))), closed = false))
        assertEquals(sharp, layer.withContours(sharp).contours)
    }

    @Test fun relativeCommandsAreOffsetFromTheCurrentPoint() {
        assertEquals(Pt(110f, 100f), parseSvgPath("M 100 100 l 10 0").single().knots[1].p)
        assertEquals(Pt(10f, 0f), parseSvgPath("M 100 100 L 10 0").single().knots[1].p)
        val (a, b) = parseSvgPath("m 10 10 20 0 h 5 v 5 c 0 3, 3 6, 6 6 z m 1 1 l 2 0")
        assertEquals(listOf(Pt(10f, 10f), Pt(30f, 10f), Pt(35f, 10f), Pt(35f, 15f), Pt(41f, 21f)), a.knots.map { it.p })
        assertEquals(Pt(35f, 18f), a.knots[3].outHandle)
        assertEquals(Pt(38f, 21f), a.knots[4].inHandle)
        assertEquals(true, a.closed)
        assertEquals(listOf(Pt(11f, 11f), Pt(13f, 11f)), b.knots.map { it.p })
    }

    @Test fun aClosedTwoPointPathCanBeOpenedAndClosedAgainWithoutLosingItsCurves() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1")))
        val lens = Contour(listOf(Knot(Pt(0f, 0f), Pt(0f, 20f), Pt(0f, -20f)), Knot(Pt(40f, 0f), Pt(40f, -20f), Pt(40f, 20f))), closed = true)
        assertTrue(lens.canToggleClosed)
        assertTrue(lens.copy(closed = false).canToggleClosed)
        val opened = layer.withContours(listOf(lens.copy(closed = false))).contours!!
        assertEquals(listOf(lens.copy(closed = false)), opened)
        assertEquals(listOf(lens), layer.withContours(opened.map { it.copy(closed = true) }).contours)
        assertEquals(listOf(lens), layer.withContours(listOf(lens)).contours)
        val plain = listOf(Contour(listOf(Knot(Pt(0f, 0f), outHandle = Pt(5f, 5f)), Knot(Pt(10f, 0f))), closed = false))
        assertEquals("M 0 0C 5 5, 10 0, 10 0", layer.withContours(plain).node.child("path")!!["d"])
    }
}
