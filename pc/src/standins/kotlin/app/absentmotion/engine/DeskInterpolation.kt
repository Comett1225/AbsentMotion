package app.absentmotion.engine

import android.content.Context
import android.net.Uri
import app.absentmotion.ai.AiSmooth
import app.absentmotion.desk.DeskMedia
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.media.VideoSources
import app.absentmotion.media.ptsAt
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.videoTimeUs
import app.absentmotion.model.pairAround
import app.absentmotion.model.needs
import app.absentmotion.model.localMs
import app.absentmotion.model.exportFrameMs
import app.absentmotion.model.moshPlan
import app.absentmotion.model.layerTracks
import app.absentmotion.pc.engine.FfmpegVideoSource
import java.io.Closeable

internal class DeskInterpolation(private val context: Context, private val smoothing: Smoothing, private val fps: Int, private var net: AiSmooth?) : Closeable {
    private val readers = HashMap<String, FfmpegVideoSource>()
    private val stamps = HashMap<String, LongArray>()
    private val variableRates = HashMap<String, Boolean>()

    suspend fun apply(scene: Scene, timeMs: Int, engine: NativeEngine) {
        if (fps <= 0) return
        for (track in scene.layerTracks()) {
            val layer = track.layer
            val childScene = track.scene
            val position = track.timeAt(exportFrameMs(timeMs, fps), true) ?: continue
            val fill = layer.mediaFill?.takeIf { it.video } ?: continue
            if (layer.moshPlan(position.ms, childScene) != null) continue
            val file = Uri.parse(fill.uri).toFile()?.takeIf { it.isFile } ?: continue
            val times = stamps.getOrPut(fill.uri) { VideoSources.timestamps(context, fill.uri) }
            if (times.size < 2) continue
            val timing = VideoTiming(layer)
            val mapped = position.ms
            val around = layer.pairAround(mapped, childScene, times) ?: continue
            val interpolate = smoothing.method != FrameInterpolation.Off && layer.needs(around, mapped, childScene, fps, position.rate)
            if (!interpolate && smoothing.blur == MotionBlur.Off && (smoothing.method == FrameInterpolation.Off || fps == childScene.fps && position.rate == 1.0)) continue
            val reader = readers.getOrPut(fill.uri) { FfmpegVideoSource(file, DeskMedia.ffmpeg, DeskMedia.ffprobe, 8192, 16_000_000) }
            val variable = variableRates.getOrPut(fill.uri) { times.variableRate() }
            fun read(stamp: Long) = if (variable) reader.frameExact(stamp) else reader.frame((stamp + 999) / 1000)
            if (!interpolate && smoothing.blur == MotionBlur.Off) {
                if (smoothing.method != FrameInterpolation.Off && (fps != childScene.fps || position.rate != 1.0)) {
                    val frame = checkNotNull(read(around.pair.a))
                    check(engine.setLayerFrame(fill.uri, layer.id, frame.width, frame.height, frame.rgba))
                }
                continue
            }
            val shown = track.timeAt(timeMs.toDouble(), true)?.ms?.toInt() ?: continue
            val a = if (smoothing.method == FrameInterpolation.Off) times.ptsAt(layer.videoTimeUs(shown, childScene, timing)) else around.pair.a
            val index = times.binarySearch(a).takeIf { it >= 0 } ?: continue
            val b = if (smoothing.method != FrameInterpolation.Off && around.pair.b != a) around.pair.b else times.getOrNull(index + 1) ?: continue
            if (b > fill.outTime.toLong() * 1000L - 500 || b <= a) continue
            var t = if (interpolate) around.pair.t else 0f
            if (smoothing.method == FrameInterpolation.Blend && smoothing.level == SmoothLevel.Low) t = ((t - 0.25f) * 2).coerceIn(0f, 1f)
            val first = read(a) ?: continue
            val width = first.width
            val height = first.height
            val before = first.rgba.copyOf()
            val second = read(b) ?: continue
            if (second.width != width || second.height != height) continue
            val after = second.rgba.copyOf()
            val blur = if (smoothing.blur == MotionBlur.Off) 0f else (smoothing.blur.shutter / 360f * (timing.speed(layer.localMs(mapped, childScene)) * position.rate * 1_000_000f / fps) / (b - a) * smoothing.blurScale).toFloat()
            if (interpolate && smoothing.method == FrameInterpolation.Ai && net != null && t > 0.001f && t < 0.999f) {
                fun argb(bytes: ByteArray) = IntArray(width * height) { i ->
                    ((bytes[4*i+3].toInt() and 255) shl 24) or ((bytes[4*i].toInt() and 255) shl 16) or
                        ((bytes[4*i+1].toInt() and 255) shl 8) or (bytes[4*i+2].toInt() and 255)
                }
                val result = try { net?.between(argb(before), argb(after), width, height, t) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { net?.close(); net = null; null }
                    catch (_: OutOfMemoryError) { net?.close(); net = null; null }
                if (result != null) {
                    val rgba = ByteArray(width * height * 4)
                    for (i in result.indices) {
                        rgba[4*i] = (result[i] shr 16).toByte(); rgba[4*i+1] = (result[i] shr 8).toByte()
                        rgba[4*i+2] = result[i].toByte(); rgba[4*i+3] = (result[i] ushr 24).toByte()
                    }
                    check(engine.interpolate(fill.uri, layer.id, width, height, before, after, null, null, rgba, t, 3, blur, smoothing.flowParams(floatArrayOf(4f, 5f, 6f, 1f, 0.15f, 4f))))
                    continue
                }
            }
            val soft = interpolate && smoothing.method == FrameInterpolation.Blend && smoothing.level == SmoothLevel.High
            fun extra(stamp: Long): ByteArray? = read(stamp)?.takeIf { it.width == width && it.height == height }?.rgba?.copyOf()
            val previous = if (soft) extra(times.getOrNull(index - 1)?.takeIf { it >= fill.inTime * 1000L - 500 } ?: a) else null
            val following = if (soft) extra(times.getOrNull(index + 2)?.takeIf { it < fill.outTime.toLong() * 1000L - 500 } ?: b) else null
            val mode = if (!interpolate) 0 else when (smoothing.method) { FrameInterpolation.Blend -> 1; else -> 2 }
            val params = smoothing.flowParams(floatArrayOf(4f, 5f, 6f, 1f, 0.15f, 4f))
            check(engine.interpolate(fill.uri, layer.id, width, height, before, after, previous, following, null, t, mode, blur, params))
        }
    }

    override fun close() { readers.values.forEach { it.close() }; net?.close() }

    companion object {
        suspend fun open(context: Context, smoothing: Smoothing, fps: Int): DeskInterpolation {
            val net = if (smoothing.method == FrameInterpolation.Ai) try { AiSmooth.open(context, smoothing.level) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
                catch (_: OutOfMemoryError) { null } else null
            return DeskInterpolation(context, smoothing, fps, net)
        }
    }
}
