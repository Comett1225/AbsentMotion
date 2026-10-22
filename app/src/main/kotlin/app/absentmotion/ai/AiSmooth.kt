package app.absentmotion.ai

import android.content.Context
import androidx.annotation.StringRes
import app.absentmotion.R
import java.io.Closeable

enum class SmoothLevel(internal val model: AiModel, val padding: Int, @StringRes val title: Int) {
    Low(AiModel.SmoothLow, 128, R.string.settings_level_low),
    Medium(AiModel.SmoothMedium, 64, R.string.aibrowser_size_medium),
    High(AiModel.SmoothHigh, 64, R.string.settings_level_high),
}

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

        fun open(context: Context, level: SmoothLevel, gpu: Boolean = true): AiSmooth = AiSmooth(AiNet.open(context, level.model, gpu = gpu), level)
    }
}
