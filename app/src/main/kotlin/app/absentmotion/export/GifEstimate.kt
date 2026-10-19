package app.absentmotion.export

import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun gifSampleEstimate(width: Int, height: Int, frames: Int, samples: List<ByteArray>): Long {
    require(samples.isNotEmpty())
    class Counter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(data: ByteArray, offset: Int, length: Int) { bytes += length }
    }
    var overhead = 0L
    val largest = samples.maxOf { pixels ->
        val out = Counter()
        val writer = GifWriter(out, width, height)
        overhead = out.bytes + 1
        writer.frame(pixels, 100)
        out.bytes - overhead + 1
    }
    return overhead + largest * frames
}

internal suspend fun measureGifBytes(engine: EngineThread, scene: Scene, settings: ExportSettings): Long {
    val (width, height) = exportSize(scene, settings.shortSide, false)
    val frames = frameCount(scene, settings.fps)
    return engine.exportSession(scene.toXml(), settings.interpolation, settings.fps) {
        val samples = listOf(0, frames / 2, frames - 1).distinct().map { index ->
            still(frameTimeMs(scene, settings.fps, index), width, height) ?: error("Cannot sample GIF size")
        }
        withContext(Dispatchers.Default) { gifSampleEstimate(width, height, frames, samples) }
    }
}
