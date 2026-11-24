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

    private fun filled(type: String, color: String) = """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
        <embedScene id="10" label="Group" startTime="0" endTime="1000" fillType="$type">
            <transform><location value="100,100,0"/></transform>
            $color
            <scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#00000000">
                <shape id="11" startTime="0" endTime="1000" fillType="color" s=".rect">
                    <transform><location value="60,100,0"/></transform>
                    <property name="size" type="vec2" value="30,30"/>
                    <fillColor value="#ff2060ff"/>
                </shape>
                <shape id="12" startTime="0" endTime="1000" fillType="color" s=".rect">
                    <transform><location value="140,100,0"/></transform>
                    <property name="size" type="vec2" value="30,30"/>
                    <fillColor value="#80ffffff"/>
                </shape>
            </scene>
        </embedScene>
    </scene>"""

    private fun near(p: Int, r: Int, g: Int, b: Int) =
        Math.abs((p shr 16 and 255) - r) <= 3 && Math.abs((p shr 8 and 255) - g) <= 3 && Math.abs((p and 255) - b) <= 3

    @Test fun aColouredGroupPaintsItsColourOverWhatItShows() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse(filled("intrinsic", """<fillColor value="#ff000000"/>""")), null)
            var image = engine.render(0, 200, 200)
            assertTrue("intrinsic keeps the colours", near(image.getRGB(60, 100), 32, 96, 255))
            engine.setScene(Scene.parse(filled("color", """<fillColor><kf t="0" v="#ffff0000"/><kf t="1" v="#80ff0000"/></fillColor>""")), null)
            image = engine.render(0, 200, 200)
            assertTrue("full colour makes the silhouette red", near(image.getRGB(60, 100), 255, 0, 0))
            assertTrue("see-through parts stay see-through", near(image.getRGB(140, 100), 128, 0, 0))
            assertTrue("outside stays clear", near(image.getRGB(100, 20), 0, 0, 0))
            image = engine.render(990, 200, 200)
            assertTrue("half colour mixes half", near(image.getRGB(60, 100), 144, 48, 127))
        }
    }
}
