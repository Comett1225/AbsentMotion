package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRowsTest {
    private val rowH = 28f
    private val top = 42f
    private val height = 280f
    private val bar = 48f
    private val panel = 80f

    private fun rowBottom(row: Int, scroll: Float) = top + (row + 1) * rowH - scroll

    @Test fun multiSelectBarShortensTheRowArea() {
        assertEquals(height, TimelineMath.rowsBottom(height, null, 0f), 0f)
        assertEquals(height - bar, TimelineMath.rowsBottom(height, null, bar), 0f)
        assertEquals(panel, TimelineMath.rowsBottom(height, panel, 0f), 0f)
        assertEquals(panel, TimelineMath.rowsBottom(height, panel, bar), 0f)
    }

    @Test fun lastRowScrollsClearOfTheMultiSelectBar() {
        val rows = 20
        val bottom = TimelineMath.rowsBottom(height, null, bar)
        val scroll = TimelineMath.maxScroll(rows, rowH, top, bottom)
        assertEquals(bottom, rowBottom(rows - 1, scroll), 0.001f)
        assertTrue(TimelineMath.maxScroll(rows, rowH, top, height) < scroll)
    }

    @Test fun nothingScrollsWhenTheRowsFit() {
        assertEquals(0f, TimelineMath.maxScroll(3, rowH, top, height - bar), 0f)
        assertEquals(0f, TimelineMath.maxScroll(0, rowH, top, height), 0f)
    }

    @Test fun visibleRowsStopAtTheUncoveredBottom() {
        assertEquals(0..5, TimelineMath.visibleRows(20, rowH, top, top + 6 * rowH, 0f))
        assertEquals(0..6, TimelineMath.visibleRows(20, rowH, top, top + 6 * rowH + 1f, 0f))
        assertEquals(0..1, TimelineMath.visibleRows(20, rowH, top, panel, 0f))
        assertEquals(0..7, TimelineMath.visibleRows(20, rowH, top, height - bar, 14f))
        assertEquals(1..7, TimelineMath.visibleRows(20, rowH, top, height - bar, 28f))
        assertEquals(17..19, TimelineMath.visibleRows(20, rowH, top, height, 500f))
        assertTrue(TimelineMath.visibleRows(0, rowH, top, height, 0f).isEmpty())
    }

    @Test fun visibleRowsCoverEveryRowThatShows() {
        val bottom = TimelineMath.rowsBottom(height, null, bar)
        for (scroll in listOf(0f, 3f, 27.5f, 28f, 100f, 300f)) {
            val shown = TimelineMath.visibleRows(20, rowH, top, bottom, scroll)
            for (row in 0 until 20) {
                val rowTop = top + row * rowH - scroll
                val shows = rowTop < bottom && rowTop + rowH > top
                assertEquals("scroll $scroll row $row", shows, row in shown)
            }
        }
    }

    private fun up(consumed: Boolean) = PointerInputChange(
        PointerId(0), 120, Offset(10f, 60f), false, 100, Offset(10f, 60f), true, consumed)

    @Test fun aCancelledPointerIsNotARelease() {
        assertTrue(TimelineMath.released(up(consumed = false)))
        assertFalse(TimelineMath.released(up(consumed = true)))
    }
}
