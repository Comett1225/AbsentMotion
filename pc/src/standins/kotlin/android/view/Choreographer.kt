package android.view

class Choreographer private constructor() {
    fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }
    private val pending = LinkedHashSet<FrameCallback>()
    fun postFrameCallback(callback: FrameCallback) {
        val first = synchronized(pending) { pending.isEmpty().also { pending += callback } }
        if (first) javax.swing.SwingUtilities.invokeLater { run() }
    }
    fun postFrameCallbackDelayed(callback: FrameCallback, delay: Long) { javax.swing.Timer(delay.toInt().coerceAtLeast(1)) { postFrameCallback(callback) }.apply { isRepeats = false; start() } }
    fun removeFrameCallback(callback: FrameCallback) { synchronized(pending) { pending -= callback } }
    private fun run() {
        val now = System.nanoTime()
        val due = synchronized(pending) { pending.toList().also { pending.clear() } }
        due.forEach { it.doFrame(now) }
    }
    companion object {
        private val main = Choreographer()
        @JvmStatic fun getInstance() = main
    }
}
