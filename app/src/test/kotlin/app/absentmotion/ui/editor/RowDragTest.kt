package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowDragTest {
    private val rowH = 28f
    private val top = 42f
    private val bottom = 280f
    private val zone = 56f
    private val slowest = 28f
    private val fastest = 336f

    private fun speed(y: Float, viewBottom: Float = bottom) = RowDrag.edgeSpeed(y, top, viewBottom, zone, slowest, fastest)

    private fun rowTop(row: Int, scroll: Float) = top + row * rowH - scroll

    @Test fun edgeSpeedIsZeroOutsideTheZones() {
        for (y in listOf(top + zone, 150f, 200f, bottom - zone)) assertEquals("y=$y", 0f, speed(y), 0f)
    }

    @Test fun edgeSpeedPointsTowardTheNearEdge() {
        assertTrue(speed(top + 10f) < 0f)
        assertTrue(speed(bottom - 10f) > 0f)
    }

    @Test fun edgeSpeedRisesMonotonicallyWithDepthAndIsCapped() {
        var last = 0f
        for (i in 1..80) {
            val s = speed(bottom - zone + i)
            assertTrue("depth $i: $s < $last", s >= last)
            assertTrue(s <= fastest)
            last = s
        }
        last = 0f
        for (i in 1..80) {
            val s = -speed(top + zone - i)
            assertTrue("depth $i: $s < $last", s >= last)
            assertTrue(s <= fastest)
            last = s
        }
        assertEquals(fastest, speed(bottom), 0.001f)
        assertEquals(fastest, speed(bottom + 300f), 0.001f)
        assertEquals(-fastest, speed(top), 0.001f)
        assertEquals(-fastest, speed(0f), 0.001f)
    }

    @Test fun edgeSpeedStartsSlowJustInsideTheZone() {
        assertEquals(slowest, speed(bottom - zone + 0.01f), 1f)
        assertEquals(-slowest, speed(top + zone - 0.01f), 1f)
        assertTrue(speed(bottom - zone / 2) < (slowest + fastest) / 2)
    }

    @Test fun zonesShrinkToAThirdOfASmallArea() {
        val small = top + 38f
        assertEquals(0f, speed(top + 19f, small), 0f)
        assertTrue(speed(top + 12f, small) < 0f)
        assertTrue(speed(small - 12f, small) > 0f)
        assertEquals(0f, RowDrag.edgeSpeed(top, top, top, zone, slowest, fastest), 0f)
    }

    @Test fun slotFollowsTheLiftedRowCentre() {
        for (scroll in listOf(0f, 13f, 300f, 602f)) {
            for (row in 0 until 30) {
                val y = rowTop(row, scroll)
                assertEquals(row, RowDrag.slot(y, rowH, top, scroll, 30))
                assertEquals(row, RowDrag.slot(y + rowH / 2 - 0.01f, rowH, top, scroll, 30))
                assertEquals(row, RowDrag.slot(y - rowH / 2, rowH, top, scroll, 30))
                if (row < 29) assertEquals(row + 1, RowDrag.slot(y + rowH / 2, rowH, top, scroll, 30))
            }
        }
    }

    @Test fun slotStaysOnTheList() {
        assertEquals(0, RowDrag.slot(-500f, rowH, top, 0f, 30))
        assertEquals(29, RowDrag.slot(5000f, rowH, top, 602f, 30))
        assertEquals(0, RowDrag.slot(200f, rowH, top, 0f, 1))
        assertEquals(0, RowDrag.slot(200f, rowH, top, 0f, 0))
        assertEquals(2, RowDrag.slot(bottom - rowH, rowH, top, 0f, 3))
    }

    @Test fun liftedRowCentreStaysInTheVisibleRowsAndOnTheList() {
        assertEquals(top, RowDrag.clampTop(0f, rowH, top, bottom, 0f, 30), 0f)
        assertEquals(bottom - rowH / 2, RowDrag.clampTop(900f, rowH, top, bottom, 0f, 30), 0f)
        assertEquals(bottom - rowH, RowDrag.clampTop(900f, rowH, top, bottom, 602f, 30), 0f)
        assertEquals(top - rowH / 2, RowDrag.clampTop(0f, rowH, top, bottom, 602f, 30), 0f)
        assertEquals(123f, RowDrag.clampTop(123f, rowH, top, bottom, 300f, 30), 0f)
        assertEquals(rowTop(3, 90f), RowDrag.clampTop(rowTop(3, 90f), rowH, top, bottom, 90f, 30), 0f)
        for (scroll in listOf(0f, 13f, 300f, 602f)) for (y in -400..800 step 7) {
            val clamped = RowDrag.clampTop(y.toFloat(), rowH, top, bottom, scroll, 30)
            assertTrue(clamped + rowH / 2 in top..bottom)
            val slot = RowDrag.slot(clamped, rowH, top, scroll, 30)
            assertTrue(rowTop(slot, scroll) + rowH > top && rowTop(slot, scroll) < bottom)
        }
    }

    @Test fun fewRowsKeepTheLiftedRowOnTheList() {
        assertEquals(top + 2 * rowH, RowDrag.clampTop(250f, rowH, top, bottom, 0f, 3), 0f)
        assertEquals(top, RowDrag.clampTop(250f, rowH, top, bottom, 0f, 1), 0f)
        assertEquals(top, RowDrag.clampTop(-20f, rowH, top, bottom, 0f, 1), 0f)
        assertEquals(top + 20f - rowH / 2, RowDrag.clampTop(100f, rowH, top, top + 20f, 0f, 30), 0f)
    }

    @Test fun otherRowsShiftByOneTowardTheGap() {
        assertEquals(listOf(0, 0, -1, -1, -1, 0), (0..5).map { RowDrag.shift(it, 1, 4) })
        assertEquals(listOf(0, 1, 1, 1, 0, 0), (0..5).map { RowDrag.shift(it, 4, 1) })
        assertEquals(listOf(0, 0, 0), (0..2).map { RowDrag.shift(it, 1, 1) })
    }

    @Test fun shiftMatchesTheModelMove() {
        val before = (0 until 30).toList()
        for (from in 0 until 30) for (to in 0 until 30) {
            val after = before.toMutableList().apply { add(to, removeAt(from)) }
            for (row in before.indices) if (row != from) assertEquals(after.indexOf(row), row + RowDrag.shift(row, from, to))
        }
    }
}
