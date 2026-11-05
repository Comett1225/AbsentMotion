package app.absentmotion.desk

import android.content.Context
import android.widget.Toast
import app.absentmotion.R
import app.absentmotion.pc.engine.DesktopNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object DeskPreload {
    private const val IdleMs = 1200L

    suspend fun run(app: DeskAppState) {
        if (System.getProperty("am.preload") == "off") return
        delay(IdleMs)
        withContext(Dispatchers.IO) {
            runCatching { app.migration }
            runCatching { app.engine.startProblem.await() }
            runCatching { gpuNotice(app.context) }
            runCatching { dropOldArchives() }
        }
    }

    private fun gpuNotice(context: Context) {
        if (DesktopNative.gpuState() != 0 || System.getenv("AM_SOFTWARE_RENDER") != null) return
        val prefs = context.getSharedPreferences("desk_gpu", Context.MODE_PRIVATE)
        if (prefs.getBoolean("told", false)) return
        prefs.edit().putBoolean("told", true).apply()
        Toast.makeText(context, context.getString(R.string.desk_gpu_software), Toast.LENGTH_LONG).show()
    }

    private fun dropOldArchives() {
        val current = System.getProperty("am.cdsArchive")?.let(::File) ?: return
        current.parentFile?.listFiles { f -> f.name.startsWith("app-") && f.extension == "jsa" && f.name != current.name }?.forEach { it.delete() }
    }
}
