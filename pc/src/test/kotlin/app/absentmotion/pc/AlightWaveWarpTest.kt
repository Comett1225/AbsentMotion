package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlightWaveWarpTest {
    private fun swing(axes: Int): Float {
        val scene = Scene.parse(
            """<scene width="1280" height="720" fps="30" totalTime="1000" bgcolor="#ff000000">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="640,360,0"/></transform>
            <property name="size" type="vec2" value="3,150"/><fillColor value="#ffffffff"/>
            <effect id="absent.wavewarp"><property name="angle" type="float" value="90"/><property name="spacing" type="float" value="20"/>
            <property name="magnitude" type="float" value="1"/><property name="warp" type="float" value="90"/><property name="axes" type="float" value="$axes"/></effect></shape></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            val image = engine.render(0, 1280, 720)
            val centres = (250 until 470).mapNotNull { y ->
                val xs = (560 until 720).filter { x -> image.getRGB(x, y) and 0xff > 128 }
                if (xs.isEmpty()) null else xs.average().toFloat()
            }
            return (centres.max() - centres.min()) / 2
        }
    }

    @Test fun alightWavesMoveByAHundredthOfTheFrameOnEachAxis() {
        assertEquals(7.2f, swing(0), 1f)
        assertEquals(12.8f, swing(1), 1f)
    }
}
