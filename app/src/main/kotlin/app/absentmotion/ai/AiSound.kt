package app.absentmotion.ai

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.audio.PcmChunk
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.audio.PcmSource
import app.absentmotion.audio.Resampler
import app.absentmotion.audio.SincBank
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioMix
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.Mux
import app.absentmotion.export.MuxerSink
import app.absentmotion.export.aacDelayFrames
import app.absentmotion.export.createAudioEncoder
import app.absentmotion.export.drain
import app.absentmotion.export.feedAudio
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private val CopiedSound = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB)

internal fun soundIsCopied(mime: String): Boolean = mime in CopiedSound

internal fun soundEndUs(durationUs: Long, limitUs: Long): Long =
    listOf(durationUs, limitUs).filter { it > 0 }.minOrNull() ?: (MAX_MATTE_SECONDS * 1_000_000L)

internal class AiSound(val uri: String, private val temp: File?) : Closeable {
    override fun close() {
        temp?.delete()
    }
}

internal class PcmFeed(private val source: PcmSource, first: PcmChunk, private val rate: Int, private val alive: () -> Unit = {}) {
    private var pending = first.pcm.copyOf(first.frames * 2)
    private var at = 0
    private var silence = first.ptsUs.coerceAtLeast(0) * rate / 1_000_000
    private var ended = false

    fun read(frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        var filled = 0
        while (filled < frames) {
            alive()
            if (silence > 0) {
                val n = minOf(silence, (frames - filled).toLong()).toInt()
                filled += n
                silence -= n
            } else if (at < pending.size) {
                val n = minOf(frames - filled, (pending.size - at) / 2)
                System.arraycopy(pending, at, out, filled * 2, n * 2)
                at += n * 2
                filled += n
            } else {
                val next = if (ended) null else source.next()
                if (next == null) {
                    ended = true
                    break
                }
                check(next.rate == rate) { AppText.get(R.string.ai_sample_rate_changed) }
                pending = next.pcm.copyOf(next.frames * 2)
                at = 0
            }
        }
        return out
    }

    val mix: AudioMix = { from, to -> read((Math.round(to * rate / 1e6) - Math.round(from * rate / 1e6)).toInt()) }
}

internal fun aacRate(rate: Int): Int = when {
    rate <= 48_000 -> rate
    rate % 44_100 == 0 -> 44_100
    else -> 48_000
}

internal class Downsampled(private val source: (Int) -> ShortArray, inRate: Int, private val rate: Int) {
    private val step = inRate.toDouble() / rate
    private var base = 1L - Resampler.TAPS
    private var filled = Resampler.TAPS - 1
    private var window = FloatArray(2 * filled)
    private var done = 0L
    private val pair = FloatArray(2)
    private val sinc = SincBank()

    fun read(frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        for (o in 0 until frames) {
            val position = (done + o) * step
            val j = position.toLong()
            val drop = (j - (Resampler.TAPS - 1) - base).toInt().coerceIn(0, filled)
            if (drop > 0) {
                System.arraycopy(window, 2 * drop, window, 0, 2 * (filled - drop))
                filled -= drop
                base += drop
            }
            while (base + filled <= j + Resampler.TAPS) {
                val pcm = source(BLOCK)
                val n = pcm.size / 2
                if (window.size < 2 * (filled + n)) window = window.copyOf(2 * (filled + n))
                for (k in 0 until 2 * n) window[2 * filled + k] = pcm[k] / 32768f
                filled += n
            }
            Resampler.sample(window, (j - base).toInt(), (position - j).toFloat(), Resampler.ULTRA, step, pair, sinc)
            out[2 * o] = (pair[0] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
            out[2 * o + 1] = (pair[1] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        done += frames
        return out
    }

    val mix: AudioMix = { from, to -> read((Math.round(to * rate / 1e6) - Math.round(from * rate / 1e6)).toInt()) }

    private companion object {
        const val BLOCK = 4096
    }
}

internal fun soundMime(context: Context, uri: String): String? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
        return (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.firstOrNull { it?.startsWith("audio/") == true }
    } finally {
        extractor.release()
    }
}

internal suspend fun keepSound(context: Context, uri: String, limitUs: Long): AiSound? {
    val mime = soundMime(context, uri) ?: return null
    if (soundIsCopied(mime)) return AiSound(uri, null)
    val temp = File(context.cacheDir, "ai-sound-${UUID.randomUUID()}.m4a")
    var done = false
    try {
        withContext(Dispatchers.IO) { convertSound(context, uri, temp, limitUs) }
        done = true
        return AiSound(Uri.fromFile(temp).toString(), temp)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(AppText.get(R.string.ai_sound_not_kept), e)
    } finally {
        if (!done) temp.delete()
    }
}

private suspend fun convertSound(context: Context, uri: String, out: File, limitUs: Long) {
    val job = currentCoroutineContext().job
    PcmDecoder(context, uri).use { decoder ->
        val first = checkNotNull(decoder.next()) { AppText.get(R.string.ai_no_samples_decoded) }
        val rate = first.rate
        val outRate = aacRate(rate)
        val totalUs = soundEndUs(decoder.durationUs, limitUs)
        val feed = PcmFeed(decoder, first, rate) { job.ensureActive() }
        val mix = if (outRate == rate) feed.mix else Downsampled(feed::read, rate, outRate).mix
        val stop = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val delay = AtomicInteger(aacDelayFrames(null))
        val codec = createAudioEncoder(AudioSettings(AudioCodec.Aac, if (outRate >= 32_000) 160 else 64, outRate, 2))
        val mux = try { Mux(MuxerSink(out.path), 1) { failure.compareAndSet(null, it); stop.set(true) } } catch (t: Throwable) { codec.release(); throw t }
        val output = thread(name = "am-sound-out") {
            try { drain(codec, mux, stop, 0, delay, totalUs, rate = outRate) } catch (t: Throwable) { failure.compareAndSet(null, t); stop.set(true) }
        }
        try {
            feedAudio(codec, mix, totalUs, stop, delay, outRate)
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
}
