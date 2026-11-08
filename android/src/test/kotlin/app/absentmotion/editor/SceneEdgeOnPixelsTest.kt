package app.absentmotion.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class SceneEdgeOnPixelsTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private val xml = """<scene width="200" height="100" fps="30"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color" fillColor="#ffff0000"><transform>""" +
        """<location value="100,50,0"/></transform><property name="size" type="vec2" value="400,200"/></shape></scene>"""

    @Test fun theSceneEdgeLandsOnWholePixelsWhateverTheViewScaleAndOffset() {
        for ((scale, dx, dy) in listOf(Triple(0.468148f, 30.7f, 0f), Triple(1f, 10.5f, 3.25f), Triple(0.333f, 7.9f, 11.1f), Triple(2.37f, 0.4f, 0.6f))) {
            val width = 700
            val height = 400
            val alpha = PivotEngine.nativeDrawnPreview(xml, 500.0, width, height, scale, dx, dy)!!
            val partial = alpha.count { it.toInt() and 0xff in 1..254 }
            assertEquals("scale $scale offset $dx,$dy leaves soft edge pixels", 0, partial)
            val covered = alpha.count { it.toInt() and 0xff == 255 }
            val wide = Math.round(dx + 200 * scale) - Math.round(dx)
            val tall = Math.round(dy + 100 * scale) - Math.round(dy)
            assertTrue("scale $scale offset $dx,$dy covers $covered pixels, expected about ${wide * tall}", Math.abs(covered - wide * tall) <= wide + tall)
        }
    }
}
