package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PivotHandleTest {
    private val box = listOf(Offset(0f, 0f), Offset(200f, 0f), Offset(200f, 100f), Offset(0f, 100f))
    private val one = floatArrayOf(1f, 1f)

    @Test fun aCornerScalesAboutThePivotAndStaysUnderTheFinger() {
        val scaled = handleScale(2, box, Offset(0f, 0f), Offset(300f, 150f), one, 8f)!!
        assertEquals(1.5f, scaled[0], 1e-4f)
        assertEquals(1.5f, scaled[1], 1e-4f)
        val centred = handleScale(2, box, Offset(100f, 50f), Offset(250f, 125f), one, 8f)!!
        assertEquals(1.5f, centred[0], 1e-4f)
    }

    @Test fun anEdgeScalesOneSideAboutThePivot() {
        val scaled = handleScale(5, box, Offset(0f, 0f), Offset(250f, 60f), floatArrayOf(2f, 3f), 8f)!!
        assertEquals(2.5f, scaled[0], 1e-4f)
        assertEquals(3f, scaled[1], 1e-4f)
        val tall = handleScale(6, box, Offset(0f, 0f), Offset(90f, 150f), one, 8f)!!
        assertEquals(1f, tall[0], 1e-4f)
        assertEquals(1.5f, tall[1], 1e-4f)
    }

    @Test fun grabbingNearAHandleStartsAtScaleOneAndFollowsTheFingerTravel() {
        val pivot = Offset(0f, 0f)
        val down = Offset(228f, 124f)
        val grab = handleGrab(2, box, down)
        assertEquals(Offset(-28f, -24f), grab)
        assertEquals(1f, handleScale(2, box, pivot, down + grab, one, 8f)!![0], 1e-6f)
        assertEquals(1.5f, handleScale(2, box, pivot, down + Offset(100f, 50f) + grab, one, 8f)!![0], 1e-4f)
        assertTrue(handleScale(2, box, pivot, down, one, 8f)!![0] > 1.1f)
        val edgeDown = Offset(215f, 70f)
        val edge = handleGrab(5, box, edgeDown)
        assertEquals(2f, handleScale(5, box, pivot, edgeDown + edge, floatArrayOf(2f, 3f), 8f)!![0], 1e-5f)
        assertEquals(2.5f, handleScale(5, box, pivot, edgeDown + Offset(50f, 30f) + edge, floatArrayOf(2f, 3f), 8f)!![0], 1e-4f)
    }

    @Test fun aHandleOnThePivotCannotScale() {
        assertNull(handleScale(0, box, Offset(0f, 0f), Offset(-50f, -50f), one, 8f))
        assertNull(handleScale(7, box, Offset(0f, 30f), Offset(-50f, 30f), one, 8f))
    }
}
