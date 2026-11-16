package app.absentmotion.audio

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import app.absentmotion.media.MediaEngine
import app.absentmotion.media.MediaStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
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
    private val file: File = Uri.parse(openableUri(uri)).toFile() ?: throw java.io.FileNotFoundException(uri)
    private val chunk = PcmChunk()
    private val channels: Int
    private var stream: MediaStream? = null
    private var fromUs = 0L
    private var frames = 0L
    private var ended = false
    private var bytes = ByteArray(0)
    private val shiftUs: Long

    override val durationUs: Long

    init {
        val probe = DeskProbe.of(file)
        val stream = probe.audio ?: error(AppText.get(R.string.ai_no_audio_track, uri))
        shiftUs = ((probe.video ?: stream).startUs - probe.startUs).coerceAtLeast(0)
        durationUs = stream.durationUs
        channels = stream.channels.coerceIn(1, 2)
        chunk.rate = stream.sampleRate.takeIf { it > 0 } ?: 48_000
    }

    override fun seek(us: Long) {
        stop()
        fromUs = us.coerceAtLeast(0)
        frames = 0
        ended = false
    }

    override fun next(): PcmChunk? {
        if (ended) return null
        val source = (stream ?: start()).input
        val frameBytes = 4 * channels
        val want = CHUNK_FRAMES * frameBytes
        if (bytes.size < want) bytes = ByteArray(want)
        val got = source.readNBytes(bytes, 0, want) / frameBytes
        if (got == 0) {
            ended = true
            stop()
            return null
        }
        fill(got)
        chunk.ptsUs = fromUs + frames * 1_000_000L / chunk.rate
        frames += got
        return chunk
    }

    private fun start(): MediaStream {
        val at = fromUs + shiftUs
        val pre = maxOf(0L, at - PREROLL_US)
        return MediaEngine.current.pcm(file, pre, at - pre, chunk.rate, channels).also { stream = it }
    }

    private fun stop() {
        stream?.close()
        stream = null
    }

    private fun fill(count: Int) {
        val src = ByteBuffer.wrap(bytes, 0, count * 4 * channels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (chunk.pcm.size < count * 2) chunk.pcm = ShortArray(count * 2)
        val out = chunk.pcm
        for (f in 0 until count) {
            val l = src.get(f * channels)
            val r = if (channels > 1) src.get(f * channels + 1) else l
            out[2 * f] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * f + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        chunk.frames = count
    }

    override fun close() = stop()

    private companion object {
        const val CHUNK_FRAMES = 1024
        const val PREROLL_US = 500_000L
    }
}
