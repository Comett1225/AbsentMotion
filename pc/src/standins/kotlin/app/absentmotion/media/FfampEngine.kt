package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.desk.DeskProbe
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.Codec
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.videoBitrate
import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.txt
import app.absentmotion.tools.AudioJob
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.VideoJob
import app.absentmotion.ui.camera.CameraDevice
import app.absentmotion.ui.camera.CameraDevices
import app.absentmotion.ui.camera.CameraFeed
import app.absentmotion.ui.camera.CameraInput
import app.absentmotion.ui.camera.DeskCameraSource
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.VideoFrame
import app.absentmotion.pc.engine.VideoInfo
import app.absentmotion.pc.engine.VideoSource
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object FfampNative {
    init { DesktopNative.hashCode() }

    external fun probe(path: String): String?
    external fun packets(path: String, stream: Int): DoubleArray?
    external fun pcmOpen(path: String, rate: Int, channels: Int, seconds: Double): Long
    external fun pcmRead(handle: Long, out: ByteArray, frames: Int, whole: Boolean): Int
    external fun pcmClose(handle: Long)
    external fun framesOpen(path: String, width: Int, height: Int, layout: Int, rotate: Boolean): Long
    external fun framesInfo(handle: Long): DoubleArray
    external fun framesIndexAt(handle: Long, seconds: Double): Int
    external fun framesSeconds(handle: Long, index: Int): Double
    external fun framesRead(handle: Long, index: Int, out: ByteArray): Boolean
    external fun framesClose(handle: Long)
    external fun framesSteady(handle: Long, start: Double, fps: Double, length: Double): IntArray
    external fun encoders(): String
    external fun divxDecoder(): Boolean
    external fun openProblem(path: String): String?
    external fun encoderWorks(codec: String, width: Int, height: Int, fps: Int, bitrate: Int): Boolean
    external fun exportOpen(path: String, codec: String, width: Int, height: Int, fps: Int, bitrate: Int, sound: Int, soundBitrate: Int, rate: Int, channels: Int, pcm: String?): Long
    external fun exportFrame(handle: Long, pixels: ByteArray): Boolean
    external fun exportFinish(handle: Long): String?
    external fun exportProblem(handle: Long): String
    external fun exportClose(handle: Long)
    external fun soundOpen(path: String, format: Int, rate: Int, channels: Int, bitrate: Int): Long
    external fun soundWrite(handle: Long, data: ByteArray, frames: Int, floats: Boolean): Boolean
    external fun soundFinish(handle: Long): String?
    external fun soundClose(handle: Long)
    external fun times(path: String, decoded: Boolean, from: Double): DoubleArray?
    external fun compress(input: String, output: String, startUs: Long, endUs: Long, width: Int, height: Int, fps: Int, bitrate: Int, sound: Int, soundBitrate: Int, progress: FfampProgress): String?
    external fun trim(input: String, output: String, startUs: Long, endUs: Long, progress: FfampProgress): String?
    external fun extractAudio(input: String, output: String, how: Int, bitrate: Int, progress: FfampProgress): String?
    external fun reverse(input: String, output: String, startUs: Long, endUs: Long, progress: FfampProgress): DoubleArray?
    external fun gifVideo(input: String, colour: String, matte: String, progress: FfampProgress): DoubleArray?
    external fun merge(video: String, audio: String, output: String): String?
    external fun stripAudio(input: String, output: String): String?
    external fun rewrap(input: String, output: String): String?
    external fun sinkOpen(path: String, width: Int, height: Int, layout: Int, rate: String, start: Double, audioFrom: String?, audioStart: Double, audioLength: Double, limit: Double): Long
    external fun sinkWrite(handle: Long, pixels: ByteArray): Boolean
    external fun sinkFinish(handle: Long): Boolean
    external fun sinkClose(handle: Long)
    external fun speech(input: String, output: String, rate: Int, speed: Double, pitch: Double): String?
    external fun devices(): String
    external fun cameraOpen(video: String, audio: String?, record: String?, maxWidth: Int): Long
    external fun cameraState(handle: Long): IntArray
    external fun cameraCopy(handle: Long, out: ByteArray): Int
    external fun cameraFailed(handle: Long): String?
    external fun cameraFinish(handle: Long): String?
    external fun cameraClose(handle: Long)
}

internal fun interface FfampProgress {
    fun step(done: Double): Boolean
}

private class UnitOutput(private val unit: Int, private val send: (ByteArray, Int) -> Boolean) : java.io.OutputStream() {
    private var held = ByteArray(0)
    private var size = 0

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        if (size == 0 && off == 0 && len == b.size && len % unit == 0) {
            if (!send(b, len / unit)) throw java.io.IOException("the encoder stopped")
            return
        }
        if (held.size < size + len) held = held.copyOf(size + len)
        System.arraycopy(b, off, held, size, len)
        size += len
        val whole = size / unit
        if (whole == 0) return
        if (!send(held.copyOf(whole * unit), whole)) throw java.io.IOException("the encoder stopped")
        System.arraycopy(held, whole * unit, held, 0, size - whole * unit)
        size -= whole * unit
    }
}

private abstract class NativeSink(unit: Int) : MediaSink() {
    protected var handle = 0L
    private var said = ""
    protected abstract fun send(data: ByteArray, count: Int): Boolean
    protected abstract fun end(): String?
    protected abstract fun free()
    protected open fun reason(): String = ""

    override val output: java.io.OutputStream = UnitOutput(unit) { data, count -> synchronized(this) { handle != 0L && send(data, count) } }

    @Synchronized override fun finish(): Boolean {
        if (handle == 0L) return false
        val told = end()
        if (told != null) said = told
        return told == null
    }

    @Synchronized override fun problem(): String = said.ifEmpty { if (handle != 0L) reason() else "" }.ifEmpty { "the encoder stopped" }

    @Synchronized override fun close() {
        if (handle != 0L) free()
        handle = 0L
    }
}

private class CameraSession(input: CameraInput, record: File?, private val onFrame: (ByteArray) -> Unit, private val onEnd: (Int, String) -> Unit) : CameraFeed {
    private val lock = Any()
    private var handle = FfampNative.cameraOpen(if (input.fake) "test" else input.video.id, input.audio?.let { if (input.fake) "test" else it.id }, record?.path, 1920)
    @Volatile override var stopping = false
        private set

    init {
        if (handle == 0L) throw java.io.IOException("the camera did not start")
        Thread({
            var seen = 0
            var pixels = ByteArray(0)
            var problem: String? = null
            while (!stopping && problem == null) {
                synchronized(lock) {
                    if (handle == 0L) return@synchronized
                    problem = FfampNative.cameraFailed(handle)
                    val (width, height, serial) = FfampNative.cameraState(handle)
                    if (serial == seen || width <= 0 || height <= 0) return@synchronized
                    if (pixels.size != width * height * 4) pixels = ByteArray(width * height * 4)
                    val got = FfampNative.cameraCopy(handle, pixels)
                    if (got < 0) return@synchronized
                    seen = got
                    val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
                    org.jetbrains.skia.Image.makeRaster(info, pixels, width * 4).use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.use { onFrame(it.bytes) } }
                }
                Thread.sleep(30)
            }
            problem?.let { if (!stopping) onEnd(1, it) }
        }, "am-camera").apply { isDaemon = true }.start()
    }

    override fun finish(timeoutMs: Long): Boolean {
        stopping = true
        synchronized(lock) {
            if (handle == 0L) return false
            val told = FfampNative.cameraFinish(handle)
            FfampNative.cameraClose(handle)
            handle = 0L
            return told == null
        }
    }

    override fun stop() {
        stopping = true
        synchronized(lock) {
            if (handle != 0L) FfampNative.cameraClose(handle)
            handle = 0L
        }
    }
}

private const val RGBA = 0
private const val RGB24 = 1
private const val GRAY = 4
private const val I420 = 2
private const val BGRA = 3

private abstract class ChunkInput(size: Int) : InputStream() {
    protected val chunk = ByteArray(size)
    private var at = 0
    private var filled = 0

    protected abstract fun fill(): Int

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (at == filled) {
            filled = fill().coerceAtLeast(0)
            at = 0
            if (filled == 0) return -1
        }
        val n = minOf(len, filled - at)
        System.arraycopy(chunk, at, b, off, n)
        at += n
        return n
    }
}

private class PcmInput(file: File, rate: Int, channels: Int, seconds: Double, private val whole: Boolean) : ChunkInput(PCM_FRAMES * channels * if (whole) 2 else 4) {
    private var handle = FfampNative.pcmOpen(file.path, rate, channels, seconds)
    private val unit = channels * if (whole) 2 else 4

    @Synchronized override fun fill(): Int = if (handle == 0L) 0 else FfampNative.pcmRead(handle, chunk, PCM_FRAMES, whole) * unit

    @Synchronized override fun close() {
        if (handle != 0L) FfampNative.pcmClose(handle)
        handle = 0L
    }

    private companion object {
        const val PCM_FRAMES = 4096
    }
}

private class Frames(file: File, askedWidth: Int, askedHeight: Int, layout: Int, rotate: Boolean) : Closeable {
    private var handle = FfampNative.framesOpen(file.path, askedWidth, askedHeight, layout, rotate)
    private val info = if (handle != 0L) FfampNative.framesInfo(handle) else DoubleArray(8)
    val open = handle != 0L
    val width = info[0].toInt()
    val height = info[1].toInt()
    val count = info[5].toInt()
    val length = info[7]
    val bytes = if (layout == I420) width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2) else width * height * if (layout == RGB24) 3 else 4

    @Synchronized fun indexAt(seconds: Double): Int = if (handle == 0L) count else FfampNative.framesIndexAt(handle, seconds)
    @Synchronized fun steady(start: Double, fps: Double, length: Double): IntArray = if (handle == 0L) IntArray(0) else FfampNative.framesSteady(handle, start, fps, length)
    @Synchronized fun seconds(index: Int): Double = if (handle == 0L) 0.0 else FfampNative.framesSeconds(handle, index)
    @Synchronized fun read(index: Int, out: ByteArray): Boolean = handle != 0L && index in 0 until count && FfampNative.framesRead(handle, index, out)

    @Synchronized override fun close() {
        if (handle != 0L) FfampNative.framesClose(handle)
        handle = 0L
    }
}

private class FrameInput(private val frames: Frames, private val next: () -> Int) : ChunkInput(frames.bytes) {
    override fun fill(): Int {
        val index = next()
        return if (index >= 0 && frames.read(index, chunk)) chunk.size else 0
    }

    override fun close() = frames.close()
}

private class FfampVideoSource(file: File, maxSide: Int, maxPixels: Int) : VideoSource {
    override val info: VideoInfo
    private val frames: Frames
    private val fps: Double
    private val shift: Double
    private val bytes: ByteArray
    private var decoded = -1
    private val exactFrames = object : LinkedHashMap<Long, VideoFrame>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VideoFrame>?) = size > 6
    }

    init {
        val probe = DeskProbe.of(file)
        val video = checkNotNull(probe.video)
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(video.width, video.height), kotlin.math.sqrt(maxPixels.toDouble() / (video.width.toDouble() * video.height)))
        val width = maxOf(2, (video.width * scale).toInt() / 2 * 2)
        val height = maxOf(2, (video.height * scale).toInt() / 2 * 2)
        fps = (video.fps.takeIf { it > 0 && it.isFinite() } ?: 30.0).coerceIn(0.001, 1000.0)
        info = VideoInfo(video.width, video.height, (probe.durationUs / 1000).coerceAtLeast(0), fps)
        shift = (video.startUs - probe.startUs).coerceAtLeast(0) / 1_000_000.0
        frames = Frames(file, width, height, RGBA, false)
        check(frames.open)
        bytes = ByteArray(frames.bytes)
    }

    override fun frame(timeMs: Long): VideoFrame? {
        val target = (timeMs.coerceAtLeast(0) * fps / 1000.0).toLong()
        val index = frames.indexAt(target / fps + shift)
        if (index != decoded) {
            if (!frames.read(index, bytes)) return null
            decoded = index
        }
        return VideoFrame(frames.width, frames.height, bytes)
    }

    override fun frameExact(timeUs: Long): VideoFrame? {
        exactFrames[timeUs]?.let { return it }
        val data = ByteArray(frames.bytes)
        return if (frames.read(frames.indexAt((timeUs - 1).coerceAtLeast(0) / 1_000_000.0 + shift), data)) VideoFrame(frames.width, frames.height, data).also { exactFrames[timeUs] = it } else null
    }

    override fun close() {
        exactFrames.clear()
        frames.close()
        decoded = -1
    }
}

object FfampEngine : MediaEngine {
    override fun probe(file: File, entries: String?): String = FfampNative.probe(file.path).orEmpty()

    override fun packets(file: File, stream: Int): List<MediaPacket> {
        val values = FfampNative.packets(file.path, stream) ?: return emptyList()
        return List(values.size / 3) { MediaPacket(values[it * 3], values[it * 3 + 1].toInt(), values[it * 3 + 2] != 0.0) }
    }

    override fun still(file: File, timeMs: Long, maxSide: Int): BufferedImage? {
        val video = DeskProbe.of(file).video ?: return null
        val turned = video.rotation % 180 != 0
        val sw = if (turned) video.height else video.width
        val sh = if (turned) video.width else video.height
        if (sw <= 0 || sh <= 0) return null
        val bw = minOf(maxSide, sw)
        val bh = minOf(maxSide, sh)
        val width = minOf(Math.round(bh.toDouble() * sw / sh).toInt(), bw).coerceAtLeast(1)
        val height = minOf(Math.round(bw.toDouble() * sh / sw).toInt(), bh).coerceAtLeast(1)
        Frames(file, width, height, BGRA, true).use { frames ->
            val data = ByteArray(frames.bytes)
            if (!frames.read(frames.indexAt(timeMs / 1000.0), data)) return null
            val pixels = IntArray(width * height)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, pixels, 0, width) }
        }
    }

    override fun pcm(file: File, beforeUs: Long, afterUs: Long, rate: Int, channels: Int): MediaStream =
        MediaStream(PcmInput(file, rate, channels, (beforeUs + afterUs) / 1e6, false))

    override fun playback(file: File, fromMs: Long, rate: Int): MediaStream = MediaStream(PcmInput(file, rate, 2, fromMs / 1000.0, true))

    override fun yuv(file: File, seconds: String, count: Int, batch: Boolean): MediaStream {
        val frames = Frames(file, 0, 0, I420, false)
        val first = frames.indexAt(seconds.toDouble())
        var sent = 0
        return MediaStream(FrameInput(frames) { if (sent < count && first + sent < frames.count) first + sent++ else -1 })
    }

    override fun play(file: File, fromMs: Long, width: Int, height: Int, loop: Boolean): MediaStream {
        val frames = Frames(file, width, height, RGBA, true)
        val first = if (loop) 0 else frames.indexAt(fromMs / 1000.0)
        val origin = frames.seconds(first)
        var index = first - 1
        var began = System.nanoTime()
        fun waitFor(seconds: Double) {
            val left = began + (seconds * 1e9).toLong() - System.nanoTime()
            if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
        }
        return MediaStream(FrameInput(frames) {
            index++
            if (index >= frames.count && loop && first < frames.count) {
                waitFor(frames.length - origin)
                began = System.nanoTime()
                index = first
            }
            if (index >= frames.count) -1 else index.also { waitFor(frames.seconds(it) - origin) }
        })
    }

    override fun videoSource(file: File, maxSide: Int, maxPixels: Int): VideoSource = FfampVideoSource(file, maxSide, maxPixels)

    private val listed: Set<String> by lazy { FfampNative.encoders().lines().filter { it.isNotBlank() }.toSet() }

    private fun codec(s: ExportSettings) = when (s.codec) { Codec.H264 -> "h264"; Codec.Hevc -> "hevc"; Codec.Av1 -> "av1"; Codec.Vp9 -> "vp9"; Codec.Apv -> "apv" }

    private fun told(problem: String?, fallback: () -> ExportError, alive: () -> Boolean) {
        if (!alive()) throw CancellationException()
        if (problem != null) throw fallback()
    }

    override fun encoders(): Set<String> = listed

    override fun trouble(file: File): Int? {
        val problem = FfampNative.openProblem(file.path) ?: return null
        return when {
            "Advanced Simple Profile" in problem -> R.string.media_error_mpeg4_asp
            else -> null
        }
    }

    override fun encoderWorks(name: String, s: ExportSettings, width: Int, height: Int): Boolean =
        name in listed && FfampNative.encoderWorks(codec(s), width, height, s.fps, videoBitrate(width, height, s))

    override fun videoExport(encoder: String, s: ExportSettings, width: Int, height: Int, pcm: File?, result: File, log: File): MediaSink {
        val sound = if (pcm == null) 0 else if (s.audio.codec == AudioCodec.Opus) 2 else 1
        if (sound == 2 && "libopus" !in listed) throw ExportError(txt(R.string.export_support_no_encoder, s.audio.codec.label))
        return object : NativeSink(width * height * 4) {
            init { handle = FfampNative.exportOpen(result.path, codec(s), width, height, s.fps, videoBitrate(width, height, s), sound, s.audio.bitrateKbps * 1000, s.audio.effectiveRate, s.audio.channels, pcm?.path) }
            override fun send(data: ByteArray, count: Int): Boolean =
                if (count == 1) FfampNative.exportFrame(handle, data) else (0 until count).all { FfampNative.exportFrame(handle, data.copyOfRange(it * width * height * 4, (it + 1) * width * height * 4)) }
            override fun end(): String? = FfampNative.exportFinish(handle)
            override fun reason(): String = FfampNative.exportProblem(handle)
            override fun free() = FfampNative.exportClose(handle)
        }
    }

    override fun audioExport(s: ExportSettings, rate: Int, floats: Boolean, result: File, log: File): MediaSink {
        val (format, needs) = when (s.audioFormat) {
            AudioFormat.Wav16 -> 0 to null
            AudioFormat.Wav24 -> 1 to null
            AudioFormat.Flac -> 2 to null
            AudioFormat.M4a -> 3 to null
            AudioFormat.Ogg -> 4 to ("libopus" to AudioCodec.Opus.label)
            AudioFormat.Mp3 -> 5 to ("libmp3lame" to "MP3")
        }
        if (needs != null && needs.first !in listed) throw ExportError(txt(R.string.export_support_no_encoder, needs.second))
        val unit = if (floats) 8 else 4
        return object : NativeSink(unit) {
            init { handle = FfampNative.soundOpen(result.path, format, rate, s.audio.channels, s.audio.bitrateKbps * 1000) }
            override fun send(data: ByteArray, count: Int): Boolean = FfampNative.soundWrite(handle, data, count, floats)
            override fun end(): String? = FfampNative.soundFinish(handle)
            override fun free() = FfampNative.soundClose(handle)
        }
    }

    override fun frameTimes(file: File, decoded: Boolean, from: String): List<Long> {
        val start = if (from == "%+#1") -1.0 else from.removeSuffix("%").toDouble()
        return FfampNative.times(file.path, decoded, start)?.map { (it * 1e6).toLong() }.orEmpty()
    }

    override fun compress(input: File, job: VideoJob, audio: Boolean, durationUs: Long, out: File, startUs: Long, endUs: Long, progress: (Float) -> Unit, alive: () -> Boolean) {
        val sound = if (!audio || job.audioKbps <= 0) 0 else if (job.copyAudio) 1 else 2
        val problem = FfampNative.compress(input.path, out.path, startUs, if (endUs > startUs) endUs else 0, job.width, job.height, job.fps, job.bitrate, sound, job.audioKbps * 1000) {
            progress(it.toFloat().coerceIn(0f, 1f))
            alive()
        }
        told(problem, { ExportError(txt(R.string.library_text_value, problem.orEmpty().ifBlank { "ffamp" })) }, alive)
    }

    override fun extractAudio(input: File, job: AudioJob, durationUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) {
        if (job.mp3 && "libmp3lame" !in listed) throw ExportError(txt(R.string.export_support_no_encoder, "MP3"))
        val problem = FfampNative.extractAudio(input.path, out.path, if (job.copy) 1 else if (job.mp3) 3 else 2, job.kbps * 1000) {
            progress(it.toFloat().coerceIn(0f, 0.99f))
            alive()
        }
        told(problem, { ExportError(txt(R.string.tools_engine_stopped)) }, alive)
    }

    override fun trim(input: File, startUs: Long, endUs: Long, out: File, progress: (Float) -> Unit, alive: () -> Boolean) {
        val problem = FfampNative.trim(input.path, out.path, startUs, endUs) {
            progress(it.toFloat().coerceIn(0f, 0.99f))
            alive()
        }
        told(problem, { ExportError(txt(R.string.tools_engine_stopped)) }, alive)
    }

    override fun merge(inputs: List<File>, clips: List<app.absentmotion.tools.MergeFacts>, job: app.absentmotion.tools.MergeJob, out: File, progress: (Float) -> Unit, alive: () -> Boolean) =
        FfmpegEngine.merge(inputs, clips, job, out, progress, alive)

    override fun reverse(parts: ReverseParts, progress: (Float) -> Unit, alive: () -> Boolean): Pair<Int, Int>? {
        val made = FfampNative.reverse(parts.input.path, parts.output.path, parts.firstUs, parts.lastUs) {
            progress(it.toFloat().coerceIn(0f, 0.99f))
            alive()
        }
        if (!alive()) throw CancellationException()
        checkNotNull(made) { AppText.get(R.string.ai_unable_to_process) }
        return made[0].toInt() to made[1].toInt()
    }

    override fun gifVideo(plan: GifPlan, alive: () -> Boolean) {
        val made = FfampNative.gifVideo(plan.source.path, plan.colour.path, plan.matte?.path.orEmpty()) { alive() }
        if (!alive()) throw CancellationException()
        check(made != null && plan.colour.length() > 0 && (plan.matte == null || plan.matte.length() > 0)) { "GIF conversion failed" }
    }

    override fun steadyFrames(file: File, start: String, length: String, rate: String, width: Int, height: Int): MediaStream {
        val frames = Frames(file, width, height, RGB24, false)
        val picked = frames.steady(start.toDouble(), rate.toDouble(), length.toDouble())
        var sent = 0
        return MediaStream(FrameInput(frames) { if (sent < picked.size) picked[sent++] else -1 })
    }

    override fun frameSink(out: File, spec: FrameSinkSpec): MediaSink {
        val layout = when (spec.format) { "rgb24" -> RGB24; "gray" -> GRAY; else -> RGBA }
        val unit = spec.width * spec.height * when (layout) { RGB24 -> 3; GRAY -> 1; else -> 4 }
        return object : NativeSink(unit) {
            init {
                handle = FfampNative.sinkOpen(out.path, spec.width, spec.height, layout, spec.rate, spec.start?.toDouble() ?: 0.0, spec.audioFrom?.path,
                    spec.audioStart?.toDouble() ?: 0.0, spec.audioLength?.toDouble() ?: -1.0, spec.limit?.toDouble() ?: -1.0)
            }
            override fun send(data: ByteArray, count: Int): Boolean =
                if (count == 1) FfampNative.sinkWrite(handle, data) else (0 until count).all { FfampNative.sinkWrite(handle, data.copyOfRange(it * unit, (it + 1) * unit)) }
            override fun end(): String? = if (FfampNative.sinkFinish(handle)) null else "the video could not be written"
            override fun free() = FfampNative.sinkClose(handle)
        }
    }

    override fun speech(input: File, out: File, rate: Int, tempo: Float, pitch: Float, timeoutMs: Long): Boolean =
        FfampNative.speech(input.path, out.path, rate, tempo.toDouble(), pitch.toDouble()) == null

    override fun cameraDevices(): CameraDevices {
        if (DeskCameraSource.fakeRequested) return DeskCameraSource.fakeDevices
        val found = FfampNative.devices().lines().mapNotNull { line -> line.split('\t').takeIf { it.size == 3 } }
        fun of(kind: String) = found.filter { it[0] == kind }.map { CameraDevice(it[1], it[2]) }
        return CameraDevices(of("video"), of("audio"))
    }

    override fun cameraEncoder(): List<String> = emptyList()

    override fun camera(input: CameraInput, record: File?, encoder: List<String>, onFrame: (ByteArray) -> Unit, onEnd: (Int, String) -> Unit): CameraFeed =
        CameraSession(input, record, onFrame, onEnd)

    override fun downloadOptions(plan: Plan, mode: SaveMode, dir: File): List<String> {
        val chosen = listOfNotNull(plan.video?.id, plan.audio?.id).distinct()
        return listOf("--ffmpeg-location", File(dir, "none").path, "--fixup", "never", "--downloader", "m3u8:native", "--downloader", "dash:native",
            "-f", chosen.joinToString(","), "-o", File(dir, if (chosen.size > 1) "item.%(format_id)s.%(ext)s" else "item.%(ext)s").path)
    }

    override fun downloaded(plan: Plan, mode: SaveMode, dir: File): File? = downloaded(plan, mode, dir) { video, audio, out -> FfampNative.merge(video.path, audio.path, out.path) == null }

    fun downloaded(plan: Plan, mode: SaveMode, dir: File, merge: (File, File, File) -> Boolean): File? {
        val pieces = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("item.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
        fun piece(id: String?) = pieces.firstOrNull { id != null && it.name.startsWith("item.$id.") }
        val video = piece(plan.video?.id)
        val audio = piece(plan.audio?.id)
        if (video != null && audio != null && video != audio) {
            val out = File(dir, if (plan.webm) "joined.webm" else "joined.mp4")
            return out.takeIf { merge(video, audio, out) }
        }
        val one = pieces.maxByOrNull { it.length() } ?: return null
        if (mode == SaveMode.AudioOnly) {
            val out = File(dir, "sound.m4a")
            val copied = one.extension.lowercase() in setOf("m4a", "mp4") && FfampNative.extractAudio(one.path, out.path, 1, 0) { true } == null
            return if (copied || FfampNative.extractAudio(one.path, out.path, 2, 160_000) { true } == null) out else null
        }
        if (JSONObject(probe(one).ifBlank { "{}" }).optJSONObject("format")?.optString("format_name") != "mpegts") return one
        val out = File(dir, "plain.mp4")
        return if (FfampNative.rewrap(one.path, out.path) == null) out else null
    }

    override fun withoutAudio(file: File): File {
        val out = File(file.parentFile, "silent." + file.extension)
        return if (FfampNative.stripAudio(file.path, out.path) == null && out.length() > 0) out else file
    }
}
