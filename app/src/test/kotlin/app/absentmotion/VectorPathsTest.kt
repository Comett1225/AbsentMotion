package app.absentmotion

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
}
