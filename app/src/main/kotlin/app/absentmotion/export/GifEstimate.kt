package app.absentmotion.export

import android.hardware.HardwareBuffer
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import java.io.OutputStream
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GifSample(val bytes: Long, val encodeMs: List<Double>, val renderMs: List<Double> = emptyList())

internal fun gifSampleEstimate(width: Int, height: Int, frames: Int, samples: List<ByteArray>): GifSample {
    require(samples.isNotEmpty())
    class Counter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(data: ByteArray, offset: Int, length: Int) { bytes += length }
    }
    var overhead = 0L
    val encodeMs = ArrayList<Double>()
    val sizes = samples.map { pixels ->
        val out = Counter()
        val started = System.nanoTime()
        val writer = GifWriter(out, width, height)
        overhead = out.bytes + 1
        writer.frame(pixels, 100)
        encodeMs += (System.nanoTime() - started) / 1e6
        out.bytes - overhead + 1
    }
    return GifSample(overhead + sizes.sum() * frames / sizes.size, encodeMs)
}

internal fun gifTimeMs(frames: Int, sample: GifSample, renderMs: Long?): Long {
    fun median(values: List<Double>) = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    val render = renderMs ?: (DefaultSetupMs + frames * median(sample.renderMs)).roundToLong()
    return render + (frames * median(sample.encodeMs)).roundToLong()
}

internal fun drawSamples(frames: Int, interpolation: FrameInterpolation): List<Int> {
    val last = (frames - 1).coerceAtLeast(0)
    val picks = if (interpolation == FrameInterpolation.Off) listOf(0, 0, frames / 3, frames * 2 / 3)
        else listOf(0, 1, frames / 3, frames / 3 + 1, frames * 2 / 3, frames * 2 / 3 + 1)
    return picks.map { it.coerceIn(0, last) }
}

internal suspend fun measureVideoDraw(engine: EngineThread, scene: Scene, settings: ExportSettings): Double {
    val (width, height) = exportSize(scene, settings.shortSide, even = true)
    val frames = frameCount(scene, settings.fps)
    val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    try {
        return engine.exportSession(scene.toXml(), settings.smoothing, settings.fps) {
            try {
                val times = drawSamples(frames, settings.interpolation).map { index ->
                    val started = System.nanoTime()
                    check(renderInto(buffer, frameTimeMs(scene, settings.fps, index))) { "Cannot sample the drawing time" }
                    (System.nanoTime() - started) / 1e6
                }
                if (settings.interpolation == FrameInterpolation.Off) times.drop(1).sorted()[1] else times.drop(2).average()
            } finally {
                releaseBuffers()
            }
        }
    } finally {
        buffer.close()
    }
}

internal suspend fun measureGif(engine: EngineThread, scene: Scene, settings: ExportSettings): GifSample {
    val (width, height) = exportSize(scene, settings.shortSide, false)
    val frames = frameCount(scene, settings.fps)
    return engine.exportSession(scene.toXml(), settings.smoothing, settings.fps) {
        val renderMs = ArrayList<Double>()
        val samples = listOf(0, frames / 4, frames / 2, frames * 3 / 4, frames - 1).distinct().map { index ->
            val started = System.nanoTime()
            (still(frameTimeMs(scene, settings.fps, index), width, height) ?: error("Cannot sample GIF size")).also {
                renderMs += (System.nanoTime() - started) / 1e6
            }
        }
        withContext(Dispatchers.Default) { gifSampleEstimate(width, height, frames, samples) }.copy(renderMs = renderMs)
    }
}
