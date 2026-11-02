package app.absentmotion.ai

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.absentmotion.R
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.SmoothLevel
import app.absentmotion.i18n.AppText
import app.absentmotion.ui.onboarding.PhoneSpeed
import app.absentmotion.ui.onboarding.phoneCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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

internal fun aiRam(context: Context): Long =
    runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)

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
private const val GPU_AWAY = "~"

internal class GpuMark(private val token: String, private val read: () -> String?, private val write: (String?) -> Unit) {
    private var open = 0
    private var away = false

    private fun record() = if (away) token + GPU_AWAY else token

    @Synchronized fun begin() { if (open++ == 0) write(record()) }

    @Synchronized fun end() { if (open > 0 && --open == 0) write(null) }

    @Synchronized fun seen(foreground: Boolean) {
        if (away == !foreground) return
        away = !foreground
        if (open > 0) write(record())
    }

    @Synchronized fun crashed() {
        if (open > 0) write(null)
        open = 0
    }

    @Synchronized fun diedBefore(): String? = read()?.takeIf { !it.startsWith(token) }

    @Synchronized fun forgive() { if (read()?.startsWith(token) != true) write(null) }
}

private val BenignDeaths = setOf(
    ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
    ApplicationExitInfo.REASON_PACKAGE_UPDATED,
)

internal fun gpuDeathBlamed(reason: Int?, foreground: Boolean = true): Boolean = if (reason == null) foreground else reason !in BenignDeaths

internal fun gpuRecordAway(record: String): Boolean = record.endsWith(GPU_AWAY)

internal fun gpuRecordToken(record: String): String = record.removeSuffix(GPU_AWAY)

internal suspend fun <T : Any> awaitProbe(work: Deferred<T>, timeoutMs: Long): T? {
    val result = withTimeoutOrNull(timeoutMs) { work.await() }
    if (result == null) work.cancel()
    return result
}

internal object AiDevice {
    private val rates = ConcurrentHashMap<Pair<AiModel, Boolean>, Double>()
    private val running = ConcurrentHashMap<AiModel, Deferred<Boolean>>()
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probing = Mutex()
    @Volatile private var mark: GpuMark? = null

    private fun gpuPrefs(context: Context) = context.getSharedPreferences("ai_gpu", Context.MODE_PRIVATE)

    private fun mark(context: Context): GpuMark = mark ?: synchronized(this) {
        mark ?: gpuPrefs(context).let { prefs ->
            GpuMark("${Process.myPid()}:${UUID.randomUUID()}", { prefs.getString(GPU_RUNNING, null) }) {
                prefs.edit().apply { if (it == null) remove(GPU_RUNNING) else putString(GPU_RUNNING, it) }.commit()
            }
        }.also { created ->
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { created.crashed() }
                previous?.uncaughtException(thread, error)
            }
            mark = created
        }
    }

    fun recover(context: Context) {
        val mark = mark(context)
        val record = mark.diedBefore() ?: return
        if (gpuDeathBlamed(exitReason(context, gpuRecordToken(record)), !gpuRecordAway(record))) gpuFailed(context) else mark.forgive()
    }

    fun gpuAlive(context: Context) {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        mark(context).seen(info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
    }

    private fun exitReason(context: Context, token: String): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pid = token.substringBefore(':').toIntOrNull() ?: return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()?.reason
        }.getOrNull()
    }

    fun gpuChoice(context: Context): Boolean? {
        recover(context)
        val prefs = gpuPrefs(context)
        return if (prefs.contains(GPU_USE)) prefs.getBoolean(GPU_USE, false) else null
    }

    @Volatile private var quarantined = false

    fun gpuOn(context: Context): Boolean = !quarantined && aiUsesGpu(AiNative.gpu, gpuChoice(context))

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

    private fun proofKey(context: Context, model: AiModel) = "${DeviceCapabilities.of(context).key}:${model.name}"

    fun gpuProven(context: Context, model: AiModel): Boolean = gpuOn(context) && gpuPrefs(context).getString(proofKey(context, model), null) == GPU_OK

    suspend fun probeGpu(context: Context, model: AiModel): Boolean = probing.withLock {
        if (!withContext(Dispatchers.Default) { gpuOn(context) }) return false
        if (gpuProven(context, model)) return true
        if (!model.installed(context)) return false
        val work = running[model]?.takeIf { it.isActive } ?: probes.async {
            val ok: Boolean? = try {
                AiNet.open(context, model, gpu = true).use { net ->
                    val smooth = SmoothLevel.entries.firstOrNull { it.model == model }
                    val side = smooth?.padding ?: PROBE_SIDE
                    val input = IntArray(side * side) { 0xFF808080.toInt() }
                    when {
                        model == AiModel.Cutout -> Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).let { probe ->
                            try { probe.eraseColor(0xFF808080.toInt()); net.cutout(probe, CUTOUT_SIDE) } finally { probe.recycle() }
                        }
                        smooth != null -> net.interpolate(input, input, side, side, 0.5f, smooth.padding)
                        else -> net.upscale(input, side, side, 1)
                    }
                    net.onGpu
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AMAi", "cannot tell whether the GPU works", e)
                null
            }
            when (ok) {
                true -> gpuPrefs(context).edit().putString(proofKey(context, model), GPU_OK).commit()
                false -> gpuFailed(context)
                null -> Unit
            }
            running.remove(model)
            ok == true
        }.also { running[model] = it }
        awaitProbe(work, PROBE_MS) ?: run {
            Log.w("AMAi", "the GPU check did not finish in time, AI runs on the processor until the app restarts")
            quarantined = true
            running.remove(model, work)
            false
        }
    }

    suspend fun phone(context: Context): AiPhone {
        val ram = aiRam(context)
        val speed = runCatching { DeviceCapabilities.of(context).load()?.let { phoneCheckResult(it, if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0).speed } }.getOrNull() ?: PhoneSpeed.Basic
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
            withContext(Dispatchers.IO) {
                AiNet.open(context, model).use { net ->
                    suspend fun once() = if (smooth != null) net.interpolate(input, input, side, side, 0.5f, smooth.padding) else net.upscale(input, side, side, 1)
                    once()
                    val start = SystemClock.elapsedRealtimeNanos()
                    once()
                    val pixels = if (smooth != null) side * side else (side + 2 * AiNet.OVERLAP) * (side + 2 * AiNet.OVERLAP)
                    (model.gmacPerMegapixel * pixels / 1e6 / ((SystemClock.elapsedRealtimeNanos() - start) / 1e9).coerceAtLeast(1e-4)).also { rates[model to net.onGpu] = it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
