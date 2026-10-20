package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.panels.sidesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeTabTest {
    @Test fun theSizeIsTheLayersOwnSidesWhateverItsTurn() {
        val flat = sidesOf(floatArrayOf(0f, 0f, 200f, 0f, 200f, 100f, 0f, 100f))
        assertEquals(200f, flat.x, 1e-3f)
        assertEquals(100f, flat.y, 1e-3f)
        assertFalse(flat.upright)
        val quarter = sidesOf(floatArrayOf(0f, 0f, 0f, 200f, -100f, 200f, -100f, 0f))
        assertEquals(200f, quarter.x, 1e-3f)
        assertEquals(100f, quarter.y, 1e-3f)
        assertTrue(quarter.upright)
        val r = 50f * Math.sqrt(2.0).toFloat()
        val diamond = sidesOf(floatArrayOf(0f, 0f, r, r, 0f, 2 * r, -r, r))
        assertEquals(100f, diamond.x, 1e-3f)
        assertEquals(100f, diamond.y, 1e-3f)
        assertFalse(diamond.upright)
    }
}
