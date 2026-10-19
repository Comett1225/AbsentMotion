package app.absentmotion.export

import org.junit.Assert.*
import org.junit.Test

class AacTimingTest {
    @Test fun encoderReportedDelayTakesPrecedenceOverTheFallback() {
        assertEquals(1024, aacDelayFrames(1024))
        assertEquals(0, aacDelayFrames(0))
        assertEquals(2048, aacDelayFrames(null))
        assertEquals(2048, aacDelayFrames(-1))
    }

    @Test fun evenOneFrameOfAudioFeedsEnoughTailToFlushThePrimingDelay() {
        assertEquals(1411L + 2048, aacInputFrames(32000, 2048))
        assertEquals(4410L + 1024, aacInputFrames(100000, 1024))
        assertEquals(44100L + 2048, aacInputFrames(1000000, 2048))
    }
    @Test fun roundedPacketBoundariesKeepTheFirstRealSamples() {
        val time = AacTimestamps(2048)
        assertEquals(-46439L, time.map(0))
        assertEquals(-23220L, time.map(23219))
        assertEquals(0L, time.map(46438))
        assertEquals(23219L, time.map(69658))
        val aligned = AacTimestamps(1024)
        assertEquals(-23219L, aligned.map(-23219))
        assertEquals(0L, aligned.map(0))
        assertEquals(23219L, aligned.map(23219))
    }

}
