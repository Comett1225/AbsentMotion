package app.absentmotion.ui.editor

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class CellDropTest {
    private val window = IntSize(1080, 2400)
    private val list = IntSize(578, 788)

    @Test fun opensDownFromTheCellWhenThereIsRoom() {
        val drop = CellDrop()
        assertEquals(IntOffset(16, 300), drop.calculatePosition(IntRect(16, 300, 270, 400), window, LayoutDirection.Ltr, list))
        assertEquals(TransformOrigin(0f, 0f), drop.origin)
    }

    @Test fun risesFromTheCellNearTheBottomOfTheScreen() {
        val drop = CellDrop()
        assertEquals(IntOffset(16, 2150 - 788), drop.calculatePosition(IntRect(16, 2050, 270, 2150), window, LayoutDirection.Ltr, list))
        assertEquals(TransformOrigin(0f, 1f), drop.origin)
    }

    @Test fun rightToLeftGrowsFromTheCellsRightEdgeAndStaysOnScreen() {
        val drop = CellDrop()
        assertEquals(IntOffset(1064 - 578, 300), drop.calculatePosition(IntRect(810, 300, 1064, 400), window, LayoutDirection.Rtl, list))
        assertEquals(TransformOrigin(1f, 0f), drop.origin)
        assertEquals(0, drop.calculatePosition(IntRect(0, 300, 100, 400), window, LayoutDirection.Rtl, list).x)
    }

    @Test fun aListTallerThanTheRoomNeverLeavesTheTopOfTheWindow() {
        val drop = CellDrop()
        assertEquals(0, drop.calculatePosition(IntRect(16, 500, 270, 600), IntSize(1080, 900), LayoutDirection.Ltr, list).y)
        assertEquals(TransformOrigin(0f, 1f), drop.origin)
    }
}
