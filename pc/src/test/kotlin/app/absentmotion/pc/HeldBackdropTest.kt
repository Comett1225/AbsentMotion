package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class HeldBackdropTest {
    private fun scene(fps: Int) = Scene.parse(
        """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
        <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location><kf t="0" v="20,100,0"/><kf t="1" v="180,100,0"/></location></transform>
        <property name="size" type="vec2" value="40,200"/><fillColor value="#ffff0000"/></shape>
        <shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0"/></transform>
        <property name="size" type="vec2" value="200,200"/><fillColor value="#ffffffff"/>
        <effect id="absent.backdrop"/><effect id="absent.tile"/><effect id="absent.timequant"><property name="fps" type="float" value="$fps"/></effect></shape></scene>"""
    )

    private fun frames(fps: Int, times: List<Int>): List<List<Int>> {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene(fps), null)
            return times.map { t -> engine.render(t, 200, 200).let { image -> (0 until 200 step 4).map { x -> image.getRGB(x, 50) } } }
        }
    }

    @Test fun copyBackgroundWithFrameRateHoldsTheBackgroundForEachStep() {
        val shown = frames(5, listOf(0, 33, 67, 100, 133, 167, 200))
        for (i in 1..5) assertEquals("frame $i", shown[0], shown[i])
        assertNotEquals(shown[0], shown[6])
    }

    @Test fun aFrameRateAboveTheProjectRateKeepsTheBackgroundLive() {
        val shown = frames(60, listOf(0, 33))
        assertNotEquals(shown[0], shown[1])
    }
}
