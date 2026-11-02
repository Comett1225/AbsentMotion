package app.absentmotion.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoColorTest {
    @Test fun extendedStandardsUseTheirMatrixRatherThanPrimaries() {
        assertEquals(1, videoColorStandard(75))
        assertEquals(2, videoColorStandard(86))
        assertEquals(6, videoColorStandard(105))
        assertEquals(1, videoColorStandard(0x10000 + 256 + 4))
    }

    @Test fun publicStandardsAndUnknownMatricesRemainPredictable() {
        for (standard in listOf(0, 1, 2, 4, 6)) assertEquals(standard, videoColorStandard(standard))
        assertEquals(1, videoColorStandard(3))
        assertEquals(2, videoColorStandard(10))
        assertEquals(0, videoColorStandard(64))
    }

    @Test fun onlyPqAndHlgCountAsHdr() {
        assertEquals(true, isHdrTransfer(6))
        assertEquals(true, isHdrTransfer(7))
        assertEquals(false, isHdrTransfer(3))
        assertEquals(false, isHdrTransfer(0))
    }
}
