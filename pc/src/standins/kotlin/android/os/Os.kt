package android.os

object Build {
    object VERSION { @JvmField val SDK_INT = 36; @JvmField val RELEASE = "desktop"; @JvmField val MEDIA_PERFORMANCE_CLASS = 0 }
    object VERSION_CODES { const val O = 26; const val P = 28; const val Q = 29; const val R = 30; const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34; const val VANILLA_ICE_CREAM = 35 }
    @JvmField val MANUFACTURER = "Desktop"
    @JvmField val MODEL = System.getProperty("os.name") ?: "Desktop"
    @JvmField val BRAND = "Desktop"
    @JvmField val DEVICE = "desktop"
    @JvmField val HARDWARE = System.getProperty("os.arch") ?: ""
    @JvmField val FINGERPRINT = "desktop/" + (System.getProperty("os.name") ?: "") + "/" + (System.getProperty("os.version") ?: "")
    @JvmField val SOC_MODEL = System.getProperty("os.arch") ?: ""
    @JvmField val SUPPORTED_ABIS = arrayOf(System.getProperty("os.arch") ?: "")
    @JvmField val SUPPORTED_64_BIT_ABIS = SUPPORTED_ABIS
}

object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime()
    @JvmStatic fun sleep(ms: Long) = Thread.sleep(ms)
}

class Looper private constructor(val main: Boolean, internal val worker: java.util.concurrent.ScheduledExecutorService? = null) {
    companion object {
        private val mainLooper = Looper(true)
        private val other = Looper(false)
        @JvmStatic fun getMainLooper() = mainLooper
        @JvmStatic fun myLooper(): Looper? = if (javax.swing.SwingUtilities.isEventDispatchThread()) mainLooper else other
        @JvmStatic fun prepare() {}
        @JvmStatic fun loop() {}
        internal fun worker(name: String) = Looper(false, java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, name).apply { isDaemon = true } })
    }
    val thread: Thread? get() = null
    fun quitSafely() { worker?.shutdown() }
    fun quit() { worker?.shutdownNow() }
}

open class Handler(private val looper: Looper = Looper.getMainLooper()) {
    private val timer by lazy { looper.worker ?: if (looper.main) mainTimer else java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "handler").apply { isDaemon = true } } }
    private val pending = java.util.concurrent.ConcurrentHashMap<Runnable, MutableList<java.util.concurrent.Future<*>>>()
    private fun run(r: Runnable) { if (looper.main) javax.swing.SwingUtilities.invokeLater(r) else r.run() }
    fun post(r: Runnable): Boolean = postDelayed(r, 0)
    fun postDelayed(r: Runnable, delay: Long): Boolean {
        val f = try { timer.schedule({ pending[r]?.removeIf { it.isDone }; run(r) }, delay.coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.RejectedExecutionException) { return false }
        pending.getOrPut(r) { java.util.Collections.synchronizedList(mutableListOf()) }.add(f)
        return true
    }
    fun removeCallbacks(r: Runnable) { pending.remove(r)?.forEach { it.cancel(false) } }
    fun removeCallbacksAndMessages(token: Any?) { pending.values.forEach { l -> l.forEach { it.cancel(false) } }; pending.clear() }
    fun getLooper() = looper

    private companion object {
        val mainTimer: java.util.concurrent.ScheduledExecutorService by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "main-handler").apply { isDaemon = true } } }
    }
}

class HandlerThread(name: String) : Thread(name) {
    val looper: Looper = Looper.worker(name)
    fun quitSafely(): Boolean = true.also { looper.quitSafely() }
    fun quit(): Boolean = true.also { looper.quit() }
}

object Process {
    const val THREAD_PRIORITY_DISPLAY = -4
    const val THREAD_PRIORITY_URGENT_AUDIO = -19
    const val THREAD_PRIORITY_BACKGROUND = 10
    const val THREAD_PRIORITY_AUDIO = -16
    @JvmStatic fun setThreadPriority(p: Int) {}
    @JvmStatic fun myPid() = ProcessHandle.current().pid().toInt()
    @JvmStatic fun is64Bit() = true
}

class Bundle {
    private val values = HashMap<String, Any?>()
    fun getFloat(key: String, default: Float = 0f): Float = values[key] as? Float ?: default
    fun putFloat(key: String, value: Float) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun putString(key: String, value: String?) { values[key] = value }
    fun getInt(key: String, default: Int = 0): Int = values[key] as? Int ?: default
    fun putInt(key: String, value: Int) { values[key] = value }
    fun containsKey(key: String) = values.containsKey(key)
}
