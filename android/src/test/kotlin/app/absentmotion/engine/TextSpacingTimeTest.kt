package app.absentmotion.engine

import app.absentmotion.model.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSpacingTimeTest {
    private val spacing = "com.alightcreative.effects.textspacing"
    private val moving = """<shape id="2" startTime="0" endTime="1000" s=".rect"><transform><location><kf t="0" v="0,0,0"/><kf t="1" v="100,0,0"/></location></transform></shape>"""

    private fun scene(effect: String, other: String = moving) = Scene.parse(
        """<scene width="100" height="100" fps="30" totalTime="1000"><text id="1" startTime="0" endTime="1000"><content>A</content>$effect</text>$other</scene>""",
    ).toXml()

    @Test fun onlyKeyframedSpacingLaysTextOutAgainEveryFrame() {
        val still = """<effect id="$spacing"><property name="letterspacing" type="float" value="0.2"/></effect>"""
        val keyed = """<effect id="$spacing"><property name="letterspacing" type="float"><kf t="0" v="0"/><kf t="1" v="0.3"/></property></effect>"""
        val blur = """<effect id="absent.blur"><property name="strength" type="float"><kf t="0" v="0"/><kf t="1" v="9"/></property></effect>"""
        assertFalse(animatesTextSpacing(scene(still)))
        assertFalse(animatesTextSpacing(scene("""<effect id="$spacing"/>$blur""")))
        assertFalse(animatesTextSpacing(scene(blur)))
        assertTrue(animatesTextSpacing(scene(keyed, "")))
        assertTrue(animatesTextSpacing(scene(still + keyed)))
    }
}
