package app.absentmotion.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import app.absentmotion.R
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.SmoothLevel
import app.absentmotion.i18n.AppText
import app.absentmotion.ui.onboarding.PhoneSpeed
import app.absentmotion.ui.onboarding.phoneCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class AiPhone(val runtime: Boolean, val gpu: AiGpu?, val ramBytes: Long, val speed: PhoneSpeed, val usesGpu: Boolean = aiUsesGpu(gpu, null))

enum class AiVerdict { Fine, Struggle, Cannot }

internal class AiEstimate(val seconds: Double, val verdict: AiVerdict, val measured: Boolean) {
    val text: String get() = when (verdict) {
        AiVerdict.Cannot -> AppText.get(R.string.ai_phone_cannot)
        AiVerdict.Struggle -> AppText.get(R.string.ai_phone_struggle, aiDuration(seconds))
        AiVerdict.Fine -> if (seconds < 60) AppText.get(R.string.ai_about_under_minute) else AppText.get(R.string.ai_about_time, aiDuration(seconds))
    }

    val extra: String get() = if (verdict == AiVerdict.Fine) AppText.get(R.string.ai_smooth_adds, aiDuration(seconds)) else text
}

internal fun aiDuration(seconds: Double): String {
    val minutes = ceil(seconds / 60).toInt().coerceAtLeast(1)
    return if (minutes < 90) AppText.get(R.string.ai_minutes, minutes.toString()) else AppText.get(R.string.ai_hours, (minutes / 60.0).roundToInt().toString())
}

internal fun guessedRate(phone: AiPhone): Double {
    val gpu = when (phone.speed) { PhoneSpeed.Fast -> 400.0; PhoneSpeed.Good -> 120.0; PhoneSpeed.Basic -> 35.0 }
    val cpu = when (phone.speed) { PhoneSpeed.Fast -> 40.0; PhoneSpeed.Good -> 15.0; PhoneSpeed.Basic -> 6.0 }
    return if (phone.usesGpu) gpu * (if (phone.gpu?.fp16 == true) 1.0 else 0.6) else cpu
}

internal fun aiVerdict(phone: AiPhone, seconds: Double): AiVerdict = when {
    !phone.runtime || phone.ramBytes in 1 until MIN_RAM || seconds > CANNOT_SECONDS -> AiVerdict.Cannot
    seconds > STRUGGLE_SECONDS || !phone.usesGpu && seconds > CPU_STRUGGLE_SECONDS || phone.ramBytes in 1 until GOOD_RAM -> AiVerdict.Struggle
    else -> AiVerdict.Fine
}

internal fun aiEstimate(phone: AiPhone, gmac: Double, measuredRate: Double?): AiEstimate {
    val seconds = gmac / (measuredRate ?: guessedRate(phone)).coerceAtLeast(0.01)
    return AiEstimate(seconds, aiVerdict(phone, seconds), measuredRate != null)
}

private const val MIN_RAM = 2_500_000_000L
private const val GOOD_RAM = 4_000_000_000L
private const val STRUGGLE_SECONDS = 45 * 60.0
private const val CPU_STRUGGLE_SECONDS = 10 * 60.0
private const val CANNOT_SECONDS = 8 * 3600.0
private const val BENCH_SIDE = 96
private const val PROBE_SIDE = 16
private const val PROBE_MS = 45_000L
private const val GPU_OK = "ok"
private const val GPU_USE = "use"
private const val GPU_FAILED = "failed"
private const val GPU_RUNNING = "running"

internal class GpuMark(private val token: String, private val read: () -> String?, private val write: (String?) -> Unit) {
    private var open = 0

    @Synchronized fun begin() { if (open++ == 0) write(token) }

    @Synchronized fun end() { if (open > 0 && --open == 0) write(null) }

    @Synchronized fun diedBefore(): Boolean = read().let { it != null && it != token }
}

internal object AiDevice {
    private val rates = ConcurrentHashMap<Pair<AiModel, Boolean>, Double>()
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probing = Mutex()
    @Volatile private var mark: GpuMark? = null

    private fun gpuPrefs(context: Context) = context.getSharedPreferences("ai_gpu", Context.MODE_PRIVATE)

    private fun mark(context: Context): GpuMark = mark ?: synchronized(this) {
        mark ?: gpuPrefs(context).let { prefs ->
            GpuMark(UUID.randomUUID().toString(), { prefs.getString(GPU_RUNNING, null) }) {
                prefs.edit().apply { if (it == null) remove(GPU_RUNNING) else putString(GPU_RUNNING, it) }.commit()
            }
        }.also { mark = it }
    }

    fun recover(context: Context) {
        if (mark(context).diedBefore()) gpuFailed(context)
    }

    fun gpuChoice(context: Context): Boolean? {
        recover(context)
        val prefs = gpuPrefs(context)
        return if (prefs.contains(GPU_USE)) prefs.getBoolean(GPU_USE, false) else null
    }

    fun gpuOn(context: Context): Boolean = aiUsesGpu(AiNative.gpu, gpuChoice(context))

    fun gpuRisky(context: Context): Boolean = AiNative.gpu?.known != true || gpuPrefs(context).getBoolean(GPU_FAILED, false)

    fun setGpu(context: Context, on: Boolean) {
        gpuPrefs(context).edit().putBoolean(GPU_USE, on).apply()
    }

    fun gpuRunning(context: Context, running: Boolean) = mark(context).let { if (running) it.begin() else it.end() }

    fun gpuFailed(context: Context) {
        val prefs = gpuPrefs(context)
        val wasOn = prefs.getBoolean(GPU_USE, true)
        prefs.edit().putBoolean(GPU_USE, false).putBoolean(GPU_FAILED, true).remove(GPU_RUNNING).commit()
        if (wasOn) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, AppText.get(R.string.ai_gpu_failed), Toast.LENGTH_LONG).show() }
        }
    }

    fun gpuProven(context: Context): Boolean = gpuOn(context) && gpuPrefs(context).getString(DeviceCapabilities.of(context).key, null) == GPU_OK

    suspend fun probeGpu(context: Context, model: AiModel): Boolean = probing.withLock {
        if (!withContext(Dispatchers.Default) { gpuOn(context) }) return false
        if (gpuProven(context)) return true
        if (!model.installed(context)) return false
        val work = probes.async {
            AiNet.open(context, model, gpu = true).use { net ->
                val smooth = SmoothLevel.entries.firstOrNull { it.model == model }
                val side = smooth?.padding ?: PROBE_SIDE
                val input = IntArray(side * side) { 0xFF808080.toInt() }
                if (smooth != null) net.interpolate(input, input, side, side, 0.5f, smooth.padding) else net.upscale(input, side, side, 1)
                net.onGpu
            }
        }
        val ok = withTimeoutOrNull(PROBE_MS) { try { work.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { false } } ?: false
        if (ok) gpuPrefs(context).edit().putString(DeviceCapabilities.of(context).key, GPU_OK).commit() else gpuFailed(context)
        ok
    }

    suspend fun phone(context: Context): AiPhone {
        val ram = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        val speed = runCatching { phoneCheckResult(DeviceCapabilities.of(context).load(), Build.VERSION.MEDIA_PERFORMANCE_CLASS).speed }.getOrDefault(PhoneSpeed.Basic)
        return withContext(Dispatchers.Default) { AiPhone(AiNative.loaded, AiNative.gpu, ram, speed, gpuOn(context)) }
    }

    fun measured(model: AiModel, gpu: Boolean): Double? = rates[model to gpu]

    suspend fun benchmark(context: Context, model: AiModel): Double? {
        if (!model.installed(context)) return null
        val smooth = SmoothLevel.entries.firstOrNull { it.model == model }
        val side = smooth?.padding?.times(2) ?: BENCH_SIDE
        val input = IntArray(side * side) { 0xFF000000.toInt() or ((it * 2654435761L).toInt() and 0xFFFFFF) }
        val gpu = probeGpu(context, model)
        rates[model to gpu]?.let { return it }
        return try {
            withContext(Dispatchers.IO) { AiNet.open(context, model) }.use { net ->
                suspend fun once() = if (smooth != null) net.interpolate(input, input, side, side, 0.5f, smooth.padding) else net.upscale(input, side, side, 1)
                once()
                val start = SystemClock.elapsedRealtimeNanos()
                once()
                val pixels = if (smooth != null) side * side else (side + 2 * AiNet.OVERLAP) * (side + 2 * AiNet.OVERLAP)
                (model.gmacPerMegapixel * pixels / 1e6 / ((SystemClock.elapsedRealtimeNanos() - start) / 1e9).coerceAtLeast(1e-4)).also { rates[model to net.onGpu] = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
