package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.contours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShapeCatalogTest {
    private val entries = XmlNode.parse(TestData.project.resolve("app/src/main/assets/shapes.xml").readText()).children

    @Test fun hasFiftyFourEntriesTheFirstNineteenWithIds() {
        assertEquals(54, entries.size)
        val ids = entries.take(19).map { it["s"].orEmpty() }
        assertTrue("every parametric entry has an id", ids.all { it.startsWith(".") })
        assertEquals("ids are unique", 19, ids.toSet().size)
        assertTrue("the rest are drawn", entries.drop(19).all { it["s"].isNullOrEmpty() })
    }

    @Test fun drawnShapesHaveUniqueNamesAndPathsThatParseToClosedOutlinesAroundTheOrigin() {
        val drawn = entries.drop(19)
        val names = drawn.map { it["name"].orEmpty() }
        assertTrue("names are not empty", names.all { it.isNotBlank() })
        assertEquals("names are unique: $names", drawn.size, names.toSet().size)
        val problems = ArrayList<String>()
        for (def in drawn) {
            val name = def["name"]
            val contours = Layer(XmlNode("shape", mapOf("id" to "1"), def.children)).contours
            if (contours.isNullOrEmpty()) { problems += "$name has no contours"; continue }
            if (contours[0].exclude) problems += "$name starts with a cut contour"
            if (contours.any { it.knots.size < 3 }) problems += "$name has a contour with fewer than 3 points"
            if (contours.any { !it.closed }) problems += "$name has an open contour"
            val points = contours.flatMap { c -> c.knots.map { it.p } }
            val w = points.maxOf { it.x } - points.minOf { it.x }
            val h = points.maxOf { it.y } - points.minOf { it.y }
            if (maxOf(w, h) !in 120f..205f) problems += "$name is not about 200 across ($w x $h)"
            if (abs(points.maxOf { it.x } + points.minOf { it.x }) > 40f || abs(points.maxOf { it.y } + points.minOf { it.y }) > 40f) problems += "$name is not centred"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }
}
