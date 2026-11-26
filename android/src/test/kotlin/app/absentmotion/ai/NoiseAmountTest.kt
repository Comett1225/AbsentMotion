package app.absentmotion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseAmountTest {
    @Test fun amountSpreadsTheNoiseLevelEvenlyInDecibels() {
        assertEquals(1.0, wetShare(1.0, 1.0), 1e-12)
        assertEquals(0.0, wetShare(1.0, 0.0), 1e-12)
        assertEquals(0.5, wetShare(0.5, 1.0), 1e-12)
        assertEquals(1 - 0.09 / 0.99, wetShare(1.0, 0.5), 1e-12)
        assertEquals(1.0, wetShare(1.0, 3.0), 1e-12)
    }
}
