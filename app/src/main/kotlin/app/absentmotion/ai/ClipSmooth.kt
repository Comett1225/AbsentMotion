package app.absentmotion.ai

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import app.absentmotion.R
import app.absentmotion.editor.EditorState
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
import app.absentmotion.model.mediaLengthMs
import app.absentmotion.model.mediaUris
import app.absentmotion.model.trimmedTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
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
    return SmoothPlan(fps, (seconds * fps).toInt() + 1, even(w * k), even(h * k), seconds, (layer.meanSpeed() * fps / projectFps).toFloat())
}

internal fun Layer.meanSpeed(): Double {
    val timing = VideoTiming(this)
    val length = (endTime - startTime).coerceAtLeast(1)
    return (timing.mediaMs(length.toDouble()) - timing.mediaMs(0.0)) / length
}

internal fun Layer.speedSignature(projectFps: Int): Int = "${node["speed"]}|${node.child("speedMap")?.toXml()}|$projectFps".hashCode()

internal fun smoothFacts(base: Layer, plan: SmoothPlan, projectFps: Int): Map<String, String> = linkedMapOf(
    "from" to "${base.mediaFill?.inTime ?: 0}", "span" to "${(plan.seconds * 1000).toInt()}", "fps" to "${plan.fps}",
    "blur" to "${plan.blurScale}", "mean" to "${base.meanSpeed()}", "sig" to "${base.speedSignature(projectFps)}",
)

internal class SmoothFacts(val from: Int, val span: Int, val fps: Int, val blur: Float, val mean: Double, val sig: Int)

internal val AiMark.facts: SmoothFacts?
    get() = SmoothFacts(
        node["from"]?.toIntOrNull() ?: return null, node["span"]?.toIntOrNull() ?: return null, node["fps"]?.toIntOrNull() ?: return null,
        node["blur"]?.toFloatOrNull() ?: return null, node["mean"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null, node["sig"]?.toIntOrNull() ?: return null,
    )

internal class SmoothChange(val id: Long, val node: XmlNode, val amountMs: Double, val small: Boolean)

internal fun Scene.smoothChange(layer: Layer): SmoothChange? {
    val mark = layer.aiMarks.firstOrNull { it.kind == AiKind.Smooth } ?: return null
    val fill = layer.mediaFill?.takeIf { it.video } ?: return null
    val pending = fill.uri != mark.result && fill.uri == mark.original && mark.shift == 0
    if (!pending && fill.uri != mark.result) return null
    val facts = mark.facts
    if (facts == null && !pending) return null
    val base = layer.smoothSource(mark)
    val media = mark.media ?: media(base.mediaFill?.uri ?: return null) ?: return null
    val smoothing = smoothingOf(mark.node["option"]) ?: return null
    val plan = smoothPlan(base, media, fps, smoothing) ?: return null
    if (facts == null) return SmoothChange(layer.id, layer.node, Double.MAX_VALUE, false)
    val length = (layer.endTime - layer.startTime).coerceAtLeast(1)
    val timing = VideoTiming(base)
    val start = timing.mediaMs(0.0)
    val end = timing.mediaMs(length.toDouble()).let { if (layer.node["loop"] == "true") minOf(it, base.mediaFill!!.outTime.toDouble()) else it }
    val mean = ((end - start) / length).coerceAtLeast(1e-6)
    val uncovered = (maxOf(0.0, facts.from - start) + maxOf(0.0, end - (facts.from + facts.span))) / mean
    val retimed = base.speedSignature(fps) != facts.sig
    val blurMoved = smoothing.blur != MotionBlur.Off && kotlin.math.abs(plan.blurScale - facts.blur) > 0.05f * maxOf(facts.blur, 0.01f)
    if (!pending && uncovered <= 1000.0 / fps && !(retimed && (plan.fps > facts.fps || blurMoved))) return null
    val amount = maxOf(uncovered, if (retimed) length * kotlin.math.abs(1 - facts.mean / base.meanSpeed().coerceAtLeast(1e-6)) else 0.0)
    val small = amount <= maxOf(1000.0 / fps, minOf(1000.0, length * 0.1)) && smoothing.method != FrameInterpolation.Ai
    return SmoothChange(layer.id, layer.node, amount, small)
}

internal fun Scene.trimmedClip(layer: Layer, start: Int, end: Int): Layer {
    if (start == layer.startTime && end == layer.endTime) return layer
    val plain = layer.trimmedTo(start, end, mediaLengthMs(layer))
    if (plain.startTime == start && plain.endTime == end) return plain
    val mark = layer.aiMarks.firstOrNull { it.kind == AiKind.Smooth && it.result == layer.mediaFill?.uri && it.original != null } ?: return plain
    val sourceMs = mark.media?.duration?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: Int.MAX_VALUE
    val wide = layer.smoothSource(mark).trimmedTo(start, end, sourceMs)
    if (wide.startTime == plain.startTime && wide.endTime == plain.endTime) return plain
    return wide.copy(node = wide.node.copy(children = wide.node.children.map { if (it.name == AI_TAG && it["type"] == AiKind.Smooth.xml) it.with("shift", null) else it }))
}

internal fun Scene.withClip(id: Long, layer: Layer, from: Scene = this): Scene {
    val wanted = layer.node.mediaUris()
    return (from.media + layer.aiMarks.mapNotNull { it.media }).filter { it.uri in wanted }.fold(update(id) { layer }) { scene, record -> scene.withMedia(record) }
}

internal fun AiMark.smoothing(): Smoothing = (smoothingOf(node["option"]) ?: Smoothing(FrameInterpolation.Flow)).let {
    if (it.method == FrameInterpolation.Ai && !app.absentmotion.Tier.has(app.absentmotion.Feature.AiSmooth)) it.copy(method = FrameInterpolation.Flow) else it
}

internal class SmoothKeeper(private val context: Context, private val state: EditorState, private val tools: AiTools) {
    var asking by mutableStateOf<SmoothChange?>(null)
    private val asked: MutableSet<XmlNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val failed: MutableSet<XmlNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private class Seen(val busy: Boolean, val scene: Scene, val frame: Int)

    suspend fun watch() {
        snapshotFlow { Seen(state.playing || state.exportOpen, state.scene, state.frame) }.collectLatest { seen ->
            if (seen.busy) return@collectLatest
            delay(IDLE_MS)
            while (state.gestureOpen || tools.running != null || asking != null) delay(WAIT_MS)
            val scene = state.scene
            val change = scene.layers.asSequence().mapNotNull { scene.smoothChange(it) }.firstOrNull { it.node !in asked } ?: return@collectLatest
            if (change.small && change.node !in failed) update(scene, change)
            if (!change.small || change.node in failed) {
                asked += change.node
                asking = change
            }
        }
    }

    fun redo(change: SmoothChange) {
        val mark = state.scene.layer(change.id)?.aiMarks?.firstOrNull { it.kind == AiKind.Smooth } ?: return
        tools.smoothing = mark.smoothing()
        tools.apply(AiAction.Smooth, confirmed = true, target = change.id)
    }

    private suspend fun update(scene: Scene, change: SmoothChange) {
        val layer = scene.layer(change.id) ?: return
        val mark = layer.aiMarks.firstOrNull { it.kind == AiKind.Smooth } ?: return
        val base = layer.smoothSource(mark)
        val source = base.mediaFill?.uri ?: return
        val media = mark.media ?: scene.media(source) ?: return
        val smoothing = mark.smoothing()
        val plan = smoothPlan(base, media, scene.fps, smoothing) ?: return
        state.hint(R.string.smooth_updating)
        var made: AiMedia? = null
        var committed = false
        try {
            try { state.engine.awaitVideoFrames(scene, state.timeMs) } catch (e: java.io.IOException) { android.util.Log.i("AMSmooth", "preview frame not ready: ${e.message}") }
            withContext(Dispatchers.IO) { made = ClipSmooth.run(context, state.engine, base, media, scene.fps, smoothing) { _, _ -> } }
            val output = made ?: return
            if (state.scene.node !== scene.node) return
            state.amend(scene.update(change.id) { it.withSmoothed(output.declaration.uri, source, smoothing.option, media, base, smoothFacts(base, plan, scene.fps)) }
                .withMedia(output.declaration))
            committed = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed += change.node
        } catch (_: OutOfMemoryError) {
            failed += change.node
        } finally {
            if (!committed) made?.file?.delete()
        }
    }

    private companion object {
        const val IDLE_MS = 1500L
        const val WAIT_MS = 250L
    }
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

internal fun Layer.withSmoothed(result: String, source: String, option: String, original: MediaDeclaration?, base: Layer, facts: Map<String, String> = emptyMap()): Layer =
    copy(node = node.with("fillVideo", result)).shiftedMedia(-(mediaFill?.inTime ?: 0))
        .withAiMark(AiKind.Smooth, source, result, option, original, base.mediaFill?.inTime ?: 0, facts)

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
