package app.absentmotion.export

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ThermalLevel { Normal, Warm, Hot, Critical }

data class ThermalState(
    val level: ThermalLevel = ThermalLevel.Normal,
    val status: Int = 0,
    val headroom: Float = Float.NaN,
    val batteryCelsius: Float = Float.NaN,
) {
    val message: UiText? get() = thermalMessage(level)
}

internal fun thermalLevel(status: Int, headroom: Float, batteryCelsius: Float): ThermalLevel {
    fun over(value: Float, limit: Float) = !value.isNaN() && value >= limit
    return when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL || over(batteryCelsius, 45f) -> ThermalLevel.Critical
        status == PowerManager.THERMAL_STATUS_SEVERE || over(batteryCelsius, 42f) || over(headroom, 1f) -> ThermalLevel.Hot
        status == PowerManager.THERMAL_STATUS_MODERATE || over(batteryCelsius, 40f) || over(headroom, 0.95f) -> ThermalLevel.Warm
        else -> ThermalLevel.Normal
    }
}

internal fun thermalMessage(level: ThermalLevel): UiText? = when (level) {
    ThermalLevel.Normal -> null
    ThermalLevel.Warm -> txt(R.string.export_thermal_warm)
    ThermalLevel.Hot -> txt(R.string.export_thermal_hot)
    ThermalLevel.Critical -> txt(R.string.export_thermal_critical)
}

internal fun pauseMs(level: ThermalLevel): Long = when (level) {
    ThermalLevel.Normal -> 0
    ThermalLevel.Warm -> 5
    ThermalLevel.Hot -> 25
    ThermalLevel.Critical -> 120
}

class ThermalMonitor private constructor(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flow = MutableStateFlow(ThermalState())
    private var users = 0
    private var poll: Job? = null
    private val listener = PowerManager.OnThermalStatusChangedListener { refresh() }

    val state: StateFlow<ThermalState> = flow.asStateFlow()

    @Volatile var forced: ThermalLevel? = null

    fun refresh(): ThermalState {
        val status = power?.currentThermalStatus ?: 0
        val headroom = runCatching { power?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN)
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f } ?: Float.NaN
        val level = forced ?: thermalLevel(status, headroom, battery)
        return ThermalState(level, status, headroom, battery).also { flow.value = it }
    }

    @Synchronized fun start() {
        if (users++ > 0) return
        power?.addThermalStatusListener(app.mainExecutor, listener)
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(2000)
            }
        }
    }

    @Synchronized fun stop() {
        if (users == 0 || --users > 0) return
        power?.removeThermalStatusListener(listener)
        poll?.cancel()
        poll = null
    }

    fun pauseMs(): Long = pauseMs(forced ?: flow.value.level)

    val hot: Boolean get() = (forced ?: flow.value.level) >= ThermalLevel.Hot

    companion object {
        @Volatile private var instance: ThermalMonitor? = null

        fun of(context: Context): ThermalMonitor =
            instance ?: synchronized(this) { instance ?: ThermalMonitor(context).also { instance = it } }
    }
}
