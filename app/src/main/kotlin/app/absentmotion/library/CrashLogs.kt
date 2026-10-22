package app.absentmotion.library

import androidx.annotation.RequiresApi
import android.content.SharedPreferences
import app.absentmotion.platform.readAtMost
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.export.StorageFullException
import app.absentmotion.platform.Context
import app.absentmotion.platform.versionName
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

enum class CrashReason(@StringRes val sentence: Int) {
    MemoryExport(R.string.crash_reason_memory_export),
    Memory(R.string.crash_reason_memory),
    Storage(R.string.crash_reason_storage),
    Engine(R.string.crash_reason_engine),
    NotResponding(R.string.crash_reason_not_responding),
    StartFailed(R.string.crash_reason_start),
    LowMemory(R.string.crash_reason_low_memory),
    Error(R.string.crash_reason_error),
    Unknown(R.string.crash_reason_unknown),
}

data class CrashEntry(val file: File, val time: Long, val reason: CrashReason)

internal data class Exit(val time: Long, val reason: Int, val importance: Int, val doing: String)

internal fun crashReason(error: Throwable, doing: String): CrashReason {
    val chain = generateSequence(error) { it.cause }.take(16).toList()
    return when {
        chain.any { it is OutOfMemoryError } -> if (doing.startsWith("exporting")) CrashReason.MemoryExport else CrashReason.Memory
        chain.any { it is StorageFullException || (it is IOException && it.message.orEmpty().let { m -> "ENOSPC" in m || m.contains("no space left", ignoreCase = true) }) } -> CrashReason.Storage
        else -> CrashReason.Error
    }
}

internal fun exitReason(reason: Int, importance: Int, doing: String): CrashReason? = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> CrashReason.Error
    ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashReason.Engine
    ApplicationExitInfo.REASON_ANR -> CrashReason.NotResponding
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> CrashReason.StartFailed
    ApplicationExitInfo.REASON_LOW_MEMORY -> when {
        importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> null
        doing.startsWith("exporting") -> CrashReason.MemoryExport
        else -> CrashReason.LowMemory
    }
    else -> null
}

internal fun exitsToRecord(exits: List<Exit>, seen: Long, logged: List<Long>): List<Pair<Exit, CrashReason>> =
    exits.filter { it.time > seen }.sortedBy { it.time }.mapNotNull { exit ->
        val reason = exitReason(exit.reason, exit.importance, exit.doing) ?: return@mapNotNull null
        val handled = exit.reason == ApplicationExitInfo.REASON_CRASH && logged.any { it in exit.time - 60_000..exit.time + 5_000 }
        if (handled) null else exit to reason
    }

internal fun readableStrings(bytes: ByteArray, maxChars: Int, shortest: Int = 5): String {
    val out = StringBuilder()
    val run = StringBuilder()
    fun flush() {
        if (run.length >= shortest && out.length < maxChars) out.append(run).append('\n')
        run.setLength(0)
    }
    for (byte in bytes) {
        val c = byte.toInt() and 255
        if (c in 32..126) run.append(c.toChar()) else flush()
    }
    flush()
    return out.take(maxChars).toString()
}

internal fun crashEntries(folder: File): List<CrashEntry> = folder.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().mapNotNull { file ->
    val parts = file.name.removeSuffix(".txt").split('_', limit = 2)
    val time = parts[0].toLongOrNull() ?: return@mapNotNull null
    CrashEntry(file, time, CrashReason.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: CrashReason.Unknown)
}.sortedByDescending { it.time }

internal fun writeCrash(folder: File, time: Long, reason: CrashReason, header: String, detail: String, keep: Int = 20) {
    folder.mkdirs()
    var name = time
    while (crashEntries(folder).any { it.time == name }) name++
    val stamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())
    File(folder, "${name}_${reason.name}.txt").writeText("$header\nTime: $stamp\nReason: ${reason.name}\n\n$detail\n")
    crashEntries(folder).drop(keep).forEach { it.file.delete() }
}

object CrashLogs {
    private const val MaxDetail = 64 * 1024
    @Volatile private var folder: File? = null
    @Volatile private var activities: ActivityManager? = null
    @Volatile private var about = ""
    @Volatile private var screen = "starting"
    @Volatile private var task: String? = null
    @Volatile private var failedDuring: Pair<Throwable, String>? = null

    val doing: String get() = task ?: screen

    fun install(context: Context) {
        if (folder != null) return
        val app = context.applicationContext
        activities = app.getSystemService(ActivityManager::class.java)
        val code = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
        about = "AbsentMotion ${versionName(app)} ($code)\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
        folder = File(app.filesDir, "crashlogs")
        publish()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val now = System.currentTimeMillis()
                val what = failedDuring?.takeIf { (failed, _) -> generateSequence(error) { it.cause }.any { it === failed } }?.second ?: doing
                folder?.let { writeCrash(it, now, crashReason(error, what), "$about\nKind: Java exception on thread ${thread.name}\nDoing: $what", error.stackTraceToString().take(MaxDetail)) }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun onScreen(name: String) {
        if (screen == name) return
        screen = name
        publish()
    }

    suspend fun <T> during(name: String, block: suspend () -> T): T {
        val before = task
        task = name
        publish()
        try {
            return block()
        } catch (t: Throwable) {
            failedDuring = t to name
            throw t
        } finally {
            task = before
            publish()
        }
    }

    private fun publish() {
        if (Build.VERSION.SDK_INT >= 30) runCatching { activities?.setProcessStateSummary(doing.take(100).encodeToByteArray()) }
    }

    fun list(context: Context): List<CrashEntry> = crashEntries(File(context.filesDir, "crashlogs"))

    fun clear(context: Context) {
        crashEntries(File(context.filesDir, "crashlogs")).forEach { it.file.delete() }
    }

    fun collect(context: Context): CrashEntry? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("crashlogs", Context.MODE_PRIVATE)
        val dir = File(app.filesDir, "crashlogs")
        if (Build.VERSION.SDK_INT >= 30) recordExits(app, prefs, dir)
        val shown = prefs.getLong("shown", 0L)
        return crashEntries(dir).firstOrNull { it.time > shown }
    }

    @RequiresApi(30)
    private fun recordExits(app: Context, prefs: SharedPreferences, dir: File) {
        val infos = runCatching { app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 0) }.getOrDefault(emptyList())
        val seen = prefs.getLong("exitsSeen", -1L)
        if (seen >= 0) {
            val exits = infos.map { Exit(it.timestamp, it.reason, it.importance, it.processStateSummary?.decodeToString().orEmpty()) }
            for ((exit, reason) in exitsToRecord(exits, seen, crashEntries(dir).map { it.time })) {
                val info = infos.firstOrNull { it.timestamp == exit.time && it.reason == exit.reason } ?: continue
                runCatching { writeCrash(dir, exit.time, reason, "$about\nKind: ${kindOf(info.reason)}\nDoing: ${exit.doing.ifEmpty { "unknown" }}", details(info)) }
            }
        }
        prefs.edit().putLong("exitsSeen", maxOf(seen, infos.maxOfOrNull { it.timestamp } ?: 0L, if (seen < 0) System.currentTimeMillis() else 0L)).apply()
    }

    fun markShown(context: Context, time: Long) {
        val prefs = context.applicationContext.getSharedPreferences("crashlogs", Context.MODE_PRIVATE)
        prefs.edit().putLong("shown", maxOf(time, prefs.getLong("shown", 0L))).apply()
    }

    private fun kindOf(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android for low memory"
        else -> "Exit $reason"
    }

    @RequiresApi(30)
    private fun details(info: ApplicationExitInfo): String = buildString {
        append("Exit: ").append(kindOf(info.reason)).append(", importance ").append(info.importance).append('\n')
        info.description?.let { append("Description: ").append(it).append('\n') }
        if (info.pss > 0 || info.rss > 0) append("Memory: pss ").append(info.pss / 1024).append(" MB, rss ").append(info.rss / 1024).append(" MB\n")
        val trace = runCatching { info.traceInputStream?.use { it.readAtMost(4 * MaxDetail) } }.getOrNull()
        if (trace != null && trace.isNotEmpty()) {
            append('\n')
            append(if (info.reason == ApplicationExitInfo.REASON_ANR) trace.decodeToString().take(MaxDetail) else readableStrings(trace, MaxDetail))
        }
    }
}
