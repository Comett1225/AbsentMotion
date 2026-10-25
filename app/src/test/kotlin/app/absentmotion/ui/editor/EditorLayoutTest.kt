package app.absentmotion.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLayoutTest {
    @Test fun aTallWindowKeepsTheFullLowerArea() {
        assertEquals(280.dp, lowerPanelHeight(830.dp, 280.dp))
        assertEquals(512.dp, lowerPanelHeight(1200.dp, 512.dp))
    }

    @Test fun aShortWindowStillLeavesRoomForThePreview() {
        val lower = lowerPanelHeight(400.dp, 280.dp)
        assertEquals(192.dp, lower)
        assertEquals(120.dp, 400.dp - 88.dp - lower)
    }

    @Test fun aTinyWindowKeepsAUsableLowerArea() {
        assertEquals(140.dp, lowerPanelHeight(300.dp, 280.dp))
        assertEquals(140.dp, lowerPanelHeight(0.dp, 280.dp))
    }
}
