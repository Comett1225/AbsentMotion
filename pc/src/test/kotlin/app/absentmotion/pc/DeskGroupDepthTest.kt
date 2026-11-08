package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskGroupDepthTest {
    private val planes = """
        <shape id="11" startTime="0" endTime="1000" fillType="color" s=".rect">
            <transform><location value="200,150,0"/><tilt value="0,-50"/></transform>
            <property name="size" type="vec2" value="120,80"/><fillColor value="#ff0000ff"/>
        </shape>
        <shape id="12" startTime="0" endTime="1000" fillType="color" s=".rect">
            <transform><location value="200,150,0"/><tilt value="0,50"/></transform>
            <property name="size" type="vec2" value="120,80"/><fillColor value="#ffff0000"/>
        </shape>"""

    private fun scene(body: String) = """<scene width="400" height="300" fps="30" totalTime="1000" bgcolor="#ff000000" depthOrder="true">$body</scene>"""

    private val grouped = scene("""<embedScene id="10" startTime="0" endTime="1000"><transform><location value="200,150,0"/></transform>
        <scene width="400" height="300" fps="30" totalTime="1000" bgcolor="#00000000">$planes</scene></embedScene>""")

    @Test fun aGroupSortsItsThreeDLayersLikeTheProjectDoes() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse(scene(planes)), null)
            val loose = engine.render(0, 400, 300)
            engine.setScene(Scene.parse(grouped), null)
            val inGroup = engine.render(0, 400, 300)
            val xs = (90..310 step 10)
            val blue = xs.count { loose.getRGB(it, 150) and 0xff > 200 }
            assertTrue("the blue plane is in front somewhere at the top level", blue > 0)
            for (x in xs) assertEquals("pixel $x", loose.getRGB(x, 150) and 0xf0f0f0, inGroup.getRGB(x, 150) and 0xf0f0f0)
        }
    }
}
