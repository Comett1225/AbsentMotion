package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class NestedTimingTest {
    private fun layer(kind: String = "shape", attrs: Map<String, String> = emptyMap(), children: List<XmlNode> = emptyList()) =
        Layer(XmlNode(kind, mapOf("id" to "1", "startTime" to "0", "endTime" to "2000") + attrs, children))

    @Test fun speedChangesLeaveNestedSceneKeyframesUnchanged() {
        val inner = XmlNode("scene", children = listOf(layer(children = listOf(XmlNode("transform", children = listOf(XmlNode("rotation", children = listOf(
            XmlNode("kf", mapOf("t" to "0.25", "v" to "0")), XmlNode("kf", mapOf("t" to "0.75", "v" to "90")))))))).node))
        val outer = layer("embedScene", children = listOf(inner))
        val map = XmlNode("speedMap", children = listOf(XmlNode("kf", mapOf("t" to "0", "v" to "1")), XmlNode("kf", mapOf("t" to "2000000000", "v" to "3"))))
        assertEquals(inner, outer.withVideoSpeedMap(map).node.child("scene"))
    }
}
