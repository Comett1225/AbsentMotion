package app.absentmotion.ai

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
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
import app.absentmotion.export.drain
import app.absentmotion.export.frameCount
import app.absentmotion.export.frameTimeMs
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
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

internal const val MAX_SMOOTH_FRAMES = 20_000
private const val MAX_SMOOTH_STEPS = 8
private const val MAX_SMOOTH_SIDE = 1080
private val KeptSound = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB, MediaFormat.MIMETYPE_AUDIO_OPUS)

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
        val inMs = fill.inTime
        val span = (plan.seconds * 1000).toInt().coerceAtLeast(1)
        val clip = XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "$span", "fillType" to "media", "mediaFillMode" to "stretch",
            "s" to ".rect", "fillVideo" to fill.uri, "inTime" to "$inMs", "outTime" to "${inMs + span}"), listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "${plan.width / 2f},${plan.height / 2f},0")))),
            XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "${plan.width / 2f},${plan.height / 2f}")),
        ))
        val scene = Scene.create("smooth", plan.width, plan.height, plan.fps, 0xFF000000.toInt()).withMedia(media).add(Layer(clip))
        val frames = minOf(plan.frames, frameCount(scene, plan.fps))
        val sound = soundTrack(context, fill.uri)
        var success = false
        try {
            progress(0f, AppText.get(R.string.ai_preparing))
            encode(engine, scene, plan, file.path, frames, inMs, smoothing.copy(blurScale = plan.blurScale), sound) { done ->
                progress(0.98f * done / frames, AppText.get(R.string.smooth_frame, done.toString(), frames.toString()))
            }
            progress(0.99f, AppText.get(R.string.smooth_saving))
            return aiMedia(context, file, "video/mp4", mapOf("width" to "${plan.width}", "height" to "${plan.height}", "orientation" to "${media.orientation}",
                "duration" to "${media.duration}", "fps" to fpsText(plan.fps.toFloat()))).also { success = true }
        } finally {
            sound?.first?.release()
            if (!success) file.delete()
        }
    }

    private fun soundTrack(context: Context, uri: String): Pair<MediaExtractor, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri.toUri(), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null.also { extractor.release() }
            check(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) in KeptSound) { AppText.get(R.string.smooth_sound_format) }
            extractor.selectTrack(track)
            return extractor to track
        } catch (t: Throwable) {
            extractor.release()
            throw t
        }
    }

    private suspend fun encode(engine: EngineThread, scene: Scene, plan: SmoothPlan, path: String, frames: Int, inMs: Int, smoothing: Smoothing,
                               sound: Pair<MediaExtractor, Int>?, done: suspend (Int) -> Unit) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && mime in it.supportedTypes }
            .sortedByDescending { it.isHardwareAccelerated }.firstOrNull { it.getCapabilitiesForType(mime).videoCapabilities!!.isSizeSupported(plan.width, plan.height) }
            ?: error(AppText.get(R.string.export_error_cannot_encode, "H.264/AVC", "${plan.width}x${plan.height}"))
        val caps = info.getCapabilitiesForType(mime)
        val format = MediaFormat.createVideoFormat(mime, plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate.coerceIn(caps.videoCapabilities!!.bitrateRange.lower, caps.videoCapabilities!!.bitrateRange.upper))
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            if (caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val encoder = MediaCodec.createByCodecName(info.name)
        val failure = AtomicReference<Throwable>()
        val finished = AtomicBoolean()
        val mux = try { Mux(MuxerSink(path), if (sound != null) 2 else 1) { failure.compareAndSet(null, it); finished.set(true) } }
            catch (t: Throwable) { encoder.release(); throw t }
        var surface: Surface? = null
        var output: Thread? = null
        try {
            val audio = sound?.let { (extractor, track) -> mux.addTrack(extractor.getTrackFormat(track)) }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface().also { surface = it }
            encoder.start()
            output = thread(name = "am-smooth-video") {
                try { drain(encoder, mux, finished, 0) } catch (t: Throwable) { failure.compareAndSet(null, t); finished.set(true) }
            }
            engine.exportSession(scene.toXml(), smoothing, plan.fps) {
                val writer = ImageWriter.Builder(input).setMaxImages(2).setHardwareBufferFormat(HardwareBuffer.RGBA_8888)
                    .setUsage(HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).build()
                try {
                    for (k in 0 until frames) {
                        currentCoroutineContext().ensureActive()
                        failure.get()?.let { throw it }
                        val pts = inMs * 1000L + (k * 1_000_000.0 / plan.fps).roundToLong()
                        for (at in if (k == 0 && inMs > 0) listOf(0L, pts) else listOf(pts)) {
                            val image = writer.dequeueInputImage()
                            try {
                                check(image.hardwareBuffer!!.use { renderInto(it, frameTimeMs(scene, plan.fps, k)) }) { AppText.get(R.string.ai_unable_to_process) }
                                image.timestamp = at * 1000
                                writer.queueInputImage(image)
                            } catch (t: Throwable) {
                                image.close()
                                throw t
                            }
                        }
                        done(k + 1)
                    }
                    encoder.signalEndOfInputStream()
                } finally {
                    releaseBuffers()
                    writer.close()
                }
            }
            runInterruptible(Dispatchers.IO) { output.join() }
            failure.get()?.let { throw it }
            if (sound != null && audio != null) copySound(sound.first, mux, audio)
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

    private fun copySound(extractor: MediaExtractor, mux: Mux, track: Int) {
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return
            val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            info.set(0, size, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.write(track, buffer, info)
            extractor.advance()
        }
    }
}
