package app.absentmotion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolygonTrianglesTest {
    private val notched = listOf(0f to 0f, 10f to 0f, 10f to 20f, 6f to 20f, 6f to 18f, 4f to 18f, 4f to 20f, 0f to 20f)

    private fun area(points: List<Pair<Float, Float>>, a: Int, b: Int, c: Int): Float {
        val (ax, ay) = points[a]; val (bx, by) = points[b]; val (cx, cy) = points[c]
        return ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2f
    }

    private fun inside(points: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var hit = false
        for (i in points.indices) {
            val (x1, y1) = points[i]; val (x2, y2) = points[(i + 1) % points.size]
            if ((y1 > y) != (y2 > y) && x < x1 + (y - y1) / (y2 - y1) * (x2 - x1)) hit = !hit
        }
        return hit
    }

    private fun check(points: List<Pair<Float, Float>>, xs: FloatArray, ys: FloatArray, zs: FloatArray) {
        val order = polygonTriangles(xs, ys, zs)
        assertEquals((points.size - 2) * 3, order.size)
        var total = 0f
        for (k in order.indices step 3) {
            val a = order[k]; val b = order[k + 1]; val c = order[k + 2]
            total += abs(area(points, a, b, c))
            val cx = (points[a].first + points[b].first + points[c].first) / 3f
            val cy = (points[a].second + points[b].second + points[c].second) / 3f
            assertTrue("triangle $a $b $c lies outside the face", inside(points, cx, cy))
        }
        assertEquals(10f * 20f - 2f * 2f, total, 1e-3f)
    }

    @Test fun aNotchedFaceIsCutIntoTrianglesThatStayInsideIt() {
        val xs = FloatArray(notched.size) { notched[it].first }
        val ys = FloatArray(notched.size) { notched[it].second }
        check(notched, xs, ys, FloatArray(notched.size))
        check(notched, FloatArray(notched.size), ys, xs)
        val reversed = notched.reversed()
        check(reversed, FloatArray(reversed.size) { reversed[it].first }, FloatArray(reversed.size) { reversed[it].second }, FloatArray(reversed.size))
    }

    @Test fun triangleAndDegenerateFacesStillWork() {
        assertEquals(listOf(0, 1, 2), polygonTriangles(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), FloatArray(3)).toList())
        assertEquals(6, polygonTriangles(FloatArray(4), FloatArray(4), FloatArray(4)).size)
    }
}
