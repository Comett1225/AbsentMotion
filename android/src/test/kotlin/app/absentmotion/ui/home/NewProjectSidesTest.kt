package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class NewProjectSidesTest {
    @Test fun linkedSidesKeepTheirShapeWhenATypedSideIsOutOfRange() {
        assertEquals(1920 to 1080, linkedSides(1920f, 1080f))
        assertEquals(4096 to 2304, linkedSides(7680f, 4320f))
        assertEquals(2304 to 4096, linkedSides(4320f, 7680f))
        assertEquals(57 to 32, linkedSides(10f, 5.625f))
        assertEquals(4096 to 32, linkedSides(8000f, 20f))
        assertEquals(32 to 32, linkedSides(0f, 0f))
    }
}
