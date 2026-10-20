package app.absentmotion.library

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DiskThread {
    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "am-disk").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
}

class SaveQueue(
    private val executor: ScheduledExecutorService,
    private val delayMs: Long,
    private val write: (String) -> Unit,
    private val failed: (Exception) -> Unit = {},
) {
    private var pending: String? = null
    private var scheduled: ScheduledFuture<*>? = null

    @Volatile var failing = false
        private set

    @Synchronized fun submit(text: String) {
        pending = text
        if (scheduled == null) scheduled = executor.schedule(::run, delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun cancel() { pending = null }

    @Synchronized fun flush(): Future<*> {
        scheduled?.cancel(false)
        scheduled = null
        return executor.submit(::run)
    }

    private fun run() {
        val text = synchronized(this) { scheduled = null; pending.also { pending = null } } ?: return
        try {
            write(text)
            failing = false
        } catch (e: Exception) {
            synchronized(this) { if (pending == null) pending = text }
            if (!failing) {
                failing = true
                failed(e)
            }
        }
    }
}

class ProjectSaver(
    val id: String,
    private val store: ProjectStore,
    private val recovery: Recovery,
    failed: (Exception) -> Unit,
    private val executor: ScheduledExecutorService = DiskThread.executor,
) {
    private val project = SaveQueue(executor, SAVE_DELAY_MS, { xml ->
        store.save(id, xml)
        recovery.clear(id)
        runCatching { store.versions.snapshot(id, xml, SNAPSHOT_GAP_MS) }
    }, failed)

    private val drafts = SaveQueue(executor, DRAFT_DELAY_MS, { xml -> recovery.write(id, xml) })

    val failing: Boolean get() = project.failing

    fun commit(xml: String) {
        drafts.cancel()
        project.submit(xml)
    }

    fun draft(xml: String) = drafts.submit(xml)

    fun flush(): Future<*> = project.flush().also { drafts.flush() }

    fun snapshot(xml: String): Future<*> = executor.submit { runCatching { store.versions.snapshot(id, xml) } }

    companion object {
        const val SAVE_DELAY_MS = 400L
        const val DRAFT_DELAY_MS = 1000L
        const val SNAPSHOT_GAP_MS = 120_000L
    }
}
