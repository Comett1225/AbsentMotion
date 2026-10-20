package app.absentmotion.audio

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder

internal class PcmChunk {
    var pcm = ShortArray(0)
    var frames = 0
    var rate = 0
    var ptsUs = 0L
}

internal interface PcmSource : Closeable {
    val durationUs: Long
    fun seek(us: Long)
    fun next(): PcmChunk?
}

internal class PcmDecoder(context: Context, uri: String) : PcmSource {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val chunk = PcmChunk()
    private var inputEnded = false
    private var outputEnded = false
    private var channels = 2
    private var float = false

    override val durationUs: Long

    init {
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error(AppText.get(R.string.ai_no_audio_track, uri))
            val format = extractor.getTrackFormat(track)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun seek(us: Long) {
        codec!!.flush()
        extractor.seekTo(us.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEnded = false
        outputEnded = false
    }

    override fun next(): PcmChunk? {
        val decoder = codec ?: return null
        while (!outputEnded) {
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 5_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(decoder.outputFormat)
                index >= 0 -> {
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (chunk.rate == 0) readFormat(decoder.outputFormat)
                    val has = info.size > 0
                    if (has) fill(decoder.getOutputBuffer(index)!!)
                    decoder.releaseOutputBuffer(index, false)
                    if (has && chunk.frames > 0) {
                        chunk.ptsUs = info.presentationTimeUs
                        return chunk
                    }
                }
            }
        }
        return null
    }

    private fun readFormat(format: MediaFormat) {
        chunk.rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }

    private fun fill(buffer: java.nio.ByteBuffer) {
        buffer.position(info.offset).limit(info.offset + info.size)
        buffer.order(ByteOrder.nativeOrder())
        val bytes = if (float) 4 else 2
        val frames = info.size / (bytes * channels)
        if (chunk.pcm.size < frames * 2) chunk.pcm = ShortArray(frames * 2)
        val out = chunk.pcm
        if (float) {
            val src = buffer.asFloatBuffer()
            for (f in 0 until frames) {
                val l = src.get(f * channels)
                val r = if (channels > 1) src.get(f * channels + 1) else l
                out[2 * f] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[2 * f + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            val src = buffer.asShortBuffer()
            for (f in 0 until frames) {
                val l = src.get(f * channels)
                out[2 * f] = l
                out[2 * f + 1] = if (channels > 1) src.get(f * channels + 1) else l
            }
        }
        chunk.frames = frames
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        extractor.release()
    }
}
