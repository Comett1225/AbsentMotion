package app.absentmotion.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

typealias AudioMix = (startUs: Long, endUs: Long) -> ShortArray

object ExportAudio {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2

    @Volatile var mixFor: ((Scene) -> AudioMix?)? = null
}

private const val BASE_US = 1_000_000L

internal suspend fun exportVideo(
    engine: EngineThread, scene: Scene, xml: String, s: ExportSettings, out: File, audio: AudioMix?, progress: (Float) -> Unit,
) {
    val (width, height) = exportSize(scene, s.shortSide, even = true)
    val frames = frameCount(scene, s.fps)
    val mime = s.codec.mime
    val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && mime in it.supportedTypes }.sortedByDescending { it.isHardwareAccelerated }
        .firstOrNull { it.getCapabilitiesForType(mime).videoCapabilities!!.isSizeSupported(width, height) }
        ?: error("This phone cannot encode ${s.codec.label} at ${width}x$height")
    val caps = info.getCapabilitiesForType(mime)
    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(width, height, s).coerceIn(caps.videoCapabilities!!.bitrateRange.lower, caps.videoCapabilities!!.bitrateRange.upper))
        setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC && caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
    }

    val encoder = MediaCodec.createByCodecName(info.name)
    val stop = AtomicBoolean()
    val failure = AtomicReference<Throwable>()
    fun fail(t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
    val mux = try { Mux(out.path, if (audio != null) 2 else 1, ::fail) } catch (t: Throwable) { encoder.release(); throw t }
    val videoDone = AtomicBoolean()
    val workers = ArrayList<Thread>()
    var surface: Surface? = null
    var audioEncoder: MediaCodec? = null
    fun worker(name: String, body: () -> Unit) { workers += thread(name = "am-export-$name") { try { body() } catch (t: Throwable) { fail(t) } } }
    try {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder.createInputSurface()
        encoder.start()
        worker("video") { drain(encoder, mux, videoDone, BASE_US) }
        if (audio != null) {
            val aac = createAacEncoder().also { audioEncoder = it }
            val delay = AtomicInteger(aacDelayFrames(null))
            val totalUs = (scene.totalTime + 1) * 1000L
            worker("audio-out") { drain(aac, mux, stop, 0, delay, totalUs) }
            worker("audio-in") { feedAudio(aac, audio, totalUs, stop, delay) }
        }
        engine.exportSession(xml, s.interpolation, s.fps) {
            val target = attach(surface)
            try {
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    failure.get()?.let { throw it }
                    val presentUs = BASE_US + k * 1_000_000L / s.fps
                    check(render(target, frameTimeMs(scene, s.fps, k), presentUs * 1000)) { "The engine could not draw frame $k" }
                    progress((k + 1f) / frames * 0.98f)
                }
                encoder.signalEndOfInputStream()
            } finally {
                detach(target)
            }
        }
        runInterruptible(Dispatchers.IO) { workers.forEach { it.join() } }
        failure.get()?.let { throw it }
        mux.finish()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            stop.set(true)
            videoDone.set(true)
            workers.forEach { it.join() }
            runCatching { encoder.stop() }; encoder.release(); surface?.release()
            audioEncoder?.let { runCatching { it.stop() }; it.release() }
            mux.release()
            (audio as? java.io.Closeable)?.close()
        }
    }
    progress(1f)
}

private fun drain(codec: MediaCodec, mux: Mux, stop: AtomicBoolean, ptsShift: Long,
                  audioDelay: AtomicInteger? = null, totalUs: Long = Long.MAX_VALUE, trace: Boolean = false) {
    var shift = ptsShift
    val audioTime = audioDelay?.let { AacTimestamps() }
    val info = MediaCodec.BufferInfo()
    var track = -1
    while (!stop.get()) {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                if (audioDelay != null) {
                    val frames = aacDelayFrames(format.getInteger(MediaFormat.KEY_ENCODER_DELAY, -1))
                    audioDelay.set(frames)
                    audioTime?.delayFrames = frames
                    if (trace) android.util.Log.i("AMAac", "delay=$frames format=$format")
                    shift = frames * 1_000_000L / ExportAudio.SAMPLE_RATE
                    format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                }
                track = mux.addTrack(format)
            }
            index >= 0 -> {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (trace) android.util.Log.i("AMAac", "packet pts=${info.presentationTimeUs} shift=$shift size=${info.size}")
                    val timestamp = if (audioTime != null) audioTime.map(info.presentationTimeUs)
                        else (info.presentationTimeUs - shift).takeIf { it >= 0 }
                    if (timestamp != null && timestamp < totalUs) {
                        info.presentationTimeUs = timestamp
                        mux.write(track, codec.getOutputBuffer(index)!!, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }
}

private fun feedAudio(codec: MediaCodec, mix: AudioMix, totalUs: Long, stop: AtomicBoolean, delay: AtomicInteger) {
    val rate = ExportAudio.SAMPLE_RATE
    val channels = ExportAudio.CHANNELS
    val totalFrames = totalUs * rate / 1_000_000
    var fed = 0L
    fun queue(flags: Int, pcm: ShortArray, from: Int): Int {
        while (!stop.get()) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val buffer = codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
            val n = minOf(pcm.size - from, buffer.remaining() / 2) / channels * channels
            if (n > 0) buffer.asShortBuffer().put(pcm, from, n)
            codec.queueInputBuffer(index, 0, n * 2, fed * 1_000_000 / rate, flags)
            fed += n / channels
            return n
        }
        return 0
    }
    while (fed < aacInputFrames(totalUs, delay.get()) && !stop.get()) {
        val want = minOf(rate / 2L, aacInputFrames(totalUs, delay.get()) - fed).toInt()
        val real = minOf(want.toLong(), (totalFrames - fed).coerceAtLeast(0)).toInt()
        val pcm = if (real == 0) ShortArray(want * channels)
            else mix(fed * 1_000_000 / rate, (fed + real) * 1_000_000 / rate).copyOf(want * channels)
        var offset = 0
        while (offset < pcm.size && !stop.get()) offset += queue(0, pcm, offset)
    }
    queue(MediaCodec.BUFFER_FLAG_END_OF_STREAM, ShortArray(0), 0)
}

private fun createAacEncoder(): MediaCodec {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    try {
        codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ExportAudio.SAMPLE_RATE, ExportAudio.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioBitrate)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    } catch (t: Throwable) { codec.release(); throw t }
}

internal fun exportAacForTest(file: File, totalUs: Long) {
    val stop = AtomicBoolean()
    val failure = AtomicReference<Throwable>()
    val delay = AtomicInteger(aacDelayFrames(null))
    val codec = createAacEncoder()
    val mux = try { Mux(file.path, 1) { failure.compareAndSet(null, it); stop.set(true) } }
        catch (t: Throwable) { codec.stop(); codec.release(); throw t }
    val output = thread(name = "am-aac-check") {
        try { drain(codec, mux, stop, 0, delay, totalUs, true) }
        catch (t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
    }
    try {
        feedAudio(codec, { from, to ->
            val frames = ((to - from) * ExportAudio.SAMPLE_RATE / 1_000_000).toInt()
            ShortArray(frames * ExportAudio.CHANNELS) { index ->
                val t = from / 1e6 + index / ExportAudio.CHANNELS.toDouble() / ExportAudio.SAMPLE_RATE
                if (t < 0.01 || t >= totalUs / 1e6 - 0.01) (10000 * kotlin.math.sin(t * 2 * Math.PI * 440)).toInt().toShort() else 0
            }
        }, totalUs, stop, delay)
        output.join()
        failure.get()?.let { throw it }
        mux.finish()
    } finally {
        stop.set(true)
        output.join()
        runCatching { codec.stop() }
        codec.release()
        mux.release()
    }
}

private class Mux(path: String, private val tracks: Int, private val onError: (Throwable) -> Unit) {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    private var started = false
    private var broken = false
    private var released = false
    private val early = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    private inline fun <T> guarded(otherwise: T, op: () -> T): T {
        if (broken) return otherwise
        return try { op() } catch (t: Exception) { broken = true; onError(t); otherwise }
    }

    @Synchronized fun addTrack(format: MediaFormat): Int = guarded(-1) {
        val id = muxer.addTrack(format)
        if (++added == tracks) {
            muxer.start()
            started = true
            for ((track, data, info) in early) muxer.writeSampleData(track, data, info)
            early.clear()
        }
        id
    }

    @Synchronized fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) = guarded(Unit) {
        if (started) return@guarded muxer.writeSampleData(track, data, info)
        val copy = ByteBuffer.allocate(info.size)
        copy.put(data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }).flip()
        early += Triple(track, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
    }

    @Synchronized fun finish() {
        check(started) { "The encoder produced no video" }
        muxer.stop()
    }

    @Synchronized fun release() {
        if (!released) { released = true; runCatching { muxer.release() } }
    }
}
