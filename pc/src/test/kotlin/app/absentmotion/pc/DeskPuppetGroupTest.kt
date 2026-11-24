package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskPuppetGroupTest {
    private val inner = """<shape id="11" startTime="0" endTime="1000" fillType="color" s=".rect">
        <transform><location value="200,200,0"/></transform>
        <property name="size" type="vec2" value="100,75"/>
        <fillColor value="#ffff0000"/>
        <effect id="absent.puppet"><property name="falloff" type="float" value="1"/>
            <property name="rest1" type="vec2" value="-90,-60"/><property name="pin1" type="vec2" value="-90,-60"/>
            <property name="rest2" type="vec2" value="-90,60"/><property name="pin2" type="vec2" value="-90,60"/>
            <property name="rest3" type="vec2" value="90,0"/><property name="pin3" type="vec2" value="250,0"/>
        </effect>
    </shape>"""

    private fun scene(location: String, groupPuppet: Boolean) = """<scene width="400" height="400" fps="30" totalTime="1000" bgcolor="#ff000000">
        <embedScene id="10" label="Group" startTime="0" endTime="1000">
            <transform><location value="$location"/></transform>
            <scene width="400" height="400" fps="30" totalTime="1000" bgcolor="#00000000">$inner</scene>
            ${if (groupPuppet) """<effect id="absent.puppet"><property name="falloff" type="float" value="1"/>
                <property name="rest1" type="vec2" value="-70,0"/><property name="pin1" type="vec2" value="-70,0"/>
                <property name="rest2" type="vec2" value="70,0"/><property name="pin2" type="vec2" value="70,20"/>
            </effect>""" else ""}
        </embedScene>
    </scene>"""

    private fun red(p: Int) = (p shr 16 and 255) > 200 && (p shr 8 and 255) < 60

    private fun render(xml: String) = File("..").canonicalFile.let { root ->
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse(xml), null)
            engine.render(0, 400, 400)
        }
    }

    @Test fun aSecondPuppetOnTheGroupKeepsTheWholeBentLayer() {
        val image = render(scene("200,200,0", groupPuppet = true))
        for ((x, y) in listOf(110 to 140, 110 to 260, 200 to 200, 290 to 220)) assertTrue("red at $x,$y", red(image.getRGB(x, y)))
    }

    @Test fun aBentLayerInAGroupIsNotCutAtTheGroupFrame() {
        val image = render(scene("100,200,0", groupPuppet = false))
        assertTrue("inside the frame", red(image.getRGB(280, 200)))
        assertTrue("past the group frame", red(image.getRGB(330, 200)))
    }
}
