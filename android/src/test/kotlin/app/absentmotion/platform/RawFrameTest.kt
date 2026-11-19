package app.absentmotion.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RawFrameTest {
    @Test fun opaqueFramesAreUsedAsTheyAre() {
        val rgba = byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1)
        assertSame(rgba, premultiplied(rgba))
    }

    @Test fun seeThroughPixelsAreScaledByTheirAlpha() {
        val rgba = byteArrayOf(10, 20, 30, -1, -1, 0, 0, -128, -1, -1, -1, 0)
        assertArrayEquals(byteArrayOf(10, 20, 30, -1, -128, 0, 0, -128, 0, 0, 0, 0), premultiplied(rgba))
        assertArrayEquals(byteArrayOf(10, 20, 30, -1, -1, 0, 0, -128, -1, -1, -1, 0), rgba)
    }
}
