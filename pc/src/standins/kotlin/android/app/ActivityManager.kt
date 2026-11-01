package android.app

open class ActivityManager {
    val memoryClass: Int get() = (Runtime.getRuntime().maxMemory() / 1048576).toInt().coerceIn(256, 2048)
    val largeMemoryClass: Int get() = memoryClass
    val isLowRamDevice: Boolean get() = false
    class MemoryInfo { @JvmField var availMem = Runtime.getRuntime().maxMemory(); @JvmField var totalMem = Runtime.getRuntime().maxMemory(); @JvmField var lowMemory = false; @JvmField var threshold = 0L }
    fun getMemoryInfo(info: MemoryInfo) {}
    class RunningAppProcessInfo { @JvmField var importance = IMPORTANCE_FOREGROUND; @JvmField var lastTrimLevel = 0; companion object { const val IMPORTANCE_FOREGROUND = 100; const val IMPORTANCE_VISIBLE = 200; const val IMPORTANCE_CACHED = 400 } }
    fun getHistoricalProcessExitReasons(pkg: String?, pid: Int, max: Int): List<android.app.ApplicationExitInfo> = emptyList()
    fun setProcessStateSummary(bytes: ByteArray?) {}
    companion object {
        val Desktop = ActivityManager()
        @JvmStatic fun getMyMemoryState(info: RunningAppProcessInfo) {}
    }
}
