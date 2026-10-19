package app.absentmotion.ui.home

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateSpeedTest {
    @Test fun replacementSlotsMeasureTheSourceMediaConsumedAtTheSelectedSpeed() {
        val scene = Scene.parse("""<scene><shape id="1" startTime="0" endTime="2000" fillType="media" fillVideo="content://clip" inTime="100" outTime="4100" speed="2"/></scene>""")
        assertEquals(4000, slotsOf(scene).single().length)
    }
}
