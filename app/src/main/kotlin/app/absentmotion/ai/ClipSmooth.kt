package app.absentmotion.ai

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import androidx.core.net.toUri
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.Mux
import app.absentmotion.export.MuxerSink
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.export.EncoderFeed
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.openVideoEncoder
import app.absentmotion.export.drain
import app.absentmotion.export.frameCount
import app.absentmotion.export.frameTimeMs
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MediaFill
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.fpsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import app.absentmotion.library.openableUri

internal const val MAX_SMOOTH_FRAMES = 20_000
private const val MAX_SMOOTH_STEPS = 8
private const val MAX_SMOOTH_SIDE = 1080

internal data class SmoothPlan(val fps: Int, val frames: Int, val width: Int, val height: Int, val seconds: Double, val blurScale: Float) {
    val bitrate: Int get() = (width.toLong() * height * fps / 5).coerceIn(2_000_000, 120_000_000).toInt()
    val bytes: Long get() = (bitrate / 8.0 * seconds).roundToLong() + 1_000_000
}

internal fun smoothPlan(layer: Layer, media: MediaDeclaration, projectFps: Int, smoothing: Smoothing): SmoothPlan? {
    if (layer.mediaFill?.video != true || media.width <= 0 || media.height <= 0) return null
    val source = media.fps ?: 30f
    val timing = VideoTiming(layer)
    val length = (layer.endTime - layer.startTime).coerceAtLeast(1)
    val slowest = (0..64).minOf { timing.speed(length * it / 64.0).toDouble() }.coerceAtLeast(0.01)
    val steps = if (smoothing.method == FrameInterpolation.Off) 1 else ceil(projectFps / slowest / source - 0.01).toInt().coerceIn(1, MAX_SMOOTH_STEPS)
    if (steps == 1 && smoothing.blur == MotionBlur.Off) return null
    val fps = (source * steps).roundToInt().coerceIn(1, 240)
    val (w, h) = media.width to media.height
    val k = min(1.0, MAX_SMOOTH_SIDE.toDouble() / min(w, h))
    fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
    val seconds = layer.matteSeconds(media)
    val mean = (timing.mediaMs(length.toDouble()) - timing.mediaMs(0.0)) / length
    return SmoothPlan(fps, (seconds * fps).toInt() + 1, even(w * k), even(h * k), seconds, (mean * fps / projectFps).toFloat())
}

internal fun smoothScene(fill: MediaFill, media: MediaDeclaration, plan: SmoothPlan): Scene {
    val span = (plan.seconds * 1000).toInt().coerceAtLeast(1)
    val clip = XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "$span", "fillType" to "media", "mediaFillMode" to "stretch",
        "s" to ".rect", "fillVideo" to fill.uri, "inTime" to "${fill.inTime}", "outTime" to "${fill.inTime + span}"), listOf(
        XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "${plan.width / 2f},${plan.height / 2f},0")))),
        XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "${plan.width / 2f},${plan.height / 2f}")),
    ))
    return Scene.create("smooth", plan.width, plan.height, plan.fps, 0xFF000000.toInt()).withMedia(media).add(Layer(clip))
}

internal fun Layer.smoothSource(previous: AiMark?): Layer =
    previous?.original?.let { copy(node = node.with("fillVideo", it)).shiftedMedia(previous.shift) } ?: this

internal fun Layer.withSmoothed(result: String, source: String, option: String, original: MediaDeclaration?, base: Layer): Layer =
    copy(node = node.with("fillVideo", result)).shiftedMedia(-(mediaFill?.inTime ?: 0))
        .withAiMark(AiKind.Smooth, source, result, option, original, base.mediaFill?.inTime ?: 0)

internal val Smoothing.option: String get() = "${method.name}:${level.name}:${blur.name}"

internal fun smoothingOf(option: String?): Smoothing? {
    val parts = option?.split(':') ?: return null
    return Smoothing(
        FrameInterpolation.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null,
        SmoothLevel.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return null,
        MotionBlur.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: return null,
    )
}

internal object ClipSmooth {
    suspend fun run(context: Context, engine: EngineThread, layer: Layer, media: MediaDeclaration, projectFps: Int, smoothing: Smoothing,
                    progress: AiProgress): AiMedia {
        val fill = checkNotNull(layer.mediaFill?.takeIf { it.video }) { AppText.get(R.string.ai_select_video_layer) }
        val plan = checkNotNull(smoothPlan(layer, media, projectFps, smoothing)) { AppText.get(R.string.smooth_nothing) }
        check(plan.seconds <= MAX_MATTE_SECONDS && plan.frames <= MAX_SMOOTH_FRAMES) { AppText.get(R.string.ai_clip_too_long, (MAX_MATTE_SECONDS / 60).toString()) }
        val file = aiFile(context, "mp4")
        check(file.parentFile!!.usableSpace >= plan.bytes * 2) { AppText.get(R.string.ai_not_enough_storage, (plan.bytes * 2 / 1_000_000).toString()) }
        val scene = smoothScene(fill, media, plan)
        val frames = minOf(plan.frames, frameCount(scene, plan.fps))
        val spanUs = (plan.seconds * 1_000_000).roundToLong()
        val kept = keepSound(context, fill.uri, fill.inTime * 1000L + spanUs)
        var sound: Pair<MediaExtractor, Int>? = null
        var success = false
        try {
            progress(0f, AppText.get(R.string.ai_preparing))
            sound = kept?.let { soundTrack(context, it.uri) }
            encode(engine, scene, plan, file.path, frames, fill.inTime * 1000L, spanUs, smoothing.copy(blurScale = plan.blurScale), sound) { done ->
                progress(0.98f * done / frames, AppText.get(R.string.smooth_frame, done.toString(), frames.toString()))
            }
            progress(0.99f, AppText.get(R.string.smooth_saving))
            return aiMedia(context, file, "video/mp4", mapOf("width" to "${plan.width}", "height" to "${plan.height}", "orientation" to "${media.orientation}",
                "duration" to "$spanUs", "fps" to fpsText(plan.fps.toFloat()))).also { success = true }
        } finally {
            sound?.first?.release()
            kept?.close()
            if (!success) file.delete()
        }
    }

    private fun soundTrack(context: Context, uri: String): Pair<MediaExtractor, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, openableUri(uri).toUri(), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null.also { extractor.release() }
            extractor.selectTrack(track)
            return extractor to track
        } catch (t: Throwable) {
            extractor.release()
            throw t
        }
    }

    private suspend fun encode(engine: EngineThread, scene: Scene, plan: SmoothPlan, path: String, frames: Int, fromUs: Long, spanUs: Long, smoothing: Smoothing,
                               sound: Pair<MediaExtractor, Int>?, done: suspend (Int) -> Unit) {
        val encoder = openVideoEncoder(ExportSettings(minOf(plan.width, plan.height), plan.fps), plan.width, plan.height, hot = false, bitrate = plan.bitrate)
        val failure = AtomicReference<Throwable>()
        val finished = AtomicBoolean()
        val mux = try { Mux(MuxerSink(path), if (sound != null) 2 else 1) { failure.compareAndSet(null, it); finished.set(true) } }
            catch (t: Throwable) { encoder.release(); throw t }
        var surface: Surface? = null
        var output: Thread? = null
        try {
            val audio = sound?.let { (extractor, track) -> mux.addTrack(extractor.getTrackFormat(track)) }
            val input = encoder.createInputSurface().also { surface = it }
            encoder.start()
            output = thread(name = "am-smooth-video") {
                try { drain(encoder, mux, finished, 0) } catch (t: Throwable) { failure.compareAndSet(null, t); finished.set(true) }
            }
            engine.exportSession(scene.toXml(), smoothing, plan.fps) {
                val writer = if (Build.VERSION.SDK_INT >= 33) ImageWriter.Builder(input).setMaxImages(2).setHardwareBufferFormat(HardwareBuffer.RGBA_8888)
                    .setUsage(HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).build() else null
                val feed = if (writer == null) EncoderFeed(input, plan.width, plan.height) else null
                try {
                    for (k in 0 until frames) {
                        currentCoroutineContext().ensureActive()
                        failure.get()?.let { throw it }
                        val pts = (k * 1_000_000.0 / plan.fps).roundToLong()
                        if (writer == null) {
                            check(feed!!.ready() && renderInto(feed.next, frameTimeMs(scene, plan.fps, k))) { AppText.get(R.string.ai_unable_to_process) }
                            feed.send(pts * 1000)
                        } else {
                            val image = writer.dequeueInputImage()
                            try {
                                check(image.hardwareBuffer!!.use { renderInto(it, frameTimeMs(scene, plan.fps, k)) }) { AppText.get(R.string.ai_unable_to_process) }
                                image.timestamp = pts * 1000
                                writer.queueInputImage(image)
                            } catch (t: Throwable) {
                                image.close()
                                throw t
                            }
                        }
                        done(k + 1)
                    }
                    check(feed?.flush() != false) { AppText.get(R.string.ai_unable_to_process) }
                    encoder.signalEndOfInputStream()
                } finally {
                    feed?.close()
                    releaseBuffers()
                    writer?.close()
                }
            }
            runInterruptible(Dispatchers.IO) { output.join() }
            failure.get()?.let { throw it }
            if (sound != null && audio != null) copySound(sound.first, mux, audio, fromUs, spanUs)
            failure.get()?.let { throw it }
            mux.finish()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                finished.set(true)
                output?.join()
                runCatching { encoder.stop() }
                encoder.release()
                surface?.release()
                mux.release()
            }
        }
    }

    private fun copySound(extractor: MediaExtractor, mux: Mux, track: Int, fromUs: Long, spanUs: Long) {
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val time = extractor.sampleTime
            if (time < 0 || time >= fromUs + spanUs) return
            if (time >= fromUs) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) return
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, time - fromUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.write(track, buffer, info)
            }
            extractor.advance()
        }
    }
}
