package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withResizedTextWrap
import app.absentmotion.model.wrapWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class TextWrapTest {
    private fun text(align: String, rotation: Float, scale: Float) = Layer(XmlNode("text", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "1000",
        "wrapWidth" to "100", "align" to align))).withTransform("location", 100f, 100f, 0f).withTransform("rotation", rotation).withTransform("scale", scale, scale)

    @Test fun theFixedEdgeStaysPutOnATurnedAndScaledText() {
        val wider = text("left", 90f, 2f).withResizedTextWrap(200)
        assertEquals(200, wider.wrapWidth)
        assertEquals(100f, wider.location[0], 1e-3f)
        assertEquals(200f, wider.location[1], 1e-3f)
        val plain = text("right", 0f, 1f).withResizedTextWrap(160)
        assertEquals(70f, plain.location[0], 1e-3f)
        assertEquals(100f, plain.location[1], 1e-3f)
        assertEquals(100f, text("center", 30f, 3f).withResizedTextWrap(300).location[0], 1e-3f)
    }
}
