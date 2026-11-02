package app.absentmotion.engine

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.media.VideoSources
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.moshPlan
import app.absentmotion.model.videoTimeUs
import app.absentmotion.pc.engine.FfmpegVideoSource
import java.io.Closeable

internal class DeskMosh(private val context: Context) : Closeable {
    private val readers = HashMap<String, FfmpegVideoSource>()
    private val stamps = HashMap<String, LongArray>()
    private val variableRates = HashMap<String, Boolean>()

    fun apply(scene: Scene, layer: Layer, timeMs: Int, engine: NativeEngine): Boolean {
        val plan = layer.moshPlan(timeMs.toDouble(), scene) ?: return false
        val uri = layer.mediaFill?.uri ?: return false
        val file = Uri.parse(uri).toFile()?.takeIf { it.isFile } ?: return false
        val times = stamps.getOrPut(uri) { VideoSources.timestamps(context, uri) }
        if (times.isEmpty()) return false
        val timing = VideoTiming(layer)
        val requests = plan.frames.map { layer.videoTimeUs(it, scene, timing).coerceAtLeast(0) }
        val pts = requests.map { times.ptsAt(it) }.toLongArray()
        val from = engine.moshStart(uri, layer.id, pts, plan.params)
        if (from < 0) return false
        val reader = readers.getOrPut(uri) { FfmpegVideoSource(file, DeskMedia.ffmpeg, DeskMedia.ffprobe, 1920, 4_000_000) }
        val variable = variableRates.getOrPut(uri) { times.variableRate() }
        for (step in from until pts.size) {
            val frame = (if (variable) reader.frameExact(pts[step]) else reader.frame((pts[step] + 999) / 1000)) ?: return false
            if (!engine.moshFrame(uri, layer.id, step, frame.width, frame.height, frame.rgba)) return false
        }
        return true
    }

    fun drop(uri: String) { readers.remove(uri)?.close(); stamps.remove(uri); variableRates.remove(uri) }

    override fun close() { readers.values.forEach { it.close() }; readers.clear(); stamps.clear(); variableRates.clear() }
}

internal fun LongArray.variableRate(): Boolean {
    var shortest = Long.MAX_VALUE
    var longest = 0L
    for (i in 1 until size) {
        val gap = this[i] - this[i - 1]
        if (gap > 0) { shortest = minOf(shortest, gap); longest = maxOf(longest, gap) }
    }
    return shortest != Long.MAX_VALUE && longest - shortest > maxOf(2000L, shortest / 20)
}
