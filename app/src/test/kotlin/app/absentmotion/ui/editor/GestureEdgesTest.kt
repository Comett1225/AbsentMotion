package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEdgesTest {
    private fun fullWidth(width: Float, top: Float, bottom: Float): List<Offset> {
        val mid = (top + bottom) / 2f
        return listOf(Offset(0f, top), Offset(width, top), Offset(width, bottom), Offset(0f, bottom), Offset(width / 2f, top), Offset(width, mid), Offset(width / 2f, bottom), Offset(0f, mid))
    }

    @Test fun handlesAtTheScreenEdgesGetABandEach() {
        val bands = GestureEdges.bands(fullWidth(1080f, 300f, 1380f), 1080f, 1f)
        assertEquals(6, bands.size)
        assertTrue(bands.filter { it.left == 0f }.all { it.right == GestureEdges.ZONE_DP })
        assertTrue(bands.filter { it.right == 1080f }.all { it.width == GestureEdges.ZONE_DP })
    }

    @Test fun handlesAwayFromTheEdgesNeedNoBand() {
        assertTrue(GestureEdges.bands(fullWidth(600f, 300f, 900f).map { it + Offset(200f, 0f) }, 1080f, 1f).isEmpty())
    }

    @Test fun aHandleJustOutsideTheZoneGetsNoBand() {
        assertTrue(GestureEdges.bands(listOf(Offset(GestureEdges.ZONE_DP + 1f, 500f), Offset(1080f - GestureEdges.ZONE_DP - 1f, 500f)), 1080f, 1f).isEmpty())
    }

    @Test fun handlesFarOffscreenAreIgnored() {
        assertTrue(GestureEdges.bands(listOf(Offset(-200f, 500f), Offset(1400f, 500f)), 1080f, 1f).isEmpty())
    }

    @Test fun closeHandlesShareOneBand() {
        val bands = GestureEdges.bands(listOf(Offset(0f, 500f), Offset(0f, 540f)), 1080f, 1f)
        assertEquals(1, bands.size)
        assertEquals(500f - GestureEdges.HALF_DP, bands[0].top, 0f)
        assertEquals(540f + GestureEdges.HALF_DP, bands[0].bottom, 0f)
    }

    @Test fun bandsScaleWithDensityAndStayUnderTheSystemLimitPerEdge() {
        val handles = List(8) { Offset(0f, 100f + it * 200f) }
        val bands = GestureEdges.bands(handles, 1080f, 2f)
        assertTrue(bands.sumOf { it.height.toDouble() } <= GestureEdges.LIMIT_DP * 2f)
        assertEquals(GestureEdges.ZONE_DP * 2f, bands[0].width, 0f)
    }
}
