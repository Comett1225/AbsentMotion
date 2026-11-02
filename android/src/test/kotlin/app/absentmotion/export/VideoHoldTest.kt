package app.absentmotion.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoHoldTest {
    private val holds = AtomicInteger()
    private val hold = { holds.incrementAndGet(); CompletableDeferred(Unit) as Job }
    private val release = { holds.decrementAndGet(); CompletableDeferred(Unit) as Job }

    @Test
    fun releasesAfterTheBlock() = runBlocking {
        val result = holdingVideo(hold, release) { assertEquals(1, holds.get()); 7 }
        assertEquals(7, result)
        assertEquals(0, holds.get())
    }

    @Test
    fun releasesWhenTheBlockFails() = runBlocking {
        try {
            holdingVideo(hold, release) { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, holds.get())
    }

    @Test
    fun releasesWhenCancelledWhileTheHoldSettles() = runBlocking {
        val settling = CompletableDeferred<Unit>()
        var ran = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            holdingVideo({ holds.incrementAndGet(); settling }, release) { ran = true }
        }
        assertEquals(1, holds.get())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, holds.get())
        assertTrue(!ran)
    }

    @Test
    fun noVideoEngineMeansNothingToHold() = runBlocking {
        assertEquals(3, holdingVideo({ null }, { null }) { 3 })
    }
}
