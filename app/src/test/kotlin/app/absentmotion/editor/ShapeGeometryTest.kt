package app.absentmotion.editor

import app.absentmotion.TestData
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class ShapeGeometryTest {
    private fun layer(kind: String = "shape", attrs: Map<String, String> = emptyMap(), children: List<XmlNode> = emptyList()) =
        Layer(XmlNode(kind, mapOf("id" to "1", "startTime" to "0", "endTime" to "2000") + attrs, children))

    @Test fun checkMarkHasNoRepeatedAdjacentCorners() {
        val def = XmlNode.parse(TestData.project.resolve("app/src/main/assets/shapes.xml").readText()).children.single { it["name"] == "Check Mark" }
        for (c in Layer(XmlNode("shape", mapOf("id" to "1"), def.children)).contours!!) {
            assertTrue(c.knots.indices.none { c.knots[it].p == c.knots[(it + 1) % c.knots.size].p })
        }
    }
}
