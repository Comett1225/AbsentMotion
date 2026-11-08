package app.absentmotion.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class PointMarksBehindTheCameraTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    @Test fun aPointBehindTheCameraIsNotMarkedInAMirroredPlace() {
        val xml = """<scene width="1080" height="1920" fps="30"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color"><transform><location value="540,960,0"/><scale value="60,60"/><tilt value="70,0"/></transform>""" +
            """<property name="size" type="vec2" value="80,50"/><path d="M -20 -50 L 20 -50 L 0 100 Z"/></shape></scene>"""
        val m = PivotEngine.nativePlaces(xml, longArrayOf(1), doubleArrayOf(500.0))!!
        val marks = PivotEngine.nativePointMarks(xml, 1, 500.0)!!
        fun at(x: Float, y: Float): Triple<Float, Int, Int> {
            val w = m[6] * x + m[7] * y + m[8]
            return Triple(w, ((m[0] * x + m[1] * y + m[2]) / w).toInt(), ((m[3] * x + m[4] * y + m[5]) / w).toInt())
        }
        val (behind, bx, by) = at(0f, 100f)
        assertTrue(behind < 0 && bx in 0 until 1080 && by in 0 until 1920)
        assertEquals(0, marks[by * 1080 + bx].toInt())
        val (front, fx, fy) = at(-20f, -50f)
        assertTrue(front > 0 && fx in 0 until 1080 && fy in 0 until 1920)
        assertTrue(marks[fy * 1080 + fx].toInt() != 0)
    }
}
