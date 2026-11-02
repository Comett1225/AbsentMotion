package app.absentmotion.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RunCancellableTest {
    @Test fun aCancelThatLandsBeforeTheWorkStartsIsStillSeenByTheWork() {
        val flag = AtomicBoolean(false)
        var seen: Boolean? = null
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext.job.cancel()
                runCancellable({ flag.set(false) }, { flag.set(true) }) { seen = flag.get() }
            }.join()
        }
        assertEquals(true, seen)
    }

    @Test fun aFinishedRunDoesNotCancelTheNextRunOnTheSameModel() {
        val flag = AtomicBoolean(false)
        val seen = mutableListOf<Boolean>()
        runBlocking { repeat(3) { runCancellable({ flag.set(false) }, { flag.set(true) }) { seen += flag.get() } } }
        assertEquals(listOf(false, false, false), seen)
        assertTrue(flag.get())
    }

    @Test fun aCancelDuringTheWorkReachesTheWork() {
        val flag = AtomicBoolean(false)
        var stopped = false
        runBlocking {
            withTimeout(10_000) {
                val job = launch(Dispatchers.Default) {
                    runCancellable({ flag.set(false) }, { flag.set(true) }) {
                        while (!flag.get()) Thread.sleep(1)
                        stopped = true
                    }
                }
                delay(50)
                job.cancel()
                job.join()
            }
        }
        assertTrue(stopped)
    }
}
