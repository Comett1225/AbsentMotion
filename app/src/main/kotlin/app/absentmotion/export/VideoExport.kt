package app.absentmotion.export

import app.absentmotion.platform.alias
import app.absentmotion.platform.hardware
import app.absentmotion.platform.intOr
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import app.absentmotion.R
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
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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

private const val BASE_US = 1_000_000L

internal const val PrimingKey = "absentmotion-priming-frames"
private const val OpusLookaheadFrames = 312

internal suspend fun exportVideo(
    engine: EngineThread, scene: Scene, xml: String, s: ExportSettings, out: FileDescriptor, audio: AudioMix?,
    hotAtStart: Boolean = false, pauseMs: () -> Long = { 0L }, note: (UiText) -> Unit = {}, progress: (Float) -> Unit,
) {
    val (width, height) = exportSize(scene, s.shortSide, even = true)
    val frames = frameCount(scene, s.fps)
    holdingVideo(engine::holdVideo, engine::releaseVideo) {
        encode(engine, scene, xml, s, out, audio, width, height, frames, hotAtStart, pauseMs, note, progress)
    }
}

internal suspend fun <T> holdingVideo(hold: () -> Job?, release: () -> Job?, block: suspend () -> T): T {
    val held = hold()
    try {
        held?.join()
        return block()
    } finally {
        withContext(NonCancellable) { release()?.join() }
    }
}

private val HighRefused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

internal fun openVideoEncoder(s: ExportSettings, width: Int, height: Int, hot: Boolean, note: (UiText) -> Unit = {}, bitrate: Int? = null): MediaCodec {
    val mime = s.codec.mime
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && !it.alias && mime in it.supportedTypes && it.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true }
        .sortedByDescending { it.hardware }
    if (infos.isEmpty()) throw ExportError(txt(R.string.export_error_cannot_encode, s.codec.label, "${width}x$height"))
    var last: Throwable? = null
    for (info in infos) {
        if (ExportDebug.refuseHardware && info.hardware) continue
        val caps = info.getCapabilitiesForType(mime)
        val rated = runCatching { caps.videoCapabilities!!.getSupportedFrameRatesFor(width, height).upper }.getOrDefault(0.0)
        val key = "${info.name} ${width}x$height ${s.fps}"
        val high = mime == MediaFormat.MIMETYPE_VIDEO_AVC && key !in HighRefused && caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
        val codec = try { MediaCodec.createByCodecName(info.name) } catch (e: Exception) {
            last = e
            Log.w("AMExport", "cannot create ${info.name}", e)
            continue
        }
        var highFailed = false
        for (bestEffort in if (hot || rated < 2.0 * s.fps) listOf(true, false) else listOf(false, true)) for (profile in if (high) listOf(true, false) else listOf(false)) {
            try {
                codec.configure(encoderFormat(info, caps, s, width, height, profile, bestEffort, hot, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i("AMExport", "encoding with ${info.name}${if (profile) " (High)" else ""}${if (bestEffort) " at best effort" else ""}; rated for ${rated.toInt()} fps at ${width}x$height")
                if (highFailed && !profile) HighRefused += key
                if (!info.hardware && infos.first().hardware) note(txt(R.string.export_note_software))
                return codec
            } catch (e: Exception) {
                last = e
                if (profile) highFailed = true
                Log.w("AMExport", "${info.name} refused ${width}x$height at ${s.fps} fps${if (profile) " (High)" else ""}${if (bestEffort) " at best effort" else ""}", e)
                runCatching { codec.reset() }
            }
        }
        codec.release()
    }
    throw ExportError(txt(R.string.export_error_encoder_busy), last)
}

private fun encoderFormat(info: MediaCodecInfo, caps: MediaCodecInfo.CodecCapabilities, s: ExportSettings, width: Int, height: Int,
                          profile: Boolean, bestEffort: Boolean, hot: Boolean, bitrate: Int?) = MediaFormat.createVideoFormat(s.codec.mime, width, height).apply {
    val bitrates = caps.videoCapabilities!!.bitrateRange
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, (bitrate ?: videoBitrate(width, height, s)).coerceIn(bitrates.lower, bitrates.upper))
    setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    setInteger(MediaFormat.KEY_COLOR_STANDARD, if (fixedBt601(info)) MediaFormat.COLOR_STANDARD_BT601_NTSC else MediaFormat.COLOR_STANDARD_BT709)
    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    if (profile) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
    if (bestEffort) setInteger(MediaFormat.KEY_PRIORITY, 1)
    if (hot) setInteger(MediaFormat.KEY_OPERATING_RATE, s.fps)
}

private suspend fun encode(
    engine: EngineThread, scene: Scene, xml: String, s: ExportSettings, out: FileDescriptor, audio: AudioMix?, width: Int, height: Int, frames: Int,
    hotAtStart: Boolean, pauseMs: () -> Long, note: (UiText) -> Unit, progress: (Float) -> Unit,
) {
    val encoder = openVideoEncoder(s, width, height, hotAtStart, note)
    val stop = AtomicBoolean()
    val failure = AtomicReference<Throwable>()
    fun fail(t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
    val mux = try { Mux(openSink(s.container, out, s.fps, fixedBt601(encoder.codecInfo)), if (audio != null) 2 else 1, ::fail) } catch (t: Throwable) { encoder.release(); throw t }
    val videoDone = AtomicBoolean()
    val workers = ArrayList<Thread>()
    var surface: Surface? = null
    var audioEncoder: MediaCodec? = null
    val spent = LongArray(3)
    fun worker(name: String, body: () -> Unit) { workers += thread(name = "am-export-$name") { try { body() } catch (t: Throwable) { fail(t) } } }
    try {
        val input = encoder.createInputSurface().also { surface = it }
        encoder.start()
        worker("video") { drain(encoder, mux, videoDone, BASE_US) }
        if (audio != null) {
            val sound = s.audio
            val coder = createAudioEncoder(sound).also { audioEncoder = it }
            val aac = sound.codec == AudioCodec.Aac
            val delay = AtomicInteger(if (aac) aacDelayFrames(null) else OpusLookaheadFrames)
            val totalUs = (scene.totalTime + 1) * 1000L
            worker("audio-out") { drain(coder, mux, stop, 0, delay.takeIf { aac }, totalUs, rate = sound.effectiveRate, zeroStart = !aac) }
            worker("audio-in") { feedAudio(coder, audio, totalUs, stop, delay, sound.effectiveRate, sound.channels) }
        }
        engine.exportSession(xml, s.smoothing, s.fps) {
            suspend fun eachFrame(flush: () -> Unit = {}, draw: suspend (k: Int, presentNs: Long) -> Unit) {
                val started = System.nanoTime()
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    failure.get()?.let { throw it }
                    val drawing = System.nanoTime()
                    draw(k, (BASE_US + k * 1_000_000L / s.fps) * 1000)
                    spent[0] += System.nanoTime() - drawing
                    progress((k + 1f) / frames * 0.98f)
                    if (k % 30 == 0) ensureStorage()
                    pauseMs().takeIf { it > 0 }?.let { delay(it) }
                }
                flush()
                encoder.signalEndOfInputStream()
                spent[2] = System.nanoTime() - started
                fun perFrame(ns: Long) = "%.1f".format(java.util.Locale.ROOT, ns / 1e6 / frames.coerceAtLeast(1))
                Log.i("AMExport", "$frames frames in ${spent[2] / 1_000_000} ms; per frame: ${perFrame(spent[2])} ms, drawing ${perFrame(spent[0] - spent[1])} ms, waiting for copies ${perFrame(spent[1])} ms")
            }
            val buffers = ExportDebug.forceBuffers || Build.VERSION.SDK_INT < 33 || !stampsPresentTime()
            Log.i("AMExport", "rendering $frames frames at ${width}x$height through ${if (buffers) "encoder buffers" else "the swapchain"}")
            if (buffers && Build.VERSION.SDK_INT >= 33) {
                val writer = ImageWriter.Builder(input).setMaxImages(2).setHardwareBufferFormat(HardwareBuffer.RGBA_8888)
                    .setUsage(HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).build()
                try {
                    eachFrame { k, presentNs ->
                        val image = writer.dequeueInputImage()
                        try {
                            val drawn = image.hardwareBuffer!!.use { renderInto(it, frameTimeMs(scene, s.fps, k)) }
                            exportCheck(drawn) { txt(R.string.export_error_draw_frame, k.toString()) }
                            image.timestamp = presentNs
                            writer.queueInputImage(image)
                        } catch (t: Throwable) {
                            image.close()
                            throw t
                        }
                    }
                } finally {
                    releaseBuffers()
                    writer.close()
                }
            } else if (buffers) {
                val feed = EncoderFeed(input, width, height)
                try {
                    eachFrame({ exportCheck(feed.flush().also { spent[1] = feed.waitedNs }) { txt(R.string.export_error_draw_frame, (frames - 1).toString()) } }) { k, presentNs ->
                        exportCheck(feed.ready().also { spent[1] = feed.waitedNs }) { txt(R.string.export_error_draw_frame, (k - 2).toString()) }
                        exportCheck(renderInto(feed.next, frameTimeMs(scene, s.fps, k))) { txt(R.string.export_error_draw_frame, k.toString()) }
                        feed.send(presentNs)
                    }
                } finally {
                    feed.close()
                    releaseBuffers()
                }
            } else {
                val target = attach(input)
                try {
                    eachFrame { k, presentNs -> exportCheck(render(target, frameTimeMs(scene, s.fps, k), presentNs)) { txt(R.string.export_error_draw_frame, k.toString()) } }
                } finally {
                    detach(target)
                }
            }
        }
        runInterruptible(Dispatchers.IO) { workers.forEach { it.join() } }
        failure.get()?.let { throw it }
        mux.finish()
        if (s.container == Container.Mov) markQuickTime(out)
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

internal fun fixedBt601(info: MediaCodecInfo): Boolean = Build.VERSION.SDK_INT <= 30 && !info.hardware

internal fun drain(codec: MediaCodec, mux: Mux, stop: AtomicBoolean, ptsShift: Long,
                  audioDelay: AtomicInteger? = null, totalUs: Long = Long.MAX_VALUE, trace: Boolean = false, rate: Int = ExportAudio.SAMPLE_RATE,
                  zeroStart: Boolean = false) {
    var shift = ptsShift
    var first = !zeroStart
    val audioTime = audioDelay?.let { AacTimestamps(rate = rate, negative = mux.negativeTimes) }
    val info = MediaCodec.BufferInfo()
    var track = -1
    while (!stop.get()) {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                if (audioDelay != null) {
                    val frames = aacDelayFrames(format.intOr(MediaFormat.KEY_ENCODER_DELAY, -1))
                    audioDelay.set(frames)
                    audioTime?.delayFrames = frames
                    if (trace) android.util.Log.i("AMAac", "delay=$frames format=$format")
                    shift = frames * 1_000_000L / rate
                    format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                    format.setInteger(PrimingKey, frames)
                }
                track = mux.addTrack(format)
            }
            index >= 0 -> {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (!first) { first = true; shift = info.presentationTimeUs }
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

internal fun feedAudio(codec: MediaCodec, mix: AudioMix, totalUs: Long, stop: AtomicBoolean, delay: AtomicInteger,
                       rate: Int = ExportAudio.SAMPLE_RATE, channels: Int = ExportAudio.CHANNELS, onFed: (Long) -> Unit = {}) {
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
            onFed(fed)
            return n
        }
        return 0
    }
    while (fed < aacInputFrames(totalUs, delay.get(), rate) && !stop.get()) {
        val want = minOf(rate / 2L, aacInputFrames(totalUs, delay.get(), rate) - fed).toInt()
        val real = minOf(want.toLong(), (totalFrames - fed).coerceAtLeast(0)).toInt()
        val pcm = if (real == 0) ShortArray(want * channels)
            else toChannels(mix(fed * 1_000_000 / rate, (fed + real) * 1_000_000 / rate), channels).copyOf(want * channels)
        var offset = 0
        while (offset < pcm.size && !stop.get()) offset += queue(0, pcm, offset)
    }
    queue(MediaCodec.BUFFER_FLAG_END_OF_STREAM, ShortArray(0), 0)
}

internal fun toChannels(stereo: ShortArray, channels: Int): ShortArray =
    if (channels != 1) stereo else ShortArray(stereo.size / 2) { ((stereo[2 * it] + stereo[2 * it + 1]) / 2).toShort() }

internal fun createAudioEncoder(a: AudioSettings): MediaCodec {
    val codec = MediaCodec.createEncoderByType(a.codec.mime)
    try {
        codec.configure(MediaFormat.createAudioFormat(a.codec.mime, a.effectiveRate, a.channels).apply {
            if (a.codec == AudioCodec.Aac) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, a.bitrateKbps * 1000)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    } catch (t: Throwable) { codec.release(); throw t }
}

internal fun exportAacForTest(file: File, totalUs: Long) {
    val stop = AtomicBoolean()
    val failure = AtomicReference<Throwable>()
    val delay = AtomicInteger(aacDelayFrames(null))
    val codec = createAudioEncoder(AudioSettings())
    val mux = try { Mux(MuxerSink(file.path), 1) { failure.compareAndSet(null, it); stop.set(true) } }
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

