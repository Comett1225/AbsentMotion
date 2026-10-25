package app.absentmotion.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StallAndProbeTest {
    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000

    @Test fun anExportThatKeepsMovingIsNeverStopped() = runBlocking {
        val result = watched(stallMs = 300) { alive ->
            repeat(20) { delay(50); alive() }
            "done"
        }
        assertEquals("done", result)
    }

    @Test fun anExportWithoutProgressStopsWithAClearErrorAndCleansUp() = runBlocking {
        val cleaned = AtomicBoolean()
        val start = System.nanoTime()
        try {
            watched(stallMs = 300) { alive ->
                alive()
                try { delay(60_000) } finally { cleaned.set(true) }
            }
            fail("a stalled export must not finish")
        } catch (e: ExportError) {
            assertTrue(encoderTrouble(e))
        }
        assertTrue(cleaned.get())
        assertTrue(elapsedMs(start) < 3_000)
    }

    @Test fun aStuckNativeCallStillEndsTheWaitAfterAShortGrace() = runBlocking {
        val release = CountDownLatch(1)
        val start = System.nanoTime()
        try {
            watched(stallMs = 200) { release.await(30, TimeUnit.SECONDS) }
            fail("a stuck export must not finish")
        } catch (e: ExportError) {
            assertTrue(encoderTrouble(e))
        } finally {
            release.countDown()
        }
        assertTrue(elapsedMs(start) < 9_000)
    }

    @Test fun errorsOfTheExportItselfPassThroughUnchanged() = runBlocking {
        try {
            watched(stallMs = 1_000) { throw IllegalArgumentException("broken frame") }
            fail()
        } catch (e: IllegalArgumentException) {
            assertEquals("broken frame", e.message)
            assertFalse(encoderTrouble(e))
        }
    }

    @Test fun cancellingWaitsForTheExportToCleanUp() = runBlocking {
        val cleaned = AtomicBoolean()
        val started = CountDownLatch(1)
        val job = async {
            watched(stallMs = 10_000) { alive ->
                alive()
                started.countDown()
                try { delay(60_000) } finally { Thread.sleep(100); cleaned.set(true) }
            }
        }
        started.await(5, TimeUnit.SECONDS)
        delay(50)
        job.cancel()
        try { job.await() } catch (e: CancellationException) { }
        assertTrue(cleaned.get())
    }

    @Test fun anEncoderListThatThrowsOrHangsGivesUpWithNoAnswer() = runBlocking {
        assertEquals(listOf(1), finishedWithin(1_000, "t") { listOf(1) })
        assertNull(finishedWithin(1_000, "t") { throw IllegalStateException("mediaserver died") })
        assertNull(finishedWithin(1_000, "t") { throw UnsatisfiedLinkError("no codec library") })
        val release = CountDownLatch(1)
        val start = System.nanoTime()
        assertNull(finishedWithin(300, "t") { release.await(30, TimeUnit.SECONDS) })
        release.countDown()
        assertTrue(elapsedMs(start) < 2_000)
    }

    @Test fun anAudioExportWhoseCountersStopMovingIsStoppedByTheWatchdog() = runBlocking {
        try {
            watched(stallMs = 300) { alive ->
                val beat = onChange { alive() }
                repeat(200) { beat(7); delay(20) }
            }
            fail("an audio export without progress must not finish")
        } catch (e: ExportError) {
            assertTrue(encoderTrouble(e))
        }
        assertEquals("done", watched(stallMs = 300) { alive ->
            val beat = onChange { alive() }
            repeat(40) { beat(it.toLong()); delay(20) }
            "done"
        })
    }

    @Test fun aFailedEncoderListIsTriedAgainAndAHungOneIsNotStartedTwice() = runBlocking {
        var calls = 0
        val flaky = Attempt(1_000, "t") { if (++calls == 1) throw IllegalStateException("mediaserver died") else listOf(calls) }
        assertNull(flaky.get())
        assertEquals(listOf(2), flaky.get())
        val release = CountDownLatch(1)
        var started = 0
        val hung = Attempt(200, "t") { started++; release.await(30, TimeUnit.SECONDS); "late" }
        assertNull(hung.get())
        assertNull(hung.get())
        assertEquals(1, started)
        release.countDown()
        assertEquals("late", hung.get())
    }

    @Test fun aProbeThatFailsOrHangsIsSkippedInsteadOfBlockingTheFirstStart() = runBlocking {
        assertEquals(5, probeWithin(1_000) { 5 })
        assertNull(probeWithin(1_000) { throw IllegalStateException("encoder crashed") })
        assertNull(probeWithin(1_000) { throw OutOfMemoryError("probe") })
        val start = System.nanoTime()
        assertNull(probeWithin(300) { delay(60_000); 1 })
        val release = CountDownLatch(1)
        assertNull(probeWithin(300) { release.await(30, TimeUnit.SECONDS) })
        release.countDown()
        assertTrue(elapsedMs(start) < 3_000)
    }
}
