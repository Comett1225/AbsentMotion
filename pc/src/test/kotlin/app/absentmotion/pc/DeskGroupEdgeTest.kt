package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskGroupEdgeTest {
    private val xml = """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
        <embedScene id="10" label="Group" startTime="0" endTime="1000">
            <transform><location value="100,100,0"/><scale value="0.5,0.5"/></transform>
            <scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#00000000">
                <shape id="11" startTime="0" endTime="1000" fillType="color" s=".rect">
                    <transform><location value="200,100,0"/></transform>
                    <property name="size" type="vec2" value="50,50"/>
                    <fillColor value="#ffff0000"/>
                </shape>
            </scene>
        </embedScene>
    </scene>"""

    private fun red(p: Int) = (p shr 16 and 255) > 200 && (p shr 8 and 255) < 60

    @Test fun aSmallerGroupShowsWhatWasOutsideTheCanvas() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse(xml), null)
            val image = engine.render(0, 200, 200)
            assertTrue("inside the old edge", red(image.getRGB(140, 100)))
            assertTrue("past the old edge", red(image.getRGB(160, 100)))
            val quad = engine.quad(10, 0)!!
            assertEquals(175f, maxOf(quad[0], quad[2], quad[4], quad[6]), 1f)
            assertEquals(10L, engine.hitTest(160f, 100f, 0))
        }
    }
}
