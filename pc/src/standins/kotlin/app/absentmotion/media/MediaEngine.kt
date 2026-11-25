package app.absentmotion.media

import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.platform.Context
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.tools.AudioJob
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoJob
import app.absentmotion.ui.camera.CameraDevices
import app.absentmotion.ui.camera.CameraFeed
import app.absentmotion.ui.camera.CameraInput
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class MediaPacket(val seconds: Double, val size: Int, val sync: Boolean)

class MediaStream(val input: InputStream, private val process: Process? = null, private val gently: Boolean = false) : Closeable {
    fun finished(): Boolean = process == null || process.waitFor() == 0

    override fun close() {
        runCatching { input.close() }
        if (gently) process?.destroy() else process?.destroyForcibly()
    }
}

abstract class MediaSink : Closeable {
    abstract val output: java.io.OutputStream
    abstract fun finish(): Boolean
    abstract fun problem(): String
}

class FrameSinkSpec(val width: Int, val height: Int, val format: String, val rate: String, val start: String? = null,
    val audioFrom: File? = null, val audioStart: String? = null, val audioLength: String? = null, val limit: String? = null)

class ReverseParts(val input: File, val output: File, val folder: File, val firstUs: Long, val lastUs: Long, val fps: Double, val total: Int, val perPart: Int, val sound: Boolean)

class GifPlan(val source: File, val colour: File, val matte: File?, val width: Int, val height: Int)

interface MediaEngine {
    fun probe(file: File, entries: String? = null): String
    fun packets(file: File, stream: Int): List<MediaPacket>
    fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage?
    fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream
    fun playback(file: File, fromMs: Long, rate: Int): MediaStream
    fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream
    fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream
    fun videoSource(file: File, maxSide: Int = 1080, maxPixels: Int = Int.MAX_VALUE): VideoSource
    fun encoders(): Set<String>
    fun encoderWorks(name: String, s: ExportSettings, width: Int, height: Int): Boolean
    fun videoExport(encoder: String, s: ExportSettings, width: Int, height: Int, pcm: File?, result: File, log: File): MediaSink
    fun audioExport(s: ExportSettings, rate: Int, floats: Boolean, result: File, log: File): MediaSink
    fun frameTimes(file: File, decoded: Boolean, from: String): List<Long>
    fun compress(input: File, job: VideoJob, audio: Boolean, durationUs: Long, out: File, startUs: Long, endUs: Long, progress: (Float) -> Unit, alive: () -> Boolean)
    fun extractAudio(input: File, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean)
    fun trim(input: File, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean)
    fun merge(inputs: List<File>, clips: List<app.absentmotion.tools.MergeFacts>, job: app.absentmotion.tools.MergeJob, out: File, progress: (Float) -> Unit, alive: () -> Boolean)
    fun reverse(parts: ReverseParts, progress: (Float) -> Unit, alive: () -> Boolean): Pair<Int, Int>?
    fun gifVideo(plan: GifPlan, alive: () -> Boolean)
    fun steadyFrames(file: File, start: String, length: String, rate: String, width: Int = 0, height: Int = 0): MediaStream
    fun frameSink(out: File, spec: FrameSinkSpec): MediaSink
    fun speech(input: File, out: File, rate: Int, tempo: Float, pitch: Float, timeoutMs: Long): Boolean
    fun cameraDevices(): CameraDevices
    fun cameraEncoder(): List<String>
    fun camera(input: CameraInput, record: File?, encoder: List<String>, onFrame: (ByteArray) -> Unit, onEnd: (Int, String) -> Unit): CameraFeed
    fun downloadOptions(plan: Plan, mode: SaveMode, dir: File): List<String>
    fun downloaded(plan: Plan, mode: SaveMode, dir: File): File?
    fun withoutAudio(file: File): File
    fun trouble(file: File): Int? = null

    companion object {
        val ffamp: Boolean by lazy { PlatformFeatures.ffamp && (System.getProperty("am.mediaReader")?.let { it == "ffamp" } ?: MediaReader.ffamp(Context())) }
        val current: MediaEngine by lazy { if (ffamp) FfampFirst else FfmpegEngine }
    }
}

object FfampFirst : MediaEngine by FfampEngine {
    private val readable = ConcurrentHashMap<String, Boolean>()

    fun reads(file: File): Boolean = readable.getOrPut("${file.absolutePath}|${file.length()}|${file.lastModified()}") {
        runCatching { FfampNative.openProblem(file.path) == null && FfampNative.probe(file.path) != null }.getOrDefault(false)
    }

    private fun of(file: File): MediaEngine = if (reads(file)) FfampEngine else FfmpegEngine

    private val capture: MediaEngine get() = if (app.absentmotion.desk.DeskOs.mac) FfampEngine else FfmpegEngine

    override fun probe(file: File, entries: String?) = of(file).probe(file, entries)
    override fun packets(file: File, stream: Int) = of(file).packets(file, stream)
    override fun still(file: File, timeMs: Long, maxSide: Int) = of(file).still(file, timeMs, maxSide)
    override fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int) = of(file).pcm(file, beforeUs, afterUs, rate, channels)
    override fun playback(file: File, fromMs: Long, rate: Int) = of(file).playback(file, fromMs, rate)
    override fun yuv(file: File, seconds: String, count: Int, batch: Boolean) = of(file).yuv(file, seconds, count, batch)
    override fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean) = of(file).play(file, fromMs, width, height, loop)
    override fun videoSource(file: File, maxSide: Int, maxPixels: Int) = of(file).videoSource(file, maxSide, maxPixels)
    override fun frameTimes(file: File, decoded: Boolean, from: String) = of(file).frameTimes(file, decoded, from)
    override fun compress(input: File, job: VideoJob, audio: Boolean, durationUs: Long, out: File, startUs: Long, endUs: Long, progress: (Float) -> Unit, alive: () -> Boolean) =
        of(input).compress(input, job, audio, durationUs, out, startUs, endUs, progress, alive)
    override fun extractAudio(input: File, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) =
        of(input).extractAudio(input, job, durationUs, out, progress, alive)
    override fun trim(input: File, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) {
        if (reads(input)) try { return FfampEngine.trim(input, startUs, endUs, out, progress, alive) } catch (_: ExportError) {}
        FfmpegEngine.trim(input, startUs, endUs, out, progress, alive)
    }
    override fun reverse(parts: ReverseParts, progress: (Float) -> Unit, alive: () -> Boolean) = of(parts.input).reverse(parts, progress, alive)
    override fun steadyFrames(file: File, start: String, length: String, rate: String, width: Int, height: Int) = of(file).steadyFrames(file, start, length, rate, width, height)
    override fun frameSink(out: File, spec: FrameSinkSpec) = (spec.audioFrom?.let(::of) ?: FfampEngine).frameSink(out, spec)
    override fun withoutAudio(file: File) = (if (reads(file)) FfampEngine.withoutAudio(file) else file).takeIf { it != file } ?: FfmpegEngine.withoutAudio(file)
    override fun downloadOptions(plan: Plan, mode: SaveMode, dir: File) =
        FfampEngine.downloadOptions(plan, mode, dir).toMutableList().also { it[it.indexOf("--ffmpeg-location") + 1] = FfmpegEngine.ffmpeg }
    override fun downloaded(plan: Plan, mode: SaveMode, dir: File) =
        FfampEngine.downloaded(plan, mode, dir) { video, audio, out -> FfampNative.merge(video.path, audio.path, out.path) == null || FfmpegEngine.merge(video, audio, out) }
    override fun trouble(file: File) = of(file).trouble(file)
    override fun cameraDevices() = capture.cameraDevices()
    override fun cameraEncoder() = capture.cameraEncoder()
    override fun camera(input: CameraInput, record: File?, encoder: List<String>, onFrame: (ByteArray) -> Unit, onEnd: (Int, String) -> Unit) = capture.camera(input, record, encoder, onFrame, onEnd)
}
