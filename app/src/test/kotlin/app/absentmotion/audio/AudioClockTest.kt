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

    @Test fun aKnownOutputLatencyHoldsThePictureUntilTheSoundIsHeard() {
        val clock = AudioClock(2000.0, 48_000)
        clock.latencyNs = 80 * ms
        clock.wallStart = 100 * ms
        assertEquals(2000.0, clock.positionMs(150 * ms)!!, 1e-6)
        assertEquals(2000.0, clock.positionMs(180 * ms)!!, 1e-6)
        assertEquals(2050.0, clock.positionMs(230 * ms)!!, 1e-6)
        clock.stamp(48 * 60, 240 * ms)
        assertEquals(2080.0, clock.positionMs(260 * ms)!!, 1e-6)
    }

    @Test fun withoutAnEstimateThePictureWaitsForTheFirstTimestamp() {
        val clock = AudioClock(0.0, 48_000)
        clock.wallStart = 100 * ms
        assertNull(clock.positionMs(400 * ms))
        clock.stamp(48 * 50, 300 * ms)
        assertEquals(150.0, clock.positionMs(400 * ms)!!, 1e-6)
    }

    @Test fun theFirstPresentedFrameMeasuresTheStartLatency() {
        assertEquals(100 * ms, AudioClock.startLatency(4800, 300 * ms, 100 * ms, 48_000))
        assertEquals(0L, AudioClock.startLatency(48_000, 300 * ms, 100 * ms, 48_000))
        assertEquals(AudioClock.MAX_LATENCY_NS, AudioClock.startLatency(0, 5000 * ms, 100 * ms, 48_000))
        val before = HashMap(AudioClock.learned)
        try {
            AudioClock.learned.clear()
            val clock = AudioClock(0.0, 44_100)
            clock.route = 7
            clock.wallStart = 1000 * ms
            clock.stamp(0, 1010 * ms)
            assertEquals(null, AudioClock.learned[7])
            clock.stamp(4410, 1170 * ms)
            assertEquals(70 * ms, AudioClock.learned[7])
            clock.stamp(8820, 1290 * ms)
            assertEquals(70 * ms, AudioClock.learned[7])
            assertEquals(null, AudioClock.learned[8])
        } finally {
            AudioClock.learned.clear()
            AudioClock.learned.putAll(before)
        }
    }

    @Test fun anEarlyEstimateNeverMakesThePictureStepBack() {
        val clock = AudioClock(0.0, 48_000)
        clock.latencyNs = 0
        clock.wallStart = 100 * ms
        assertEquals(100.0, clock.positionMs(200 * ms)!!, 1e-6)
        clock.stamp(48 * 50, 200 * ms)
        assertEquals(100.0, clock.positionMs(210 * ms)!!, 1e-6)
        assertEquals(110.0, clock.positionMs(260 * ms)!!, 1e-6)
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
