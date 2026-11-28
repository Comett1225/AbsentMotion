package app.absentmotion.library

import app.absentmotion.platform.versionCodeLong
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

private class ProtoField(val num: Int, val value: Long, val bytes: ByteArray?) {
    val text: String get() = bytes?.decodeToString().orEmpty()
}

private fun protoFields(b: ByteArray): List<ProtoField> {
    val out = ArrayList<ProtoField>()
    var i = 0
    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val x = b[i++].toInt() and 255
            r = r or ((x and 127).toLong() shl shift)
            if (x < 128) return r
            shift += 7
            require(shift < 64)
        }
    }
    while (i < b.size) {
        val key = varint()
        val num = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> out += ProtoField(num, varint(), null)
            1 -> i += 8
            2 -> {
                val n = varint().toInt()
                if (n < 0 || i + n > b.size) break
                out += ProtoField(num, 0, b.copyOfRange(i, i + n))
                i += n
            }
            5 -> i += 4
            else -> error("not protobuf")
        }
    }
    return out
}

internal fun tombstoneText(bytes: ByteArray, maxChars: Int): String? = runCatching {
    val top = protoFields(bytes)
    val crashed = top.lastOrNull { it.num == 6 && it.bytes == null }?.value?.toInt()
    val threads = top.filter { it.num == 16 && it.bytes != null }.mapNotNull { entry -> protoFields(entry.bytes!!).firstOrNull { it.num == 2 }?.bytes?.let(::protoFields) }
    if (crashed == null || threads.isEmpty()) return null
    fun id(t: List<ProtoField>) = t.firstOrNull { it.num == 1 && it.bytes == null }?.value?.toInt()
    fun frames(t: List<ProtoField>, limit: Int, withBuild: Boolean) = buildString {
        t.filter { it.num == 4 && it.bytes != null }.take(limit).forEachIndexed { n, frame ->
            val f = protoFields(frame.bytes!!)
            fun str(k: Int) = f.firstOrNull { it.num == k && it.bytes != null }?.text.orEmpty()
            val pc = f.firstOrNull { it.num == 1 && it.bytes == null }?.value ?: 0L
            append("  #").append(n.toString().padStart(2, '0')).append(" pc 0x").append(pc.toString(16)).append(' ').append(str(6).substringAfterLast('/').ifEmpty { "<unknown>" })
            if (str(4).isNotEmpty()) append(" (").append(str(4)).append('+').append(f.firstOrNull { it.num == 5 && it.bytes == null }?.value ?: 0).append(')')
            if (withBuild && str(8).isNotEmpty()) append(" (BuildId: ").append(str(8)).append(')')
            append('\n')
        }
    }
    fun name(t: List<ProtoField>) = t.firstOrNull { it.num == 2 && it.bytes != null }?.text.orEmpty()
    buildString {
        top.firstOrNull { it.num == 10 && it.bytes != null }?.let { protoFields(it.bytes!!) }?.let { s ->
            fun str(k: Int) = s.firstOrNull { it.num == k && it.bytes != null }?.text.orEmpty()
            append(str(2)).append(" (").append(str(4)).append(')')
            if (s.any { it.num == 8 && it.value != 0L }) append(", fault address 0x").append((s.firstOrNull { it.num == 9 && it.bytes == null }?.value ?: 0L).toULong().toString(16))
            append('\n')
        }
        top.filter { it.num == 15 && it.bytes != null }.forEach { c -> protoFields(c.bytes!!).firstOrNull { it.num == 1 && it.bytes != null }?.let { append("Cause: ").append(it.text).append('\n') } }
        top.firstOrNull { it.num == 14 && it.bytes != null }?.text?.takeIf { it.isNotBlank() }?.let { append("Abort message: ").append(it).append('\n') }
        val first = threads.firstOrNull { id(it) == crashed }
        if (first != null) append("\nCrashed thread ").append(crashed).append(' ').append(name(first)).append(":\n").append(frames(first, 64, true))
        for (t in threads) if (t !== first) append("\nThread ").append(id(t)).append(' ').append(name(t)).append(":\n").append(frames(t, 12, false))
    }.take(maxChars)
}.getOrNull()

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

private val ProviderAddress = Regex("""(content://[^/\s"'<>]+/)[^\s"'<>]+""")
private val FileAddress = Regex("""(file:)/[^\s"'<>]+""")
private val UserPath = Regex("""(/(?:storage|sdcard|mnt|data/user/\d+|data/data|data/media)/)[^\n"'<>]*?(?=: |\s\(|["'<>]|$)""", RegexOption.MULTILINE)

internal fun redactPaths(text: String): String {
    fun hidden(match: MatchResult): String {
        val ext = match.value.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        return match.groupValues[1] + "…" + (ext?.let { ".$it" } ?: "")
    }
    return listOf(ProviderAddress, FileAddress, UserPath).fold(text) { acc, regex -> regex.replace(acc, ::hidden) }
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
    private class Running(val name: String)
    private val running = ArrayList<Running>()
    private val task: String? get() = synchronized(running) { running.lastOrNull()?.name }
    @Volatile private var failedDuring: Pair<Throwable, String>? = null

    val doing: String get() = task ?: screen

    fun install(context: Context) {
        if (folder != null) return
        val app = context.applicationContext
        activities = app.getSystemService(ActivityManager::class.java)
        val code = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionCodeLong }.getOrDefault(0L)
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
        val mine = Running(name)
        synchronized(running) { running += mine }
        publish()
        try {
            return block()
        } catch (t: Throwable) {
            failedDuring = t to name
            throw t
        } finally {
            synchronized(running) { running.remove(mine) }
            publish()
        }
    }

    private fun publish() {
        if (Build.VERSION.SDK_INT >= 30) runCatching { activities?.setProcessStateSummary(doing.take(100).encodeToByteArray()) }
    }

    fun list(context: Context): List<CrashEntry> = crashEntries(File(context.filesDir, "crashlogs"))

    fun text(entry: CrashEntry): String = runCatching { redactPaths(entry.file.readText()) }.getOrDefault("")

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
        val infos = runCatching { app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 0) }.getOrDefault(emptyList()).filter { it.processName == app.packageName }
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
            append(when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> trace.decodeToString().take(MaxDetail)
                ApplicationExitInfo.REASON_CRASH_NATIVE -> tombstoneText(trace, MaxDetail) ?: readableStrings(trace, MaxDetail)
                else -> readableStrings(trace, MaxDetail)
            })
        }
    }
}
