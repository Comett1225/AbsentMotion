package app.absentmotion.engine

import android.content.Context
import android.net.Uri
import app.absentmotion.ai.AiSmooth
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.Smoothing
import app.absentmotion.media.VideoSources
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.videoTimeUs
import app.absentmotion.pc.engine.FfmpegVideoSource
import java.io.Closeable

internal class DeskInterpolation(private val context: Context, private val smoothing: Smoothing, private val fps: Int, private val net: AiSmooth) : Closeable {
    private val readers = HashMap<String, FfmpegVideoSource>()
    private val stamps = HashMap<String, LongArray>()

    suspend fun apply(scene: Scene, timeMs: Int, engine: NativeEngine) {
        for (layer in scene.layers) {
            val fill = layer.mediaFill?.takeIf { it.video } ?: continue
            if (timeMs !in layer.startTime..layer.endTime) continue
            val file = Uri.parse(fill.uri).toFile()?.takeIf { it.isFile } ?: continue
            val times = stamps.getOrPut(fill.uri) { VideoSources.timestamps(context, fill.uri) }
            if (times.size < 2) continue
            val timing = VideoTiming(layer)
            val at = layer.videoTimeUs(timeMs, scene, timing).coerceAtLeast(0)
            val found = times.binarySearch(at)
            val index = if (found >= 0) found else -found - 2
            if (index < 0 || index + 1 >= times.size) continue
            val a = times[index]
            val b = times[index + 1]
            if (a < fill.inTime * 1000L || b >= fill.outTime.toLong() * 1000L || b <= a) continue
            val next = layer.videoTimeUs(timeMs + (1000.0 / fps.coerceAtLeast(1)).toInt().coerceAtLeast(1), scene, timing)
            if (b - a <= kotlin.math.abs(next - at)) continue
            val t = ((at - a).toDouble() / (b - a)).toFloat().coerceIn(0f, 1f)
            if (t <= 0.001f || t >= 0.999f) continue
            val reader = readers.getOrPut(fill.uri) { FfmpegVideoSource(file, DeskMedia.ffmpeg, DeskMedia.ffprobe, 8192, 16_000_000) }
            val first = reader.frame((a + 999) / 1000) ?: continue
            val width = first.width
            val height = first.height
            fun argb(bytes: ByteArray) = IntArray(width * height) { i ->
                ((bytes[4*i+3].toInt() and 255) shl 24) or ((bytes[4*i].toInt() and 255) shl 16) or
                    ((bytes[4*i+1].toInt() and 255) shl 8) or (bytes[4*i+2].toInt() and 255)
            }
            val before = argb(first.rgba)
            val second = reader.frame((b + 999) / 1000) ?: continue
            if (second.width != width || second.height != height) continue
            val after = argb(second.rgba)
            val result = net.between(before, after, width, height, t)
            if (smoothing.blur != app.absentmotion.export.MotionBlur.Off) {
                val strength = (smoothing.blur.shutter / 360f * smoothing.blurScale).coerceIn(0f, 1f)
                for (i in result.indices) {
                    var value = result[i] and -0x1000000
                    for (c in 0..2) {
                        val shift = c * 8
                        val centre = (result[i] shr shift) and 255
                        val sides = (((before[i] shr shift) and 255) + ((after[i] shr shift) and 255)) / 2f
                        value = value or (kotlin.math.round(centre * (1f - strength) + sides * strength).toInt().coerceIn(0, 255) shl shift)
                    }
                    result[i] = value
                }
            }
            val rgba = ByteArray(width * height * 4)
            for (i in result.indices) {
                rgba[4*i] = (result[i] shr 16).toByte(); rgba[4*i+1] = (result[i] shr 8).toByte()
                rgba[4*i+2] = result[i].toByte(); rgba[4*i+3] = (result[i] ushr 24).toByte()
            }
            check(engine.setFrame(fill.uri, width, height, rgba)) { app.absentmotion.i18n.AppText.get(app.absentmotion.R.string.ai_unable_to_process) }
        }
    }

    override fun close() { readers.values.forEach { it.close() }; net.close() }

    companion object {
        suspend fun open(context: Context, smoothing: Smoothing, fps: Int) = DeskInterpolation(context, smoothing, fps, AiSmooth.open(context, smoothing.level))
    }
}
