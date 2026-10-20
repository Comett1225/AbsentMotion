package app.absentmotion.export

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

internal suspend fun measureGif(engine: EngineThread, scene: Scene, settings: ExportSettings): GifSample {
    val (width, height) = exportSize(scene, settings.shortSide, false)
    val frames = frameCount(scene, settings.fps)
    return engine.exportSession(scene.toXml(), settings.interpolation, settings.fps) {
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
