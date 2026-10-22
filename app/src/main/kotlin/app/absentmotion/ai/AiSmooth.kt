package app.absentmotion.ai

import android.content.Context
import app.absentmotion.export.SmoothLevel
import app.absentmotion.model.Scene
import java.io.Closeable
import kotlin.math.min

internal val SmoothLevel.model: AiModel get() = when (this) {
    SmoothLevel.Low -> AiModel.SmoothLow
    SmoothLevel.Medium -> AiModel.SmoothMedium
    SmoothLevel.High -> AiModel.SmoothHigh
}

internal val SmoothLevel.padding: Int get() = if (this == SmoothLevel.Low) 128 else 64

class AiSmooth private constructor(private val net: AiNet, val level: SmoothLevel) : Closeable {
    val onGpu: Boolean get() = net.onGpu

    suspend fun between(first: IntArray, second: IntArray, width: Int, height: Int, t: Float, output: IntArray = IntArray(width * height)): IntArray {
        require(t > 0f && t < 1f && first.size >= width * height && second.size >= width * height)
        return net.interpolate(first, second, width, height, t, level.padding, output)
    }

    override fun close() = net.close()

    companion object {
        fun installed(context: Context, level: SmoothLevel): Boolean = level.model.installed(context)

        fun downloadBytes(level: SmoothLevel): Long = level.model.bytes

        suspend fun download(context: Context, level: SmoothLevel, progress: (Float) -> Unit) = level.model.download(context, progress)

        suspend fun open(context: Context, level: SmoothLevel): AiSmooth {
            AiDevice.probeGpu(context, level.model)
            return AiSmooth(AiNet.open(context, level.model), level)
        }

        internal fun open(context: Context, level: SmoothLevel, gpu: Boolean): AiSmooth = AiSmooth(AiNet.open(context, level.model, gpu = gpu), level)

        internal fun gmac(level: SmoothLevel, frames: Double, width: Int, height: Int): Double = level.model.gmacPerMegapixel * frames * width * height / 1e6

        internal fun exportGmac(scene: Scene, level: SmoothLevel, fps: Int, width: Int, height: Int): Double =
            scene.layers.filter { it.mediaFill?.video == true }.sumOf { layer ->
                val media = scene.media(layer.mediaFill!!.uri)
                val pixels = min(width.toDouble() * height, (media?.width ?: width).toDouble() * (media?.height ?: height))
                level.model.gmacPerMegapixel * (layer.endTime - layer.startTime).coerceAtLeast(0) / 1000.0 * fps * pixels / 1e6
            }
    }
}
