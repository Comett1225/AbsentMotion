package app.absentmotion.editor

import app.absentmotion.TestData
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class ShapeGeometryTest {
    @Test fun checkMarkHasNoRepeatedAdjacentCorners() {
        val def = XmlNode.parse(TestData.project.resolve("app/src/main/assets/shapes.xml").readText()).children.single { it["name"] == "Check Mark" }
        val contours = Layer(XmlNode("shape", mapOf("id" to "1"), def.children)).contours!!
        assertTrue("check mark contours", contours.isNotEmpty())
        for (c in contours) {
            assertTrue("check mark corners", c.knots.size >= 3)
            assertTrue(c.knots.indices.none { c.knots[it].p == c.knots[(it + 1) % c.knots.size].p })
        }
    }
}
