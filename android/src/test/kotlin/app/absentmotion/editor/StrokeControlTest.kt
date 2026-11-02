package app.absentmotion.editor

import app.absentmotion.shapeLayer
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class StrokeControlTest {
    @Test fun strokeDefaultsMatchTheRendererAndEveryCapCanBeSelected() {
        assertEquals(1f, shapeLayer(children = listOf(XmlNode("path-stroke", mapOf("end" to "arrow")))).stroke.endSize, 0f)
    }

    @Test fun buttRoundSquareAndUnknownCapsHaveTheRightSelection() {
        assertEquals(listOf(0, 1, 2, 1), listOf("butt", "round", "square", "unknown").map(::strokeCapIndex))
    }
}
