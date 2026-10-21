package app.absentmotion.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SaveQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor()

    @After fun stop() { executor.shutdownNow() }

    private fun drain() = executor.submit { }.get(5, TimeUnit.SECONDS)

    @Test fun manyQuickEditsBecomeOneWriteOfTheLatest() {
        val written = Collections.synchronizedList(ArrayList<String>())
        val wrote = CountDownLatch(1)
        val queue = SaveQueue(executor, 50, { written += it; wrote.countDown() })
        val release = CountDownLatch(1)
        executor.submit { release.await() }
        repeat(20) { queue.submit("v$it") }
        assertTrue(written.isEmpty())
        release.countDown()
        assertTrue(wrote.await(5, TimeUnit.SECONDS))
        drain()
        assertEquals(listOf("v19"), written)
    }

    @Test fun flushWritesAtOnceAndOnlyOnce() {
        val written = Collections.synchronizedList(ArrayList<String>())
        val queue = SaveQueue(executor, 60_000, { written += it })
        queue.submit("a")
        queue.submit("b")
        queue.flush().get(5, TimeUnit.SECONDS)
        assertEquals(listOf("b"), written)
        queue.flush().get(5, TimeUnit.SECONDS)
        assertEquals(listOf("b"), written)
    }

    @Test fun failureIsReportedOnceAndTheLatestTextIsRetried() {
        var broken = true
        val written = ArrayList<String>()
        var reports = 0
        val queue = SaveQueue(executor, 60_000, { if (broken) throw IOException("disk full") else written += it }, { reports++ })
        queue.submit("a")
        queue.flush().get(5, TimeUnit.SECONDS)
        queue.flush().get(5, TimeUnit.SECONDS)
        queue.submit("b")
        queue.flush().get(5, TimeUnit.SECONDS)
        assertEquals(1, reports)
        assertTrue(queue.failing)
        broken = false
        queue.flush().get(5, TimeUnit.SECONDS)
        assertEquals(listOf("b"), written)
        assertFalse(queue.failing)
        broken = true
        queue.submit("c")
        queue.flush().get(5, TimeUnit.SECONDS)
        assertEquals(2, reports)
    }

    @Test fun projectSaverWritesTheFileWithoutTheCallerWaiting() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(app.absentmotion.model.Scene.parse("<scene width='10' height='10' title='A'/>"))
        val saver = ProjectSaver(id, store, Recovery(root.resolve("recovery")), failed = { throw AssertionError(it) }, executor = executor)
        saver.commit("<scene width='10' height='10' title='B'/>")
        saver.flush().get(5, TimeUnit.SECONDS)
        assertEquals("B", store.load(id)!!.title)
        assertFalse(root.resolve("projects/$id.xml.tmp").exists())
    }
}
