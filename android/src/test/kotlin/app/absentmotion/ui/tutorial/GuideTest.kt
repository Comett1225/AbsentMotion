package app.absentmotion.ui.tutorial

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {
    private val limit = 140f
    private val add = Mark("add", Area(900f, 2000f, 973f, 2073f))
    private val undo = Mark("undo", Area(10f, 10f, 90f, 90f))
    private val timeline = Mark("timeline", Area(0f, 1500f, 1080f, 2200f))

    @Test fun theAddButtonGetsOnlyTheRedPulseAndThePointer() {
        val marks = listOf(add)
        val tap = Guide.tapTarget(marks, limit)
        assertNotNull(tap)
        assertFalse(Guide.rings(tap))
        assertFalse(Guide.accented(marks, 0))
        assertEquals(Offset(936.5f, 2036.5f), Guide.pointerAt(tap, true))
    }

    @Test fun otherSmallControlsAndAreasKeepTheirOriginalLook() {
        val small = listOf(undo)
        val tap = Guide.tapTarget(small, limit)
        assertTrue(Guide.rings(tap))
        assertTrue(Guide.accented(small, 0))
        val wide = listOf(timeline)
        assertNull(Guide.tapTarget(wide, limit))
        assertFalse(Guide.rings(null))
        assertTrue(Guide.accented(wide, 0))
        assertNull(Guide.pointerAt(null, true))
    }

    @Test fun onlyTheFirstMarkCanBeTheTapTargetAndOnlyTheFirstIsAccented() {
        val marks = listOf(timeline, undo)
        assertNull(Guide.tapTarget(marks, limit))
        assertTrue(Guide.accented(marks, 0))
        assertFalse(Guide.accented(marks, 1))
        assertFalse(Guide.accented(listOf(add, undo), 0))
        assertEquals(undo, Guide.tapTarget(listOf(undo, add), limit))
    }

    @Test fun thePointerWaitsForTheFrameAndFollowsItsOffset() {
        assertNull(Guide.pointerAt(add, false))
        val frame = Area(0f, 63f, 1080f, 2400f)
        assertEquals(Offset(936.5f, 1973.5f), Guide.inFrame(Offset(936.5f, 2036.5f), frame))
        assertEquals(Offset(5f, 7f), Guide.inFrame(Offset(5f, 7f), null))
    }

    @Test fun aFirstAppearanceSnapsInPlaceThenFadesIn() {
        assertEquals(Flight.Appear, Pointer.flight(0f, Offset.Zero, Offset(300f, 400f), false))
        assertEquals(Flight.Appear, Pointer.flight(0.02f, Offset(10f, 10f), Offset(300f, 400f), false))
        assertEquals(Flight.Appear, Pointer.flight(0f, Offset.Zero, Offset(300f, 400f), true))
    }

    @Test fun aMovingTargetGlidesAndASettledOneStays() {
        val at = Offset(300f, 400f)
        assertEquals(Flight.Glide, Pointer.flight(1f, at, Offset(500f, 900f), false))
        assertEquals(Flight.Glide, Pointer.flight(0.4f, at, Offset(500f, 900f), false))
        assertEquals(Flight.Stay, Pointer.flight(1f, at, Offset(300.2f, 400.1f), false))
        assertEquals(Flight.Stay, Pointer.flight(1f, at, at, false))
    }

    @Test fun reducedMotionNeverGlides() {
        val at = Offset(300f, 400f)
        assertEquals(Flight.Snap, Pointer.flight(1f, at, Offset(500f, 900f), true))
        assertEquals(Flight.Stay, Pointer.flight(1f, at, at, true))
        assertEquals(Flight.Leave, Pointer.flight(1f, at, null, true))
    }

    @Test fun aLostTargetFadesOutOnceAndThenStaysHidden() {
        val at = Offset(300f, 400f)
        assertEquals(Flight.Leave, Pointer.flight(1f, at, null, false))
        assertEquals(Flight.Leave, Pointer.flight(0.3f, at, null, false))
        assertEquals(Flight.Stay, Pointer.flight(0f, at, null, false))
    }

    @Test fun aTargetThatComesBackWhileFadingGlidesFromWhereThePointerIs() {
        val at = Offset(300f, 400f)
        assertEquals(Flight.Glide, Pointer.flight(0.5f, at, Offset(320f, 700f), false))
        assertEquals(Flight.Appear, Pointer.flight(0.01f, at, Offset(320f, 700f), false))
    }

    @Test fun boundsThatDriftBySubPixelsKeepOneKey() {
        assertEquals(Pointer.key(Offset(100.2f, 200.4f)), Pointer.key(Offset(99.8f, 199.6f)))
        assertEquals(Offset(101f, 200f), Pointer.key(Offset(100.6f, 200.4f)))
        assertNull(Pointer.key(null))
    }
}
