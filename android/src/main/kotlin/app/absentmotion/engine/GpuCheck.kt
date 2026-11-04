package app.absentmotion.engine

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import app.absentmotion.BuildConfig

internal class GpuCheck(private val context: Context) {
    private val prefs = context.getSharedPreferences("gpu_check", Context.MODE_PRIVATE)
    private var backend: String? = null
    private var away = false
    private val shown = HashSet<Activity>()
    private val app = context.applicationContext as? Application
    private val watcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { shown += activity; seen(away = false) }
        override fun onActivityStopped(activity: Activity) { shown -= activity; if (shown.isEmpty() && !activity.isChangingConfigurations) seen(away = true) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        app?.registerActivityLifecycleCallbacks(watcher)
        clearOnCrash(prefs)
    }

    private fun key(gl: Boolean) = "${Build.FINGERPRINT}|${BuildConfig.VERSION_CODE}|${name(gl)}"

    fun state(gl: Boolean): String? = prefs.getString(key(gl), null)

    fun knownBad(gl: Boolean): Boolean = bad(state(gl))

    fun recover() {
        val record = prefs.getString(TRYING, null) ?: return
        val pid = record.substringBefore(':').toIntOrNull()
        if (pid == Process.myPid()) return
        val (badGl, strike) = judge(record, pid?.let(::exitReason), prefs.getString(STRIKE, null))
        val edit = prefs.edit().remove(TRYING)
        if (strike == null) edit.remove(STRIKE) else edit.putString(STRIKE, strike)
        badGl?.let { gl ->
            edit.putString(key(gl), CRASHED)
            Log.w("AMEngine", "the app closed while ${if (gl) "OpenGL" else "Vulkan"} was starting, so it is skipped from now on")
        }
        edit.commit()
    }

    @Synchronized fun trying(gl: Boolean) {
        backend = name(gl)
        write()
    }

    @Synchronized fun survived() {
        if (backend == null) return
        backend = null
        prefs.edit().remove(TRYING).remove(STRIKE).apply()
    }

    fun close() {
        app?.unregisterActivityLifecycleCallbacks(watcher)
        survived()
    }

    @Synchronized private fun seen(away: Boolean) {
        if (this.away == away) return
        this.away = away
        if (backend != null) write()
    }

    private fun write() { prefs.edit().putString(TRYING, "${Process.myPid()}:$backend${if (away) AWAY else ""}").commit() }

    private fun exitReason(pid: Int): Int? = if (Build.VERSION.SDK_INT < 30) null else runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()?.reason
    }.getOrNull()

    fun drawsRight(engine: NativeEngine): Boolean {
        val gl = engine.openGl
        prefs.getString(key(gl), null)?.let { return it == "ok" }
        val good = EngineDebug.failCheck?.let { it != (if (gl) "gl" else "vk") } ?: render(engine)
        prefs.edit().putString(key(gl), if (good) "ok" else "bad").apply()
        if (!good) Log.w("AMEngine", "the ${if (gl) "OpenGL" else "Vulkan"} check picture came out wrong")
        return good
    }

    private fun render(engine: NativeEngine): Boolean = try {
        engine.setScene(SCENE)
        engine.setPicture(URI, quad())
        val px = engine.previewStill(0.0, SIZE, SIZE, false)
        px == null || px.size < SIZE * SIZE * 4 || matches(px)
    } catch (e: Exception) {
        Log.w("AMEngine", "GPU check could not run", e)
        true
    } finally {
        runCatching { engine.setPicture(URI, null) }
        runCatching { engine.setScene(EMPTY) }
    }

    internal data class Judged(val badGl: Boolean?, val strike: String?)

    companion object {
        private val TRYING = "trying.${BuildConfig.VERSION_CODE}"
        private val STRIKE = "strike.${BuildConfig.VERSION_CODE}"
        internal const val CRASHED = "crashed"
        private const val AWAY = "~"
        private val crashHandler = java.util.concurrent.atomic.AtomicBoolean()

        private fun name(gl: Boolean) = if (gl) "gl" else "vk"

        private fun clearOnCrash(prefs: SharedPreferences) {
            if (!crashHandler.compareAndSet(false, true)) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { if (prefs.getString(TRYING, null)?.substringBefore(':') == "${Process.myPid()}") prefs.edit().remove(TRYING).commit() }
                previous?.uncaughtException(thread, error)
            }
        }

        private fun bad(state: String?) = state == "bad" || state == CRASHED

        internal const val BOTH_CLOSED = "Vulkan closed the app while starting; OpenGL: closed the app while starting"

        internal fun startOrder(preferGl: Boolean, gl: String?, vk: String?, risky: () -> Boolean): List<Boolean> {
            if (gl == CRASHED && vk == CRASHED) return emptyList()
            val glFirst = gl != CRASHED && (preferGl || vk == CRASHED || !bad(gl) && (bad(vk) || risky()))
            return if ((if (glFirst) vk else gl) == CRASHED) listOf(glFirst) else listOf(glFirst, !glFirst)
        }

        internal fun judge(record: String, reason: Int?, strike: String?): Judged {
            val backend = record.substringAfter(':', "")
            if (record.substringBefore(':').toIntOrNull() == null || backend.endsWith(AWAY) || backend.isEmpty()) return Judged(null, null)
            return when (reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> Judged(backend == "gl", null)
                null, ApplicationExitInfo.REASON_ANR -> if (strike == backend) Judged(backend == "gl", null) else Judged(null, backend)
                else -> Judged(null, null)
            }
        }

        private const val URI = "absent-check://quad"
        private const val SIZE = 64
        private const val EMPTY = """<scene title="check" width="$SIZE" height="$SIZE" exportWidth="$SIZE" exportHeight="$SIZE" fps="30" totalTime="1000" bgcolor="#ff000000"></scene>"""
        private const val SCENE = """<scene title="check" width="$SIZE" height="$SIZE" exportWidth="$SIZE" exportHeight="$SIZE" fps="30" totalTime="1000" bgcolor="#ff000000"><shape id="1" label="p" startTime="0" endTime="1000" fillType="media" mediaFillMode="fill" s=".rect" fillImage="$URI"><transform><location value="32,32,0"/></transform><property name="size" type="vec2" value="32,32"/></shape><shape id="2" label="c" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="32,56,0"/><opacity value="0.5"/></transform><property name="size" type="vec2" value="32,8"/><fillColor value="#ffffffff"/></shape></scene>"""

        private fun quad(): Bitmap {
            val colors = IntArray(SIZE * SIZE) { i ->
                val x = i % SIZE
                val y = i / SIZE
                when {
                    y < SIZE / 2 && x < SIZE / 2 -> 0xffff0000.toInt()
                    y < SIZE / 2 -> 0xff00ff00.toInt()
                    x < SIZE / 2 -> 0xff0000ff.toInt()
                    else -> 0xff000000.toInt()
                }
            }
            return Bitmap.createBitmap(colors, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        }

        private const val ARM = 0x13B5
        private const val IMAGINATION = 0x1010
        private val SlowMali = Regex("""^Mali-(G31|G51|G52|T\d+)\b""")
        private val NewPowerVr = Regex("""^PowerVR [D-Z]-Series""")

        internal fun vulkanRisky(device: String?): Boolean {
            val vendor = device?.substringBefore('\t')?.toIntOrNull() ?: return false
            val name = device.substringAfter('\t')
            return when (vendor) {
                ARM -> SlowMali.containsMatchIn(name)
                IMAGINATION -> !NewPowerVr.containsMatchIn(name)
                else -> false
            }
        }

        internal fun matches(px: ByteArray): Boolean {
            fun at(x: Int, y: Int) = IntArray(3) { c -> px[(y * SIZE + x) * 4 + c].toInt() and 0xff }
            fun near(x: Int, y: Int, vararg want: IntRange) = at(x, y).zip(want).all { (v, r) -> v in r }
            val hi = 200..255
            val lo = 0..55
            val mid = 90..215
            return near(16, 16, hi, lo, lo) && near(48, 16, lo, hi, lo) && near(16, 40, lo, lo, hi) && near(48, 40, lo, lo, lo) &&
                near(16, 58, mid, mid, hi) && near(48, 58, mid, mid, mid)
        }
    }
}
