package app.absentmotion.ai

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.replaceMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedMarksAndTurnTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30"><shape id="1" fillType="media" fillImage="cut.png" startTime="0" endTime="999" s=".rect"><ai type="background" original="raw.png" result="cut.png"/></shape></scene>""")

    @Test fun replacingAnAiResultDropsItsMarkOnlyWhenAsked() {
        val with = MediaDeclaration(XmlNode("media", mapOf("uri" to "new.png", "type" to "image/png")))
        assertTrue(scene.replaceMedia("cut.png", with, dropMarks = true).layer(1)!!.aiMarks.isEmpty())
        assertEquals("new.png", scene.replaceMedia("cut.png", with).layer(1)!!.aiMarks.single().result)
    }

    @Test fun turningMatteBytesRoundTrips() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), data.turned(3, 2, 90))
        assertArrayEquals(data, data.turned(3, 2, 90).turned(2, 3, 270))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), data.turned(3, 2, 180))
    }
}
