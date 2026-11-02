package app.absentmotion.ui.editor

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class ChoicePositionTest {
    @Test fun choicesFitAtEveryWindowEdgeInBothLayoutDirections() {
        val window = IntSize(300, 400)
        val menu = IntSize(220, 280)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) for (anchor in listOf(IntRect(290, 0, 300, 10), IntRect(0, 390, 10, 400))) {
            val at = ChoicePosition.calculatePosition(anchor, window, direction, menu)
            assertTrue(at.x in 0..80)
            assertTrue(at.y in 0..120)
        }
        assertEquals(IntOffset.Zero, ChoicePosition.calculatePosition(IntRect(10, 10, 20, 20), IntSize(100, 100), LayoutDirection.Ltr, menu))
    }
}
