package app.absentmotion.editor

import app.absentmotion.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class PointsBehindTheCameraTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    @Test fun aLayerPointBehindTheCameraHasNoPlaceOnScreenAndAPointInFrontKeepsItsPlace() {
        val xml = """<scene width="1080" height="1920" fps="30"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color"><transform><location value="540,960,0"/><scale value="60,60"/><tilt value="70,0"/></transform>""" +
            """<property name="size" type="vec2" value="80,50"/></shape></scene>"""
        val world = PivotEngine.nativePlaces(xml, longArrayOf(1), doubleArrayOf(500.0))!!.copyOfRange(0, 9)
        assertNull(world.projected(Pt(0f, 100f)))
        val front = world.projected(Pt(-80f, -50f))
        assertNotNull(front)
        assertEquals(-1240.8f, front!!.x, 0.5f)
        assertEquals(579.3f, front.y, 0.5f)
    }
}
