package android.os

open class PowerManager {
    val currentThermalStatus: Int get() = 0
    fun getThermalHeadroom(seconds: Int): Float = Float.NaN
    val isPowerSaveMode: Boolean get() = false
    val isInteractive: Boolean get() = true
    fun addThermalStatusListener(listener: OnThermalStatusChangedListener) {}
    fun addThermalStatusListener(executor: java.util.concurrent.Executor, listener: OnThermalStatusChangedListener) {}
    fun removeThermalStatusListener(listener: OnThermalStatusChangedListener) {}
    fun newWakeLock(flags: Int, tag: String) = WakeLock()
    class WakeLock { fun acquire() {}; fun acquire(timeout: Long) {}; fun release() {}; val isHeld = false; fun setReferenceCounted(on: Boolean) {} }
    fun interface OnThermalStatusChangedListener { fun onThermalStatusChanged(status: Int) }
    companion object { const val THERMAL_STATUS_NONE = 0; const val THERMAL_STATUS_LIGHT = 1; const val THERMAL_STATUS_MODERATE = 2; const val THERMAL_STATUS_SEVERE = 3; const val THERMAL_STATUS_CRITICAL = 4; const val THERMAL_STATUS_EMERGENCY = 5; const val THERMAL_STATUS_SHUTDOWN = 6; const val PARTIAL_WAKE_LOCK = 1 }
}
