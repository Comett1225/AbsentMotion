package app.absentmotion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioClockTest {
    private val ms = 1_000_000L

    @Test fun aRevisedTimestampNeverMovesThePlayheadBack() {
        val clock = AudioClock(1000.0, 48_000)
        assertNull(clock.positionMs(5 * ms))
        clock.stamp(0, 10 * ms)
        assertEquals(1060.0, clock.positionMs(70 * ms)!!, 1e-6)
        clock.stamp(2400, 75 * ms)
        assertEquals(1060.0, clock.positionMs(76 * ms)!!, 1e-6)
        assertEquals(1080.0, clock.positionMs(105 * ms)!!, 1e-6)
    }

    @Test fun aStoppedClockStaysWhereItWasLastShown() {
        val clock = AudioClock(1000.0, 48_000)
        clock.stamp(0, 0)
        assertEquals(1100.0, clock.positionMs(100 * ms)!!, 1e-6)
        clock.stamp(2400, 95 * ms)
        clock.stop(96 * ms)
        assertEquals(1100.0, clock.positionMs(500 * ms)!!, 1e-6)
        assertEquals(1100.0, clock.positionMs(900 * ms)!!, 1e-6)
    }

    @Test fun switchingFromTheWallClockToTheAudioClockDoesNotStepBack() {
        val clock = AudioClock(0.0, 48_000)
        clock.wallStart = 100 * ms
        clock.failed = true
        assertEquals(1600.0, clock.positionMs(1700 * ms)!!, 1e-6)
        clock.failed = false
        clock.stamp(48_000 * 3 / 2, 1700 * ms)
        assertEquals(1600.0, clock.positionMs(1710 * ms)!!, 1e-6)
    }
}
