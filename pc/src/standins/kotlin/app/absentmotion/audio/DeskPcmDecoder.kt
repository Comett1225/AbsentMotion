package app.absentmotion.audio

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri
import java.io.Closeable
import java.io.File
import java.io.InputStream
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
    private val mix: FloatArray?
    private var process: Process? = null
    private var input: InputStream? = null
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
        channels = stream.channels.coerceAtLeast(1)
        chunk.rate = stream.sampleRate.takeIf { it > 0 } ?: 48_000
        mix = if (channels > 2) downmixGains(channels, 0) else null
    }

    override fun seek(us: Long) {
        stop()
        fromUs = us.coerceAtLeast(0)
        frames = 0
        ended = false
    }

    override fun next(): PcmChunk? {
        if (ended) return null
        val source = input ?: start()
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

    private fun start(): InputStream {
        val command = mutableListOf(DeskMedia.ffmpeg, "-nostdin", "-v", "error")
        val at = fromUs + shiftUs
        val pre = maxOf(0L, at - PREROLL_US)
        if (pre > 0) command += listOf("-ss", seconds(pre))
        command += listOf("-i", file.path)
        if (at > pre) command += listOf("-ss", seconds(at - pre))
        command += listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-af", "aresample=async=1:first_pts=0", "-ar", "${chunk.rate}", "-ac", "$channels", "-f", "f32le", "-")
        val started = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        return started.inputStream.buffered(1 shl 16).also { input = it }
    }

    private fun seconds(us: Long) = "%.6f".format(java.util.Locale.ROOT, us / 1e6)

    private fun stop() {
        runCatching { input?.close() }
        process?.destroy()
        input = null
        process = null
    }

    private fun fill(count: Int) {
        val src = ByteBuffer.wrap(bytes, 0, count * 4 * channels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (chunk.pcm.size < count * 2) chunk.pcm = ShortArray(count * 2)
        val out = chunk.pcm
        val gains = mix
        if (gains != null) {
            for (f in 0 until count) {
                var l = 0f
                var r = 0f
                for (c in 0 until channels) {
                    val v = src.get(f * channels + c) * 32767f
                    l += gains[2 * c] * v
                    r += gains[2 * c + 1] * v
                }
                out[2 * f] = l.toInt().coerceIn(-32768, 32767).toShort()
                out[2 * f + 1] = r.toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            for (f in 0 until count) {
                val l = src.get(f * channels)
                val r = if (channels > 1) src.get(f * channels + 1) else l
                out[2 * f] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[2 * f + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        chunk.frames = count
    }

    override fun close() = stop()

    private companion object {
        const val CHUNK_FRAMES = 1024
        const val PREROLL_US = 500_000L
    }
}

private const val SIDE = 0.7071f

internal fun downmixGains(channels: Int, mask: Int): FloatArray {
    val layout = mask.takeIf { Integer.bitCount(it) == channels } ?: when (channels) {
        3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
        else -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    }
    val bits = (0 until 32).map { 1 shl it }.filter { layout and it != 0 }
    val gains = FloatArray(2 * channels)
    for (c in 0 until minOf(channels, bits.size)) {
        val (l, r) = when (bits[c]) {
            AudioFormat.CHANNEL_OUT_FRONT_LEFT, AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER -> 1f to 0f
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT, AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER -> 0f to 1f
            AudioFormat.CHANNEL_OUT_FRONT_CENTER -> SIDE to SIDE
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY -> 0f to 0f
            AudioFormat.CHANNEL_OUT_BACK_LEFT, AudioFormat.CHANNEL_OUT_SIDE_LEFT -> SIDE to 0f
            AudioFormat.CHANNEL_OUT_BACK_RIGHT, AudioFormat.CHANNEL_OUT_SIDE_RIGHT -> 0f to SIDE
            else -> 0.5f to 0.5f
        }
        gains[2 * c] = l
        gains[2 * c + 1] = r
    }
    return gains
}
