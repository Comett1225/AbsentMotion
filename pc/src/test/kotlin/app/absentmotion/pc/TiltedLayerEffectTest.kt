package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TiltedLayerEffectTest {
    private fun red(engine: DesktopEngine, tiltY: Int, wipe: Boolean): Pair<Int, Int> {
        val effect = if (wipe) """<effect id="absent.wipe"><property name="start" type="float" value="0.5"/></effect>""" else ""
        engine.setScene(Scene.parse(
            """<scene width="400" height="400" fps="30" totalTime="1000" bgcolor="#ff000000" depthOrder="true">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect" threeD="true"><transform><location value="200,200,0"/><tilt value="0,$tiltY"/></transform>
            <property name="size" type="vec2" value="150,150"/><fillColor value="#ffff0000"/>$effect</shape></scene>"""
        ), null)
        val image = engine.render(0, 400, 400)
        var left = 0
        var right = 0
        for (y in 0 until 400) for (x in 0 until 400) {
            if ((image.getRGB(x, y) shr 16 and 0xff) <= 128) continue
            if (x < 198) left++ else if (x > 202) right++
        }
        return left to right
    }

    @Test fun wipeCutsATiltedLayerAlongItsOwnMiddle() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            for (tilt in listOf(30, -40)) {
                val (left, right) = red(engine, tilt, true)
                assertEquals("tilt $tilt", 0, left)
                assertEquals("tilt $tilt", red(engine, tilt, false).second.toFloat(), right.toFloat(), 200f)
            }
        }
    }
}
