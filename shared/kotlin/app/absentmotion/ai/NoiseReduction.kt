package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import android.content.Context
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.SincBank
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object NoiseReduction {
    init { System.loadLibrary("amengine") }
    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, pcm: FloatArray): FloatArray
    private external fun nativeDestroy(handle: Long)

    suspend fun run(context: Context, uri: String, source: NoiseSource, progress: AiProgress): AiMedia {
        val token = UUID.randomUUID()
        val decoded = File(context.cacheDir, "ai-input-$token.pcm")
        val cleaned = File(context.cacheDir, "ai-clean-$token.pcm")
        val output = aiFile(context, "wav")
        var success = false
        try {
            var rate = 0
            var frames = 0L
            var rumble: Rumble? = null
            PcmDecoder(context, uri).use { decoder ->
                DataOutputStream(decoded.outputStream().buffered()).use { pcm ->
                    var reported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val chunk = decoder.next() ?: break
                        if (rate == 0) {
                            rate = chunk.rate
                            val needed = (decoder.durationUs / 1e6 * (rate * 6L + 48000 * 4L)).toLong() + 50_000_000L
                            check(context.cacheDir.usableSpace >= needed) { AppText.get(R.string.ai_not_enough_storage, (needed / 1_000_000).toString()) }
                            if (source.rumbleHz > 0) rumble = Rumble(rate, source.rumbleHz)
                        }
                        check(rate > 0 && rate == chunk.rate) { AppText.get(R.string.ai_sample_rate_changed) }
                        for (i in 0 until chunk.frames) {
                            val sample = (chunk.pcm[2 * i].toInt() + chunk.pcm[2 * i + 1].toInt()) / 65536f
                            pcm.writeFloat(rumble?.next(sample) ?: sample)
                        }
                        frames += chunk.frames
                        check(frames * 2 <= 0xFFFFFFFFL - 36) { AppText.get(R.string.ai_recording_too_large) }
                        val percent = if (decoder.durationUs > 0) ((chunk.ptsUs * 30 / decoder.durationUs).toInt()).coerceIn(0, 30) else 0
                        if (percent != reported) { progress(percent / 100f, AppText.get(R.string.ai_decoding_audio)); reported = percent }
                    }
                }
            }
            check(frames > 0 && rate > 0) { AppText.get(R.string.ai_no_samples_decoded) }
            val noiseFrames = (frames.toDouble() * 48000 / rate).roundToLong().coerceAtLeast(1)
            val handle = nativeCreate()
            try {
                DataOutputStream(cleaned.outputStream().buffered()).use { pcm ->
                    var first = true
                    var written = 0L
                    var reported = -1
                    fun denoise(block: FloatArray) {
                        val samples = nativeProcess(handle, block)
                        if (first) { first = false; return }
                        val count = minOf(480L, noiseFrames - written).toInt()
                        for (i in 0 until count) pcm.writeFloat(samples[i])
                        written += count
                    }
                    resample(decoded, frames, rate, 48000, noiseFrames) { block, _, done ->
                        denoise(block)
                        val percent = (done * 60 / noiseFrames).toInt()
                        if (percent != reported) { progress(0.3f + percent / 100f, AppText.get(R.string.ai_reducing_noise)); reported = percent }
                    }
                    coroutineContext.ensureActive()
                    denoise(FloatArray(480))
                    check(written == noiseFrames)
                }
            } finally { nativeDestroy(handle) }
            output.outputStream().buffered().use { wav ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt((36 + frames * 2).toInt()).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt((frames * 2).toInt())
                wav.write(header.array())
                val bytes = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
                var reported = -1
                val original = if (source.blend < 1.0) DataInputStream(decoded.inputStream().buffered()) else null
                val keep = (1.0 - source.blend).toFloat()
                try {
                    resample(cleaned, noiseFrames, 48000, rate, frames) { block, count, done ->
                        bytes.clear()
                        for (i in 0 until count) {
                            val mixed = if (original == null) block[i] else block[i] * (1f - keep) + original.readFloat() * keep
                            bytes.putShort((mixed * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                        }
                        wav.write(bytes.array(), 0, count * 2)
                        val percent = (done * 10 / frames).toInt()
                        if (percent != reported) { progress(0.9f + percent / 100f, AppText.get(R.string.ai_saving_cleaned_audio)); reported = percent }
                    }
                } finally { original?.close() }
            }
            coroutineContext.ensureActive()
            return aiMedia(context, output, "audio/wav", mapOf("duration" to "${frames * 1_000_000 / rate}"))
                .also { success = true }
        } finally {
            decoded.delete(); cleaned.delete()
            if (!success) output.delete()
        }
    }

    private class Rumble(rate: Int, hz: Double) {
        private val b0: Double
        private val b1: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w = 2 * Math.PI * hz / rate
            val alpha = Math.sin(w) / (2 * 0.70710678)
            val a0 = 1 + alpha
            b0 = (1 + Math.cos(w)) / 2 / a0
            b1 = -(1 + Math.cos(w)) / a0
            a1 = -2 * Math.cos(w) / a0
            a2 = (1 - alpha) / a0
        }

        fun next(x: Float): Float {
            val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
            return y.toFloat()
        }
    }

    private suspend fun resample(file: File, frames: Long, sourceRate: Int, targetRate: Int, outputFrames: Long,
                                 consume: suspend (FloatArray, Int, Long) -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            val taps = Resampler.TAPS
            val windowFrames = 4096 + taps * 2
            val window = FloatArray(windowFrames * 2)
            val bytes = ByteArray(windowFrames * 4)
            val pair = FloatArray(2)
            val sinc = SincBank()
            val block = FloatArray(480)
            val step = sourceRate.toDouble() / targetRate
            var base = Long.MIN_VALUE
            var done = 0L
            while (done < outputFrames) {
                coroutineContext.ensureActive()
                block.fill(0f)
                val count = minOf(480L, outputFrames - done).toInt()
                for (i in 0 until count) {
                    val position = ((done + i) * step).coerceAtMost((frames - 1).toDouble())
                    val index = position.toLong()
                    val nextBase = index / 4096 * 4096 - taps
                    if (base != nextBase) {
                        base = nextBase
                        val first = base.coerceAtLeast(0)
                        val end = minOf(frames, base + windowFrames)
                        val valid = (end - first).toInt()
                        input.seek(first * 4)
                        input.readFully(bytes, 0, valid * 4)
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
                        for (j in 0 until windowFrames) {
                            val sample = buffer.get(((base + j).coerceIn(0, frames - 1) - first).toInt())
                            window[2 * j] = sample; window[2 * j + 1] = sample
                        }
                    }
                    Resampler.sample(window, (index - base).toInt(), (position - index).toFloat(), Resampler.ULTRA, step, pair, sinc)
                    block[i] = pair[0]
                }
                done += count
                consume(block, count, done)
            }
        }
    }
}
