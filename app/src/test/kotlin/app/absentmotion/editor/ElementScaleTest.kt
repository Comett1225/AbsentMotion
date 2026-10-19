package app.absentmotion.editor

import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class ElementScaleTest {
    @Test fun missingScaleGetsTheDefaultBeforeApplyingGroupScale() {
        val node = XmlNode("shape").mapTransform("scale") { it[0] *= 2f; it[1] *= 3f }
        assertEquals("2.000000,3.000000", node.child("transform")?.child("scale")?.get("value"))
    }

    @Test fun shortLocationsAndAnimatedScalesKeepTheirDefaultComponents() {
        val shape = XmlNode("shape", children = listOf(XmlNode("transform", children = listOf(
            XmlNode("location", mapOf("value" to "10")),
            XmlNode("scale", children = listOf(XmlNode("kf", mapOf("t" to "0.5", "v" to "2,3"))))))))
        val node = shape.mapTransform("location") { it[1] += 20f }.mapTransform("scale") { it[0] *= 2f; it[1] *= 2f }
        assertEquals("10.000000,20.000000,0.000000", node.child("transform")?.child("location")?.get("value"))
        assertEquals("4.000000,6.000000", node.child("transform")?.child("scale")?.children?.single()?.get("v"))
    }
}
