package app.absentmotion.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class AiPhone(val runtime: Boolean, val gpu: AiGpu?, val ramBytes: Long, val speed: PhoneSpeed) {
    val usableGpu: Boolean get() = gpu != null && !gpu.software
}

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
    return if (phone.usableGpu) gpu * (if (phone.gpu?.fp16 == true) 1.0 else 0.6) else cpu
}

internal fun aiVerdict(phone: AiPhone, seconds: Double): AiVerdict = when {
    !phone.runtime || phone.ramBytes in 1 until MIN_RAM || seconds > CANNOT_SECONDS -> AiVerdict.Cannot
    !phone.usableGpu || seconds > STRUGGLE_SECONDS || phone.ramBytes in 1 until GOOD_RAM -> AiVerdict.Struggle
    else -> AiVerdict.Fine
}

internal fun aiEstimate(phone: AiPhone, gmac: Double, measuredRate: Double?): AiEstimate {
    val seconds = gmac / (measuredRate ?: guessedRate(phone)).coerceAtLeast(0.01)
    return AiEstimate(seconds, aiVerdict(phone, seconds), measuredRate != null)
}

private const val MIN_RAM = 2_500_000_000L
private const val GOOD_RAM = 4_000_000_000L
private const val STRUGGLE_SECONDS = 45 * 60.0
private const val CANNOT_SECONDS = 8 * 3600.0
private const val BENCH_SIDE = 96
private const val PROBE_SIDE = 16
private const val PROBE_MS = 45_000L
private const val GPU_OK = "ok"
private const val GPU_BAD = "bad"
private const val GPU_PENDING = "pending"

internal object AiDevice {
    private val rates = ConcurrentHashMap<AiModel, Double>()
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probing = Mutex()

    private fun gpuPrefs(context: Context) = context.getSharedPreferences("ai_gpu", Context.MODE_PRIVATE)

    fun gpuProven(context: Context): Boolean = gpuPrefs(context).getString(DeviceCapabilities.of(context).key, null) == GPU_OK

    suspend fun probeGpu(context: Context, model: AiModel): Boolean = probing.withLock {
        val prefs = gpuPrefs(context)
        val key = DeviceCapabilities.of(context).key
        fun mark(state: String?) { prefs.edit().apply { if (state == null) remove(key) else putString(key, state) }.commit() }
        when (prefs.getString(key, null)) {
            GPU_OK -> return true
            GPU_BAD -> return false
            GPU_PENDING -> { mark(GPU_BAD); return false }
        }
        val gpu = withContext(Dispatchers.Default) { AiNative.gpu }
        if (gpu == null || gpu.software) { mark(GPU_BAD); return false }
        if (!model.installed(context)) return false
        mark(GPU_PENDING)
        val work = probes.async {
            AiNet.open(context, model, gpu = true).use { net ->
                val smooth = SmoothLevel.entries.firstOrNull { it.model == model }
                val side = smooth?.padding ?: PROBE_SIDE
                val input = IntArray(side * side) { 0xFF808080.toInt() }
                if (smooth != null) net.interpolate(input, input, side, side, 0.5f, smooth.padding) else net.upscale(input, side, side, 1)
                net.onGpu
            }
        }
        val ok = try {
            withTimeoutOrNull(PROBE_MS) { try { work.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { false } }
        } catch (e: CancellationException) {
            mark(null)
            throw e
        } ?: false
        mark(if (ok) GPU_OK else GPU_BAD)
        ok
    }

    suspend fun phone(context: Context): AiPhone {
        val ram = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        val speed = runCatching { phoneCheckResult(DeviceCapabilities.of(context).load(), Build.VERSION.MEDIA_PERFORMANCE_CLASS).speed }.getOrDefault(PhoneSpeed.Basic)
        return withContext(Dispatchers.Default) { AiPhone(AiNative.loaded, AiNative.gpu, ram, speed) }
    }

    fun measured(model: AiModel): Double? = rates[model]

    suspend fun benchmark(context: Context, model: AiModel): Double? {
        rates[model]?.let { return it }
        if (!model.installed(context)) return null
        val smooth = SmoothLevel.entries.firstOrNull { it.model == model }
        val side = smooth?.padding?.times(2) ?: BENCH_SIDE
        val input = IntArray(side * side) { 0xFF000000.toInt() or ((it * 2654435761L).toInt() and 0xFFFFFF) }
        probeGpu(context, model)
        return try {
            withContext(Dispatchers.IO) { AiNet.open(context, model) }.use { net ->
                suspend fun once() = if (smooth != null) net.interpolate(input, input, side, side, 0.5f, smooth.padding) else net.upscale(input, side, side, 1)
                once()
                val start = SystemClock.elapsedRealtimeNanos()
                once()
                val pixels = if (smooth != null) side * side else (side + 2 * AiNet.OVERLAP) * (side + 2 * AiNet.OVERLAP)
                model.gmacPerMegapixel * pixels / 1e6 / ((SystemClock.elapsedRealtimeNanos() - start) / 1e9).coerceAtLeast(1e-4)
            }.also { rates[model] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
