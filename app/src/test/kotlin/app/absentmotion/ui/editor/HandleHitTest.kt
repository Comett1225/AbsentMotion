package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class HandleHitTest {
    private fun square(side: Float) = listOf(Offset(0f, 0f), Offset(side, 0f), Offset(side, side), Offset(0f, side))
    private fun handles(side: Float) = square(side).let { c -> c + List(4) { (c[it] + c[(it + 1) % 4]) / 2f } }

    @Test fun aTouchTwentyFivePixelsAwayNowCatchesTheCorner() {
        val reach = HandleHit.reach(square(400f), 1f)
        assertEquals(30f, reach, 0f)
        assertEquals(0, HandleHit.at(handles(400f), Offset(-25f, 0f), reach))
    }

    @Test fun aTouchBeyondTheReachMissesAndMovesTheLayer() {
        assertEquals(-1, HandleHit.at(handles(400f), Offset(-35f, 0f), HandleHit.reach(square(400f), 1f)))
    }

    @Test fun theNearestHandleWinsWhereZonesOverlap() {
        val side = 100f
        val reach = HandleHit.reach(square(side), 1f)
        assertEquals(4, HandleHit.at(handles(side), Offset(30f, 0f), reach))
        assertEquals(0, HandleHit.at(handles(side), Offset(10f, 0f), reach))
    }

    @Test fun smallLayersKeepTheOldReachSoTheyCanStillBeMoved() {
        assertEquals(20f, HandleHit.reach(square(40f), 1f), 0f)
        assertEquals(-1, HandleHit.at(handles(40f), Offset(20f, 20f), HandleHit.reach(square(40f), 1f)))
    }

    @Test fun reachScalesWithScreenDensity() {
        assertEquals(90f, HandleHit.reach(square(900f), 3f), 0f)
    }
}
