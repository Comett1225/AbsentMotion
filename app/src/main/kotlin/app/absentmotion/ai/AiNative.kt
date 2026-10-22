package app.absentmotion.ai

import android.content.Context
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

internal class AiGpu(val api: Int, val type: Int, val fp16: Boolean, val name: String, val pushDescriptor: Boolean = false) {
    val version: String get() = "${api ushr 22}.${(api ushr 12) and 0x3FF}"
    val software: Boolean get() = type == 3
    val known: Boolean get() = !software && api >= VULKAN_1_3 && fp16 && pushDescriptor && KnownGpus.any { it.containsMatchIn(name) }

    companion object {
        private const val VULKAN_1_3 = 0x403000
        private val KnownGpus = listOf(
            Regex("""^Adreno \(TM\) [78]\d\d\b"""),
            Regex("""^Mali-G(6[1-9]\d|[7-9]\d\d)\b"""),
        )

        fun parse(line: String?): AiGpu? {
            val parts = line?.split('\t', limit = 6) ?: return null
            if (parts.size < 6) return null
            return AiGpu(parts[0].toLongOrNull()?.toInt() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2] == "1", parts[5], parts[4] == "1")
        }
    }
}

internal fun aiUsesGpu(gpu: AiGpu?, choice: Boolean?): Boolean = gpu != null && !gpu.software && (choice ?: gpu.known)

internal object AiNative {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("absent_ai") }.isSuccess }
    val gpu: AiGpu? by lazy { if (loaded) runCatching { AiGpu.parse(nativeGpu()) }.getOrNull() else null }

    external fun nativeGpu(): String?
    external fun nativeCpuCores(): Int
    external fun nativeOpen(param: String, bin: String, blendBin: String?, weight: Float, useGpu: Boolean): Long
    external fun nativeOnGpu(handle: Long): Boolean
    external fun nativeCancel(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativeUpscale(handle: Long, input: IntArray, width: Int, height: Int, netScale: Int, outScale: Int, tile: Int, overlap: Int, output: IntArray): Int
    external fun nativeInterpolate(handle: Long, first: IntArray, second: IntArray, width: Int, height: Int, t: Float, padding: Int, output: IntArray): Int
}

internal class AiNet private constructor(private val handle: Long, private val gpuApp: Context?) : Closeable {
    val onGpu: Boolean get() = AiNative.nativeOnGpu(handle)

    suspend fun upscale(input: IntArray, width: Int, height: Int, outScale: Int, output: IntArray = IntArray(width * outScale * height * outScale)): IntArray {
        val code = blocking { AiNative.nativeUpscale(handle, input, width, height, NET_SCALE, outScale, TILE, OVERLAP, output) }
        checkResult(code)
        return output
    }

    suspend fun interpolate(first: IntArray, second: IntArray, width: Int, height: Int, t: Float, padding: Int, output: IntArray = IntArray(width * height)): IntArray {
        checkResult(blocking { AiNative.nativeInterpolate(handle, first, second, width, height, t, padding, output) })
        return output
    }

    private fun checkResult(code: Int) {
        check(code != 1) { AppText.get(R.string.ai_cancelled) }
        if (code == -3 && gpuApp != null) AiDevice.gpuFailed(gpuApp)
        check(code == 0) { AppText.get(if (code == -2) R.string.ai_out_of_memory_media else R.string.ai_model_failed) }
    }

    private suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.Default) {
        coroutineScope {
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { AiNative.nativeCancel(handle) } }
            try { work() } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
        }.also { ensureActive() }
    }

    override fun close() {
        AiNative.nativeClose(handle)
        if (gpuApp != null) AiDevice.gpuRunning(gpuApp, false)
    }

    companion object {
        const val NET_SCALE = 4
        const val TILE = 256
        const val OVERLAP = 10

        @Volatile var cpuOnly = false

        fun open(context: Context, model: AiModel, blend: AiModel? = null, weight: Float = 1f, gpu: Boolean = AiDevice.gpuProven(context, model)): AiNet {
            check(AiNative.loaded) { AppText.get(R.string.ai_runtime_unavailable) }
            model.verify(context)
            blend?.verify(context)
            val param = model.file(context, checkNotNull(model.param)).path
            val useGpu = gpu && !cpuOnly
            if (useGpu) AiDevice.gpuRunning(context, true)
            val bin = model.file(context, model.bin).path
            val other = blend?.let { it.file(context, it.bin).path }
            var handle = AiNative.nativeOpen(param, bin, other, weight, useGpu)
            val onGpu = handle != 0L && AiNative.nativeOnGpu(handle)
            if (useGpu && !onGpu) {
                if (handle == 0L) {
                    AiDevice.gpuFailed(context)
                    handle = AiNative.nativeOpen(param, bin, other, weight, false)
                }
                AiDevice.gpuRunning(context, false)
            }
            check(handle != 0L) { AppText.get(R.string.ai_model_failed) }
            return AiNet(handle, if (onGpu) context.applicationContext else null)
        }
    }
}
