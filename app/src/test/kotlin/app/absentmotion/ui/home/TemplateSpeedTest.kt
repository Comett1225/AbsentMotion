package app.absentmotion.ui.home

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MediaFillMode
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateSpeedTest {
    @Test fun replacementSlotsMeasureTheSourceMediaConsumedAtTheSelectedSpeed() {
        val scene = Scene.parse("""<scene><shape id="1" startTime="0" endTime="2000" fillType="media" fillVideo="content://clip" inTime="100" outTime="4100" speed="2"/></scene>""")
        assertEquals(4000, slotsOf(scene).single().length)
    }

    @Test fun aSpeedupWithoutAnOutPointKeepsTheSourceItConsumes() {
        val scene = Scene.parse("""<scene><media uri="file:/v.mp4" type="video/mp4" duration="4000000" width="100" height="50" orientation="0"/><shape id="1" startTime="0" endTime="1000" fillType="media" fillVideo="file:/v.mp4" speed="2"/></scene>""")
        val slot = slotsOf(scene).single()
        assertEquals(2000, slot.length)
        val long = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "content://long", "type" to "video/mp4", "duration" to "20000000", "width" to "100", "height" to "50", "orientation" to "0")))
        val layer = applied(scene, listOf(slot), mapOf("file:/v.mp4" to Choice(long, 3000, MediaFillMode.Fill))).layer(1)!!.node
        assertEquals("3000", layer["inTime"])
        assertEquals("5000", layer["outTime"])
    }

    @Test fun replacingALoopMovesItsLoopStartWithTheTrim() {
        val scene = Scene.parse("""<scene><media uri="file:/v.mp4" type="video/mp4" duration="10000000" width="100" height="50" orientation="0"/><shape id="1" startTime="0" endTime="20000" fillType="media" fillVideo="file:/v.mp4" inTime="5000" loopIn="2000" outTime="8000" loop="true"/></scene>""")
        val slot = slotsOf(scene).single()
        assertEquals(6000, slot.length)
        fun swapped(durationUs: Long, trim: Int) = applied(scene, listOf(slot), mapOf("file:/v.mp4" to Choice(MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "content://new", "type" to "video/mp4", "duration" to "$durationUs", "width" to "100", "height" to "50", "orientation" to "0"))), trim, MediaFillMode.Fill))).layer(1)!!.node
        val long = swapped(30_000_000, 8000)
        assertEquals(listOf("11000", "8000", "14000"), listOf(long["inTime"], long["loopIn"], long["outTime"]))
        val short = swapped(5_000_000, 0)
        assertEquals(listOf("3000", "0", "5000"), listOf(short["inTime"], short["loopIn"], short["outTime"]))
    }
}
