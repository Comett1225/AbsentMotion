package app.absentmotion.editor

import app.absentmotion.shapeLayer
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class NestedTimingTest {
    @Test fun speedChangesLeaveNestedSceneKeyframesUnchanged() {
        val inner = XmlNode("scene", children = listOf(shapeLayer(children = listOf(XmlNode("transform", children = listOf(XmlNode("rotation", children = listOf(
            XmlNode("kf", mapOf("t" to "0.25", "v" to "0")), XmlNode("kf", mapOf("t" to "0.75", "v" to "90")))))))).node))
        val outer = shapeLayer("embedScene", children = listOf(inner))
        val map = XmlNode("speedMap", children = listOf(XmlNode("kf", mapOf("t" to "0", "v" to "1")), XmlNode("kf", mapOf("t" to "2000000000", "v" to "3"))))
        assertEquals(inner, outer.withVideoSpeedMap(map).node.child("scene"))
    }
}
