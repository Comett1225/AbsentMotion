package app.absentmotion.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.system.Os
import app.absentmotion.R
import app.absentmotion.i18n.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt

internal class Mp3Encoder(rate: Int, channels: Int, kbps: Int) : Closeable {
    private var handle = nativeOpen(rate, channels, kbps).also { exportCheck(it != 0L) { txt(R.string.export_error_mp3_settings, rate.toString(), kbps.toString()) } }

    fun encode(pcm: ShortArray, frames: Int): ByteArray = nativeEncode(handle, pcm, frames) ?: throw ExportError(txt(R.string.export_error_mp3))

    fun flush(): ByteArray = nativeFlush(handle) ?: throw ExportError(txt(R.string.export_error_mp3))

    fun tag(): ByteArray = nativeTag(handle) ?: ByteArray(0)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("lame_jni")
        }

        @JvmStatic private external fun nativeOpen(rate: Int, channels: Int, kbps: Int): Long
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int): ByteArray?
        @JvmStatic private external fun nativeFlush(handle: Long): ByteArray?
        @JvmStatic private external fun nativeTag(handle: Long): ByteArray?
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

internal fun wavHeader(rate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
    val data = dataBytes.coerceAtMost(0xFFFFFFFFL - 36).toInt()
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        .put("data".toByteArray()).putInt(data).array()
}

internal fun pcm16(samples: ShortArray): ByteArray =
    ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(samples) }.array()

internal fun pcm24(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 3)
    for (i in samples.indices) {
        val v = (samples[i] * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
        out[3 * i] = v.toByte(); out[3 * i + 1] = (v shr 8).toByte(); out[3 * i + 2] = (v shr 16).toByte()
    }
    return out
}

internal fun monoFloats(stereo: FloatArray, channels: Int): FloatArray =
    if (channels != 1) stereo else FloatArray(stereo.size / 2) { (stereo[2 * it] + stereo[2 * it + 1]) / 2 }

internal fun flacStreamHeader(config: ByteArray, totalSamples: Long, minFrame: Int, maxFrame: Int): ByteArray {
    require(config.size >= 42 && String(config, 0, 4, Charsets.US_ASCII) == "fLaC") { "The FLAC encoder gave no stream header" }
    val out = config.copyOf()
    var at = 4
    while (at + 4 <= out.size) {
        val length = ((out[at + 1].toInt() and 0xFF) shl 16) or ((out[at + 2].toInt() and 0xFF) shl 8) or (out[at + 3].toInt() and 0xFF)
        val last = at + 4 + length >= out.size
        out[at] = ((out[at].toInt() and 0x7F) or if (last) 0x80 else 0).toByte()
        if (last) break
        at += 4 + length
    }
    val info = 8
    fun put24(offset: Int, value: Int) {
        out[info + offset] = (value shr 16).toByte(); out[info + offset + 1] = (value shr 8).toByte(); out[info + offset + 2] = value.toByte()
    }
    put24(4, minFrame.coerceIn(0, 0xFFFFFF))
    put24(7, maxFrame.coerceIn(0, 0xFFFFFF))
    out[info + 13] = ((out[info + 13].toInt() and 0xF0) or ((totalSamples ushr 32).toInt() and 0x0F)).toByte()
    for (k in 0 until 4) out[info + 14 + k] = (totalSamples ushr (24 - 8 * k)).toByte()
    return out
}

internal class FlacSink(private val fd: FileDescriptor, private val totalSamples: Long) : SampleSink {
    private val out = BufferedOutputStream(FileOutputStream(fd), 1 shl 16)
    private var header = ByteArray(0)
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0

    override fun addTrack(format: MediaFormat): Int {
        header = format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } ?: error("The FLAC encoder gave no stream header")
        flacStreamHeader(header, 0, 0, 0)
        return 0
    }

    override fun start() = out.write(header)

    override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        data.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(bytes)
        out.write(bytes)
        minFrame = minOf(minFrame, info.size)
        maxFrame = maxOf(maxFrame, info.size)
    }

    override fun stop() {
        out.flush()
        val patched = flacStreamHeader(header, totalSamples, if (maxFrame == 0) 0 else minFrame, maxFrame)
        Os.pwrite(fd, patched, 0, patched.size, 0)
    }

    override fun release() {}
}

internal suspend fun exportAudio(s: ExportSettings, totalUs: Long, mix: AudioMix, out: FileDescriptor, progress: (Float) -> Unit) =
    withContext(Dispatchers.IO) {
        val rate = audioRate(s)
        val channels = s.audio.channels
        val totalFrames = totalUs * rate / 1_000_000
        when (s.audioFormat) {
            AudioFormat.Wav16, AudioFormat.Wav24 -> writeWav(s.audioFormat == AudioFormat.Wav24, rate, channels, totalFrames, mix, out, progress)
            AudioFormat.Mp3 -> writeMp3(rate, channels, s.audio.bitrateKbps, totalFrames, mix, out, progress)
            AudioFormat.Flac -> encodeAudio(flacEncoder(rate, channels), FlacSink(out, totalFrames), null, totalUs, rate, channels, false, mix, progress)
            AudioFormat.M4a -> encodeAudio(createAudioEncoder(AudioSettings(AudioCodec.Aac, s.audio.bitrateKbps, rate, channels)),
                MuxerSink(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), aacDelayFrames(null), totalUs, rate, channels, false, mix, progress)
            AudioFormat.Ogg -> encodeAudio(createAudioEncoder(AudioSettings(AudioCodec.Opus, s.audio.bitrateKbps, rate, channels)),
                MuxerSink(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG), null, totalUs, rate, channels, true, mix, progress)
        }
        progress(1f)
    }

private const val BlockFrames = 22050

private suspend fun writeWav(deep: Boolean, rate: Int, channels: Int, totalFrames: Long, mix: AudioMix, fd: FileDescriptor, progress: (Float) -> Unit) {
    val bits = if (deep) 24 else 16
    val out = BufferedOutputStream(FileOutputStream(fd), 1 shl 16)
    out.write(wavHeader(rate, channels, bits, 0))
    var written = 0L
    var frame = 0L
    while (frame < totalFrames) {
        currentCoroutineContext().ensureActive()
        val next = minOf(totalFrames, frame + BlockFrames)
        val from = frame * 1_000_000 / rate
        val to = next * 1_000_000 / rate
        val bytes = if (deep) {
            val floats = (mix as? FloatMix)?.floats(from, to) ?: mix(from, to).let { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } }
            pcm24(monoFloats(floats, channels))
        } else pcm16(toChannels(mix(from, to), channels))
        out.write(bytes)
        written += bytes.size
        frame = next
        ensureStorage()
        progress(frame.toFloat() / totalFrames * 0.99f)
    }
    out.flush()
    val header = wavHeader(rate, channels, bits, written)
    Os.pwrite(fd, header, 0, header.size, 0)
}

private suspend fun writeMp3(rate: Int, channels: Int, kbps: Int, totalFrames: Long, mix: AudioMix, fd: FileDescriptor, progress: (Float) -> Unit) {
    Mp3Encoder(rate, channels, kbps).use { encoder ->
        val out = BufferedOutputStream(FileOutputStream(fd), 1 shl 16)
        var frame = 0L
        while (frame < totalFrames) {
            currentCoroutineContext().ensureActive()
            val next = minOf(totalFrames, frame + BlockFrames)
            val pcm = toChannels(mix(frame * 1_000_000 / rate, next * 1_000_000 / rate), channels)
            out.write(encoder.encode(pcm, pcm.size / channels))
            frame = next
            ensureStorage()
            progress(frame.toFloat() / totalFrames * 0.99f)
        }
        out.write(encoder.flush())
        out.flush()
        val tag = encoder.tag()
        if (tag.isNotEmpty()) Os.pwrite(fd, tag, 0, tag.size, 0)
    }
}

private fun flacEncoder(rate: Int, channels: Int): MediaCodec {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
    try {
        codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    } catch (t: Throwable) {
        codec.release()
        throw t
    }
}

private suspend fun encodeAudio(
    codec: MediaCodec, sink: SampleSink, primingFrames: Int?, totalUs: Long, rate: Int, channels: Int, zeroStart: Boolean,
    mix: AudioMix, progress: (Float) -> Unit,
) {
    val stop = AtomicBoolean()
    val failure = AtomicReference<Throwable>()
    fun fail(t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
    val mux = Mux(sink, 1, ::fail)
    val extra = AtomicInteger(primingFrames ?: if (zeroStart) 312 else 0)
    val fed = AtomicLong()
    val total = aacInputFrames(totalUs, extra.get(), rate).coerceAtLeast(1)
    val workers = listOf(
        thread(name = "am-audio-out") {
            try { drain(codec, mux, stop, 0, extra.takeIf { primingFrames != null }, totalUs, rate = rate, zeroStart = zeroStart) } catch (t: Throwable) { fail(t) }
        },
        thread(name = "am-audio-in") {
            try { feedAudio(codec, mix, totalUs, stop, extra, rate, channels) { fed.set(it) } } catch (t: Throwable) { fail(t) }
        },
    )
    try {
        while (workers.any { it.isAlive }) {
            currentCoroutineContext().ensureActive()
            progress(fed.get().toFloat() / total * 0.99f)
            ensureStorage()
            delay(50)
        }
        failure.get()?.let { throw it }
        mux.finish()
    } finally {
        withContext(NonCancellable) {
            stop.set(true)
            workers.forEach { it.join() }
            runCatching { codec.stop() }
            codec.release()
            mux.release()
        }
    }
}
