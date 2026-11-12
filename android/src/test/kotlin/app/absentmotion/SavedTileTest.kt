package app.absentmotion

import app.absentmotion.ui.home.Marquee
import app.absentmotion.ui.home.tileFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTileTest {
    private fun at(ms: Long, overflow: Float = 100f) = Marquee.frame(ms, overflow, 50f).let { it.offset to it.alpha }

    @Test
    fun shortTextStaysStill() {
        for (ms in listOf(0L, 1500L, 99999L)) assertEquals(0f to 1f, at(ms, 0f))
    }

    @Test
    fun waitsThenScrollsToTheEndThenFadesAndRepeats() {
        assertEquals(2000L, Marquee.travelMs(100f, 50f))
        assertEquals(0f to 1f, at(0))
        assertEquals(0f to 1f, at(999))
        assertEquals(50f to 1f, at(2000))
        assertEquals(100f to 1f, at(3000))
        assertEquals(100f to 1f, at(3799))
        assertEquals(100f to 0.5f, at(3950))
        assertEquals(0f to 0f, at(4100))
        assertEquals(0f to 0.5f, at(4250))
        assertEquals(0f to 1f, at(4400))
        assertEquals(50f to 1f, at(4400 + 2000))
    }

    @Test
    fun neverLeavesItsRange() {
        var last = 0f
        for (ms in 0L..3000L step 16) {
            val (offset, alpha) = at(ms)
            assertTrue(offset in 0f..100f && alpha == 1f && offset >= last)
            last = offset
        }
    }

    @Test
    fun framesMatchTheBuiltInTilesAndAlwaysFit() {
        fun rounded(w: Int, h: Int) = tileFrame(w, h).let { (fw, fh) -> Math.round(fw) to Math.round(fh) }
        assertEquals(55 to 31, rounded(1920, 1080))
        assertEquals(31 to 55, rounded(1080, 1920))
        assertEquals(31 to 31, rounded(500, 500))
        assertEquals(31 to 39, rounded(1080, 1350))
        assertEquals(41 to 31, rounded(1440, 1080))
        for ((w, h) in listOf(4096 to 32, 32 to 4096, 3000 to 1000, 1000 to 3000, 33 to 32)) {
            val (fw, fh) = tileFrame(w, h)
            assertTrue(fw in 5f..55f && fh in 5f..55f)
            assertEquals(w >= h, fw >= fh)
        }
    }
}
