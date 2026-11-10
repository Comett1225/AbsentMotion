package app.absentmotion.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import app.absentmotion.BuildConfig
import java.io.File

internal object VulkanProbe {
    private const val TRYING = "trying"
    private const val OK = "ok"

    private fun file(context: Context) = File(context.filesDir, "vulkan_probe_${BuildConfig.VERSION_CODE}.txt")

    private fun read(context: Context): String? = runCatching { file(context).readLines() }.getOrNull()
        ?.takeIf { it.size == 2 && it[0] == Build.FINGERPRINT }?.get(1)

    fun proven(context: Context): Boolean = read(context) == OK

    fun startOnce(context: Context) {
        if (read(context) != null) return
        runCatching { context.startService(Intent(context, VulkanProbeService::class.java)) }
    }

    internal fun run(context: Context) {
        val out = file(context)
        if (read(context) != null) return
        out.writeText("${Build.FINGERPRINT}\n$TRYING")
        val good = runCatching {
            if (GpuCheck.vulkanRisky(NativeEngine.vulkanDevice())) false
            else NativeEngine(NativeEngine.Backend.GRAPHITE).use { engine -> !engine.openGl && GpuCheck.render(engine) == true }
        }.getOrDefault(false)
        out.writeText("${Build.FINGERPRINT}\n${if (good) OK else "bad"}")
    }
}

class VulkanProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({
            runCatching { VulkanProbe.run(applicationContext) }
            stopSelf(startId)
            Process.killProcess(Process.myPid())
        }, "vulkan-probe").start()
        return START_NOT_STICKY
    }
}
