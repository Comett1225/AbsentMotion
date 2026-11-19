package app.absentmotion.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.absentmotion.ui.editor.effects.COMPARE_MAX_ZOOM
import app.absentmotion.ui.editor.effects.CompareView
import app.absentmotion.ui.editor.effects.compareFractions
import app.absentmotion.ui.editor.effects.compareWheel
import app.absentmotion.ui.editor.effects.compareZoomLabel
import app.absentmotion.ui.editor.effects.moved
import app.absentmotion.ui.editor.effects.zoomed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceCompareTest {
    private val frame = Size(800f, 500f)

    private fun under(view: CompareView, at: Offset) = (at - view.pan) / view.zoom

    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    @Test fun dividerShowsOriginalOnTheLeftAndResultOnTheRight() {
        assertEquals(0f to 1f, compareFractions(0f))
        assertEquals(0.5f to 0.5f, compareFractions(0.5f))
        assertEquals(1f to 0f, compareFractions(1f))
        assertEquals(0f to 1f, compareFractions(-1f))
        assertEquals(1f to 0f, compareFractions(2f))
    }

    @Test fun zoomKeepsThePointUnderTheFocusStill() {
        val focus = Offset(600f, 150f)
        val once = CompareView().zoomed(2f, focus, frame)
        assertEquals(2f, once.zoom)
        assertNear(focus, under(once, focus))
        val other = Offset(300f, 320f)
        val seen = under(once, other)
        val twice = once.zoomed(1.5f, other, frame)
        assertEquals(3f, twice.zoom, 1e-4f)
        assertNear(seen, under(twice, other))
        val back = twice.zoomed(1f / 1.5f, other, frame)
        assertNear(seen, under(back, other))
    }

    @Test fun pinchMovesAndZoomsTogether() {
        val view = CompareView(2f, Offset(-300f, -200f))
        val from = Offset(380f, 240f)
        val step = Offset(25f, -10f)
        val seen = under(view, from)
        val next = view.zoomed(1.25f, from + step, frame, step)
        assertEquals(2.5f, next.zoom, 1e-4f)
        assertNear(seen, under(next, from + step))
    }

    @Test fun wheelingInAndBackOutLandsUnzoomed() {
        val focus = Offset(200f, 100f)
        val view = (1..2).fold(CompareView()) { v, _ -> v.zoomed(compareWheel(-1f), focus, frame) }
        assertEquals(CompareView(), (1..2).fold(view) { v, _ -> v.zoomed(compareWheel(1f), focus, frame) })
        assertTrue(CompareView().zoomed(1.0005f, focus, frame).zoom > 1f)
    }

    @Test fun zoomStaysBetweenOneAndEight() {
        assertEquals(CompareView(), CompareView().zoomed(0.5f, Offset(100f, 100f), frame))
        assertEquals(COMPARE_MAX_ZOOM, CompareView().zoomed(100f, Offset(100f, 100f), frame).zoom)
        assertEquals(COMPARE_MAX_ZOOM, CompareView(6f, Offset(-1000f, -1000f)).zoomed(2f, Offset(10f, 10f), frame).zoom)
        assertEquals(CompareView(), CompareView(4f, Offset(-900f, -600f)).zoomed(0.1f, Offset(400f, 250f), frame))
    }

    @Test fun panIsClampedSoNoEmptySpaceShows() {
        val corner = CompareView().zoomed(2f, Offset.Zero, frame)
        assertEquals(Offset.Zero, corner.pan)
        assertEquals(Offset.Zero, corner.moved(Offset(100f, 100f), frame).pan)
        assertEquals(Offset(-800f, -500f), corner.moved(Offset(-10_000f, -10_000f), frame).pan)
        val edge = CompareView().zoomed(2f, Offset(800f, 500f), frame)
        assertEquals(Offset(-800f, -500f), edge.pan)
        val out = CompareView(4f, Offset(-1200f, -750f)).zoomed(0.5f, Offset(0f, 0f), frame)
        assertTrue(out.pan.x >= frame.width * (1f - out.zoom) && out.pan.x <= 0f)
        assertTrue(out.pan.y >= frame.height * (1f - out.zoom) && out.pan.y <= 0f)
    }

    @Test fun atOneTimesNothingMoves() {
        assertEquals(CompareView(), CompareView().moved(Offset(50f, -30f), frame))
        assertEquals(CompareView(), CompareView().zoomed(1f, Offset(400f, 250f), frame, Offset(10f, 10f)))
    }

    @Test fun wheelUpZoomsInAndDownZoomsOut() {
        assertTrue(compareWheel(-1f) > 1f)
        assertTrue(compareWheel(1f) < 1f)
        assertEquals(1f, compareWheel(0f), 0f)
        assertEquals(2f, compareWheel(-100f), 0f)
        assertEquals(0.5f, compareWheel(100f), 0f)
    }

    @Test fun labelShowsOnlyWhileZoomedIn() {
        assertNull(compareZoomLabel(1f))
        assertNull(compareZoomLabel(1.04f))
        val label = compareZoomLabel(2.5f)!!
        assertTrue(label.endsWith("×") && label.startsWith("2") && label.contains("5"))
        assertTrue(compareZoomLabel(8f)!!.startsWith("8"))
    }
}
