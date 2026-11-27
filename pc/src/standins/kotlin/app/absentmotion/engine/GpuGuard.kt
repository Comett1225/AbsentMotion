package app.absentmotion.engine

import app.absentmotion.R
import app.absentmotion.pc.engine.DesktopNative
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object GpuGuard {
    const val PROBE = "--gpu-probe"
    @Volatile private var checked = false
    @Volatile private var pending: CompletableFuture<Boolean?>? = null

    fun softwareFor(dir: File, version: String): Pair<Boolean, Boolean> {
        val starting = File(dir, "gpu-starting")
        val software = File(dir, "gpu-software")
        val crashed = starting.isFile
        if (crashed) runCatching { software.writeText(version) }
        val cpu = crashed || runCatching { software.readText().trim() == version }.getOrDefault(false)
        return cpu to crashed
    }

    fun probed(dir: File, version: String): Boolean = runCatching { File(dir, "gpu-probe").readText().trim() == version }.getOrDefault(false)

    fun record(dir: File, version: String, ok: Boolean) {
        runCatching {
            dir.mkdirs()
            if (!ok) File(dir, "gpu-software").writeText(version)
            File(dir, "gpu-probe").writeText(version)
        }
    }

    fun command(): List<String>? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        val name = File(command).name.lowercase().removeSuffix(".exe")
        if (name != "java" && name != "javaw") return listOf(command, PROBE)
        val classes = System.getProperty("java.class.path")?.takeIf { it.isNotEmpty() } ?: return null
        val options = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-D") }
        return listOf(command) + options + listOf("-cp", classes, "app.absentmotion.pc.MainKt", PROBE)
    }

    fun probe(command: List<String>, dir: File, timeoutMs: Long, environment: Map<String, String> = emptyMap()): Boolean? {
        val process = try {
            ProcessBuilder(command).directory(dir.takeIf { it.isDirectory }).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().putAll(environment) }.start()
        } catch (e: Exception) {
            return null
        }
        process.outputStream.close()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    fun start() {
        val desk = app.absentmotion.desk.DeskOs
        if (desk.mac || pending != null) return
        val dir = android.content.Desk.dataDir
        val version = app.absentmotion.desk.DeskInfo.version
        if (probed(dir, version) || System.getenv("AM_SOFTWARE_RENDER") != null) return
        val command = command() ?: return
        pending = CompletableFuture.supplyAsync {
            DesktopNative.gpuState()
            probe(command, dir, 8000)?.also { record(dir, version, it) }
        }
    }

    fun probeChild() {
        try {
            if (System.getenv("AM_GPU_PROBE_CRASH") == "1") {
                val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
                (field.get(null) as sun.misc.Unsafe).getLong(0L)
                Runtime.getRuntime().halt(134)
            }
            DesktopNative.create()
            println("gpu ${DesktopNative.gpuState()}")
            System.out.flush()
            Runtime.getRuntime().halt(0)
        } catch (t: Throwable) {
            Runtime.getRuntime().halt(3)
        }
    }

    @Synchronized fun create(): Long {
        if (checked) return DesktopNative.create()
        checked = true
        val probe = pending?.let { future -> runCatching { future.get() }.getOrElse { e -> (e as? java.util.concurrent.ExecutionException)?.cause?.let { throw it }; null } }
        val dir = android.content.Desk.dataDir
        val starting = File(dir, "gpu-starting")
        val (cpu, crashed) = softwareFor(dir, app.absentmotion.desk.DeskInfo.version)
        if (cpu) {
            DesktopNative.useSoftware()
            runCatching { starting.delete() }
            if (crashed || probe == false) runCatching { app.absentmotion.desk.DeskNotify.show("AbsentMotion", app.absentmotion.i18n.AppText.get(R.string.gpu_software_fallback)) }
            return DesktopNative.create()
        }
        runCatching { dir.mkdirs(); starting.writeText("1") }
        return DesktopNative.create().also { runCatching { starting.delete() } }
    }
}
