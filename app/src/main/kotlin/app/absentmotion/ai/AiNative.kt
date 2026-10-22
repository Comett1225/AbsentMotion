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

internal class AiGpu(val api: Int, val type: Int, val fp16: Boolean, val name: String) {
    val version: String get() = "${api ushr 22}.${(api ushr 12) and 0x3FF}"
    val software: Boolean get() = type == 3

    companion object {
        fun parse(line: String?): AiGpu? {
            val parts = line?.split('\t', limit = 5) ?: return null
            if (parts.size < 5) return null
            return AiGpu(parts[0].toLongOrNull()?.toInt() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2] == "1", parts[4])
        }
    }
}

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

internal class AiNet private constructor(private val handle: Long) : Closeable {
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
        check(code == 0) { AppText.get(if (code == -2) R.string.ai_out_of_memory_media else R.string.ai_model_failed) }
    }

    private suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.Default) {
        coroutineScope {
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { AiNative.nativeCancel(handle) } }
            try { work() } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
        }.also { ensureActive() }
    }

    override fun close() = AiNative.nativeClose(handle)

    companion object {
        const val NET_SCALE = 4
        const val TILE = 256
        const val OVERLAP = 10

        @Volatile var cpuOnly = false

        fun open(context: Context, model: AiModel, blend: AiModel? = null, weight: Float = 1f, gpu: Boolean = true): AiNet {
            check(AiNative.loaded) { AppText.get(R.string.ai_runtime_unavailable) }
            model.verify(context)
            blend?.verify(context)
            val param = model.file(context, checkNotNull(model.param)).path
            val handle = AiNative.nativeOpen(param, model.file(context, model.bin).path, blend?.let { it.file(context, it.bin).path }, weight, gpu && !cpuOnly)
            check(handle != 0L) { AppText.get(R.string.ai_model_failed) }
            return AiNet(handle)
        }
    }
}
