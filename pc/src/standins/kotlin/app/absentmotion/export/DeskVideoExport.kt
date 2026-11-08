package app.absentmotion.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.engine.EngineThread
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

typealias AudioMix = (startUs: Long, endUs: Long) -> ShortArray

interface FloatMix {
    fun floats(startUs: Long, endUs: Long): FloatArray
}

object ExportDebug {
    @Volatile var forceBuffers = false
    @Volatile var fakeFreeBytes = -1L
    @Volatile var refuseHardware = false
    @Volatile var failPlayers = 0
}

object ExportAudio {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2

    @Volatile var mixFor: ((Scene, Int) -> AudioMix?)? = null
}

internal const val PrimingKey = "absentmotion-priming-frames"

private const val FrameBuffers = 4

internal fun frameBufferCount(width: Int, height: Int): Int = ((192L shl 20) / (width.toLong() * height * 4)).toInt().coerceIn(2, FrameBuffers)

internal suspend fun <T> holdingVideo(hold: () -> Job?, release: () -> Job?, block: suspend () -> T): T {
    val held = hold()
    try {
        held?.join()
        return block()
    } finally {
        withContext(NonCancellable) { release()?.join() }
    }
}

internal object EncoderTrouble {
    val bad: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var last: String? = null

    fun blameLast() { last?.let { bad += it } }
}

internal object DeskEncoders {
    private const val HardwareSide = 4096
    private const val SoftwareSide = 8192

    val available: Set<String> by lazy {
        runCatching {
            val process = ProcessBuilder(DeskMedia.ffmpeg, "-hide_banner", "-encoders").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val names = process.inputStream.bufferedReader().readLines().mapNotNull { line ->
                line.trim().split(Regex("\\s+")).takeIf { it.size >= 2 && it[0].length == 6 && it[0][0] in "VAS" }?.get(1)
            }.toSet()
            process.waitFor()
            names
        }.getOrDefault(emptySet())
    }

    fun hardware(name: String): Boolean = !name.startsWith("lib") && name !in setOf("aac", "opus", "flac")

    fun video(codec: Codec): List<String> = when (codec) {
        Codec.H264 -> listOf("h264_videotoolbox", "h264_nvenc", "h264_qsv", "h264_amf", "h264_mf", "h264_vaapi", "libx264")
        Codec.Hevc -> listOf("hevc_videotoolbox", "hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_mf", "hevc_vaapi", "libx265")
        Codec.Av1 -> listOf("av1_nvenc", "av1_qsv", "av1_amf", "av1_vaapi", "libsvtav1", "libaom-av1")
        Codec.Vp9 -> listOf("vp9_qsv", "libvpx-vp9")
        Codec.Apv -> listOf("liboapv")
    }.filter { it in available && (!vaapi(it) || renderNode != null) }

    private fun vaapi(name: String) = name.endsWith("_vaapi")

    private val renderNode: String? by lazy {
        System.getenv("AM_VAAPI_DEVICE") ?: File("/dev/dri").listFiles { f -> f.name.startsWith("renderD") }?.map { it.path }?.minOrNull()
    }

    fun device(name: String): List<String> = if (vaapi(name)) listOf("-init_hw_device", "vaapi=am:$renderNode", "-filter_hw_device", "am") else emptyList()

    fun filter(name: String): List<String> = listOf("-vf", "scale=out_color_matrix=bt709:out_range=tv" + if (vaapi(name)) ",format=nv12,hwupload" else "")

    fun audio(codec: AudioCodec, kbps: Int = 0): List<String> = when (codec) {
        AudioCodec.Aac -> listOfNotNull("aac_at", "aac_mf".takeIf { kbps in 1..192 }, "aac")
        AudioCodec.Opus -> listOf("libopus", "opus")
    }.filter { it in available }

    private val channelBits = mapOf("libopus" to 256_000, "opus" to 255_000, "aac_at" to 256_000, "aac" to 264_600)

    fun audioExtra(name: String): List<String> = if (name == "opus") listOf("-strict", "-2") else emptyList()

    fun side(name: String): Int = if (hardware(name)) HardwareSide else SoftwareSide

    private val working = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun works(name: String, s: ExportSettings, width: Int, height: Int): Boolean = (name != "av1_amf" || width % 64 == 0 && height % 16 == 0) && working.getOrPut("$name ${width}x$height ${s.fps} ${s.codec} ${videoBitrate(width, height, s)}") {
        runCatching {
            val args = listOf(DeskMedia.ffmpeg, "-v", "error") + device(name) + listOf("-f", "lavfi", "-i", "color=c=black:s=${width}x$height:r=${s.fps}:d=0.2") +
                filter(name) + videoArgs(name, s, width, height) + listOf("-frames:v", "2", "-f", "null", "-")
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try { process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0 } finally { process.destroyForcibly() }
        }.getOrDefault(false)
    }

    fun videoArgs(name: String, s: ExportSettings, width: Int, height: Int): List<String> {
        val bitrate = videoBitrate(width, height, s)
        val tuned = when {
            name.endsWith("_videotoolbox") -> listOf("-realtime", "0") + if (s.codec == Codec.H264) listOf("-profile:v", "high") else emptyList()
            name == "libx264" -> listOf("-preset", "veryfast", "-profile:v", "high")
            name == "libx265" -> listOf("-preset", "fast", "-x265-params", "log-level=error")
            name == "libsvtav1" -> listOf("-preset", "10")
            name == "libaom-av1" -> listOf("-cpu-used", "8", "-row-mt", "1")
            name == "libvpx-vp9" -> listOf("-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1")
            name.endsWith("_nvenc") -> listOf("-preset", "p4")
            else -> emptyList()
        }
        return listOf("-c:v", name) + tuned + listOf("-b:v", "$bitrate", "-g", "${s.fps}") + (if (vaapi(name)) emptyList() else listOf("-pix_fmt", "yuv420p")) +
            listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv")
    }

    private val seeded = AtomicBoolean()

    fun seedReport(context: Context) {
        if (!seeded.compareAndSet(false, true)) return
        runCatching {
            val caps = DeviceCapabilities.of(context)
            val prefs = context.getSharedPreferences("export_device", Context.MODE_PRIVATE)
            val old = runCatching { prefs.getString("report", null)?.let { DeviceReport.fromJson(JSONObject(it)) } }.getOrNull()
            val video = Codec.entries.flatMap { codec ->
                video(codec).map { name ->
                    val side = side(name)
                    EncoderCaps(name, codec.mime, hardware(name), 2..side, 2..side, 2, 2, listOf(SizePoint(side, side, 240.0)), 10_000..800_000_000)
                }
            }
            val sound = AudioCodec.entries.flatMap { codec -> audio(codec).map { EncoderCaps(it, codec.mime, false, maxChannels = 2, channelBits = channelBits[it] ?: 0) } } +
                listOf("flac").filter { it in available }.map { EncoderCaps(it, "audio/flac", false, maxChannels = 2) }
            val report = DeviceReport(caps.key, video + sound, old?.takeIf { it.key == caps.key }?.probe, android.os.Build.VERSION.SDK_INT)
            prefs.edit().putString("report", report.toJson().toString()).apply()
        }.onFailure { Log.w("AMExport", "cannot list the encoders", it) }
    }
}

internal class Ffmpeg(args: List<String>, private val log: File) {
    val process: Process = try {
        ProcessBuilder(listOf(DeskMedia.ffmpeg, "-hide_banner", "-v", "error") + args).redirectError(log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: java.io.IOException) {
        throw ExportError(txt(R.string.desk_export_no_ffmpeg), e)
    }

    fun finish(): Boolean {
        runCatching { process.outputStream.close() }
        return process.waitFor() == 0
    }

    fun problem(): String = runCatching { log.readLines().lastOrNull { it.isNotBlank() } }.getOrNull() ?: "ffmpeg stopped"

    fun kill() { process.destroyForcibly() }
}

internal fun copyInto(file: File, out: FileDescriptor) {
    FileInputStream(file).channel.use { input ->
        val target = FileOutputStream(out).channel
        var done = 0L
        val size = input.size()
        while (done < size) {
            val moved = input.transferTo(done, size - done, target)
            if (moved <= 0L) throw java.io.IOException("copy stalled")
            done += moved
        }
        target.force(false)
    }
}

internal fun exportTemp(ext: String): File =
    File(android.content.Desk.context.cacheDir, "export").also { it.mkdirs() }.resolve("${System.nanoTime()}.$ext")

internal suspend fun writePcm(mix: AudioMix, totalUs: Long, rate: Int, file: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val totalFrames = totalUs * rate / 1_000_000
    val free = ExportDebug.fakeFreeBytes.takeIf { it >= 0 } ?: file.absoluteFile.parentFile.usableSpace
    if (free < totalFrames * 4 + MinFreeBytes) throw StorageFullException()
    file.outputStream().buffered(1 shl 16).use { out ->
        var frame = 0L
        while (frame < totalFrames) {
            currentCoroutineContext().ensureActive()
            val next = minOf(totalFrames, frame + rate / 2)
            val pcm = mix(frame * 1_000_000 / rate, next * 1_000_000 / rate)
            val wanted = ((next - frame) * 2).toInt()
            val bytes = ByteBuffer.allocate(wanted * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm, 0, minOf(pcm.size, wanted))
            out.write(bytes.array())
            frame = next
            progress(frame.toFloat() / totalFrames)
        }
    }
}

internal suspend fun exportVideo(
    engine: EngineThread, scene: Scene, xml: String, s: ExportSettings, out: FileDescriptor, audio: AudioMix?,
    hotAtStart: Boolean = false, pauseMs: () -> Long = { 0L }, note: (UiText) -> Unit = {}, looks: (FrameLooks) -> Unit = {}, progress: (Float) -> Unit,
) {
    val (width, height) = exportSize(scene, s.shortSide, even = true)
    val frames = frameCount(scene, s.fps)
    if (DeskEncoders.available.isEmpty()) throw ExportError(txt(R.string.desk_export_no_ffmpeg))
    EncoderTrouble.last = null
    val names = DeskEncoders.video(s.codec).filter { it !in EncoderTrouble.bad && !(ExportDebug.refuseHardware && DeskEncoders.hardware(it)) }.toMutableList()
    val result = exportTemp(s.container.ext)
    val log = exportTemp("log")
    val pcm = audio?.let { exportTemp("pcm") }
    var ffmpeg: Ffmpeg? = null
    try {
        if (audio != null && pcm != null) writePcm(audio, (scene.totalTime + 1) * 1000L, s.audio.effectiveRate, pcm) { progress(0f) }
        val sound = if (pcm == null) emptyList() else {
            val coder = DeskEncoders.audio(s.audio.codec, s.audio.bitrateKbps).firstOrNull() ?: throw ExportError(txt(R.string.export_support_no_encoder, s.audio.codec.label))
            listOf("-map", "1:a", "-c:a", coder) + DeskEncoders.audioExtra(coder) + listOf("-b:a", "${s.audio.bitrateKbps}k", "-ar", "${s.audio.effectiveRate}", "-ac", "${s.audio.channels}")
        }
        val inputs = listOf("-f", "rawvideo", "-pix_fmt", "rgb0", "-s", "${width}x$height", "-r", "${s.fps}", "-i", "-") +
            (if (pcm == null) emptyList() else listOf("-f", "s16le", "-ar", "${s.audio.effectiveRate}", "-ac", "2", "-i", pcm.path))
        val tag = if (s.codec == Codec.Hevc && (s.container == Container.Mp4 || s.container == Container.Mov)) listOf("-tag:v", "hvc1") else emptyList()
        val format = when (s.container) {
            Container.Mp4 -> listOf("-f", "mp4", "-movflags", "+faststart")
            Container.Mov -> listOf("-f", "mov", "-movflags", "+faststart")
            Container.WebM -> listOf("-f", "webm")
            Container.Mkv -> listOf("-f", "matroska")
        }
        var trouble: Throwable? = null
        while (true) {
            val encoder = withContext(Dispatchers.IO) { names.firstOrNull { DeskEncoders.works(it, s, width, height) } }
                ?: throw trouble ?: ExportError(txt(R.string.desk_export_no_encoder, s.codec.label, "${width}x$height"))
            names.subList(0, names.indexOf(encoder) + 1).clear()
            if (!DeskEncoders.hardware(encoder) && DeskEncoders.video(s.codec).any(DeskEncoders::hardware)) note(txt(R.string.desk_export_note_software))
            EncoderTrouble.last = encoder
            Log.i("AMExport", "encoding ${width}x$height at ${s.fps} fps with $encoder")
            val running = Ffmpeg(DeskEncoders.device(encoder) + inputs + listOf("-map", "0:v") + DeskEncoders.filter(encoder) +
                DeskEncoders.videoArgs(encoder, s, width, height) + tag + sound + format + listOf("-y", result.path), log).also { ffmpeg = it }
            val buffers = frameBufferCount(width, height)
            val queue = ArrayBlockingQueue<ByteArray>(buffers)
            val free = ArrayBlockingQueue<ByteArray>(buffers)
            val end = ByteArray(0)
            val failure = AtomicReference<Throwable>()
            val written = AtomicInteger()
            fun <T : Any> waitFor(step: () -> T?): T? {
                while (failure.get() == null) step()?.let { return it }
                return null
            }
            val writer = thread(name = "am-export-write") {
                try {
                    val stdin = running.process.outputStream
                    while (true) {
                        val pixels = queue.take()
                        if (pixels === end) break
                        stdin.write(pixels)
                        free.offer(pixels)
                        written.incrementAndGet()
                    }
                    stdin.flush()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    queue.clear()
                }
            }
            val ok = try {
                try {
                    repeat(buffers) { free.add(ByteArray(width * height * 4)) }
                    engine.exportSession(xml, s.smoothing, s.fps) {
                        for (k in 0 until frames) {
                            currentCoroutineContext().ensureActive()
                            failure.get()?.let { throw ExportError(txt(R.string.library_text_value, running.problem()), it) }
                            val pixels = runInterruptible(Dispatchers.IO) { waitFor { free.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) } } ?: continue
                            if (!stillInto(frameTimeMs(scene, s.fps, k), width, height, pixels)) throw ExportError(txt(R.string.export_error_draw_frame, k.toString()))
                            runInterruptible(Dispatchers.IO) { waitFor { pixels.takeIf { queue.offer(it, 50, java.util.concurrent.TimeUnit.MILLISECONDS) } } }
                            progress((k + 1f) / frames * 0.98f)
                            if (k % 30 == 0) ensureStorage()
                            pauseMs().takeIf { it > 0 }?.let { delay(it) }
                        }
                    }
                    runInterruptible(Dispatchers.IO) { while (failure.get() == null && !queue.offer(end, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit }
                    runInterruptible(Dispatchers.IO) { writer.join() }
                } finally {
                    if (writer.isAlive) { running.kill(); queue.clear(); queue.offer(end); writer.join() }
                }
                runInterruptible(Dispatchers.IO) { running.finish() }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || failure.get() == null) throw t
                false
            }
            val problem = failure.get()?.let { ExportError(txt(R.string.library_text_value, running.problem()), it) }
                ?: if (!ok) ExportError(txt(R.string.library_text_value, running.problem())) else null
            if (problem != null && result.length() == 0L && names.isNotEmpty()) {
                Log.w("AMExport", "$encoder failed at start, trying the next encoder", problem)
                running.kill()
                EncoderTrouble.blameLast()
                note(txt(R.string.export_note_retry))
                trouble = problem
                continue
            }
            if (problem != null) throw problem
            Log.i("AMExport", "${written.get()} frames written by $encoder")
            break
        }
        withContext(Dispatchers.IO) { copyInto(result, out) }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            ffmpeg?.kill()
            result.delete(); log.delete(); pcm?.delete()
            (audio as? java.io.Closeable)?.close()
        }
    }
    progress(1f)
}

internal fun turnsForEncoder(s: ExportSettings, width: Int, height: Int): Boolean = false

internal fun fixedBt601(info: MediaCodecInfo): Boolean = false

private fun noMediaCodec(): Nothing = throw ExportError(txt(R.string.export_error_encoder_busy), UnsupportedOperationException("MediaCodec is not available on this computer"))

internal fun openVideoEncoder(s: ExportSettings, width: Int, height: Int, hot: Boolean, note: (UiText) -> Unit = {}, bitrate: Int? = null): MediaCodec = noMediaCodec()

internal fun drain(codec: MediaCodec, mux: Mux, stop: AtomicBoolean, ptsShift: Long, audioDelay: AtomicInteger? = null, totalUs: Long = Long.MAX_VALUE,
                   trace: Boolean = false, rate: Int = ExportAudio.SAMPLE_RATE, zeroStart: Boolean = false): Unit = noMediaCodec()

internal fun feedAudio(codec: MediaCodec, mix: AudioMix, totalUs: Long, stop: AtomicBoolean, delay: AtomicInteger,
                       rate: Int = ExportAudio.SAMPLE_RATE, channels: Int = ExportAudio.CHANNELS, onFed: (Long) -> Unit = {}): Unit = noMediaCodec()

internal fun toChannels(stereo: ShortArray, channels: Int): ShortArray =
    if (channels != 1) stereo else ShortArray(stereo.size / 2) { ((stereo[2 * it] + stereo[2 * it + 1]) / 2).toShort() }

internal fun createAudioEncoder(a: AudioSettings): MediaCodec = noMediaCodec()

internal fun startEncoder(format: MediaFormat, label: String): MediaCodec = noMediaCodec()

internal fun exportAacForTest(file: File, totalUs: Long): Unit = noMediaCodec()
