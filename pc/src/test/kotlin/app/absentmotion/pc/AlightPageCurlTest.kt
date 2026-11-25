package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlightPageCurlTest {
    private fun grey(roll: Int, x: Int): Int {
        val scene = Scene.parse(
            """<scene width="300" height="300" fps="30" totalTime="1000" bgcolor="#ff000000">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="150,150,0"/></transform>
            <property name="size" type="vec2" value="150,150"/><fillColor value="#ffffffff"/>
            <effect id="absent.pagecurl"><property name="progress" type="float" value="0.4"/><property name="radius" type="float" value="0.1"/>
            <property name="angle" type="float" value="0"/><property name="backAlpha" type="float" value="1"/><property name="shine" type="float" value="0"/>
            <property name="shadow" type="float" value="0"/><property name="follow" type="bool" value="false"/><property name="style" type="float" value="1"/>
            <property name="roll" type="float" value="$roll"/></effect></shape></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            return engine.render(0, 300, 300).getRGB(x, 150) and 0xff
        }
    }

    @Test fun alightPagesRollUpInsteadOfFoldingAFlapBack() {
        assertEquals(255, grey(1, 100))
        assertEquals(136f, grey(1, 168).toFloat(), 6f)
        assertEquals(0, grey(1, 215))
        assertEquals(122f, grey(0, 100).toFloat(), 6f)
    }
}
