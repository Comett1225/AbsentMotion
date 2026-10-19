package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class StrokeControlTest {
    private fun layer(kind: String = "shape", attrs: Map<String, String> = emptyMap(), children: List<XmlNode> = emptyList()) =
        Layer(XmlNode(kind, mapOf("id" to "1", "startTime" to "0", "endTime" to "2000") + attrs, children))

    @Test fun strokeDefaultsMatchTheRendererAndEveryCapCanBeSelected() {
        assertEquals(1f, layer(children = listOf(XmlNode("path-stroke", mapOf("end" to "arrow")))).stroke.endSize, 0f)
    }

    @Test fun buttRoundSquareAndUnknownCapsHaveTheRightSelection() {
        assertEquals(listOf(0, 1, 2, 1), listOf("butt", "round", "square", "unknown").map(::strokeCapIndex))
    }
}
