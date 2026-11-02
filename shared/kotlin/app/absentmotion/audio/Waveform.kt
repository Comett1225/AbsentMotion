package app.absentmotion.audio

import app.absentmotion.platform.Context
import androidx.compose.runtime.mutableStateMapOf
import app.absentmotion.platform.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class AudioPeaks(val data: ByteArray) {
    fun peak(fromMs: Double, toMs: Double): Float {
        if (data.isEmpty()) return 0f
        val a = (fromMs / BIN_MS).toInt().coerceIn(0, data.size - 1)
        val b = (toMs / BIN_MS).toInt().coerceIn(a, data.size - 1)
        var best = 0
        for (i in a..b) best = maxOf(best, data[i].toInt() and 0xFF)
        return best / 255f
    }

    companion object {
        const val BIN_MS = 10
    }
}

object Waveforms {
    private val ready = mutableStateMapOf<String, AudioPeaks>()
    private val asked = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "am-waveform").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun peaks(uri: String): AudioPeaks? = ready[uri]

    fun request(context: Context, uri: String) {
        synchronized(asked) { if (!asked.add(uri)) return }
        val app = context.applicationContext
        worker.execute { ready[uri] = load(app, uri) }
    }

    private fun cacheFile(context: Context, uri: String) = File(File(context.cacheDir, "waveforms"), "${uri.hashCode().toUInt().toString(16)}-${uri.length}.bin")

    private fun load(context: Context, uri: String): AudioPeaks {
        val file = cacheFile(context, uri)
        val version = runCatching { app.absentmotion.media.fileVersion(context.contentResolver, android.net.Uri.parse(uri)) }.getOrNull().orEmpty()
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() == uri && input.readUTF() == version) return AudioPeaks(ByteArray(input.readInt()).also { input.readFully(it) })
            }
        }
        val peaks = runCatching { decode(context, uri) }.onFailure { Log.w("AMAudio", "No waveform for $uri", it) }.getOrDefault(AudioPeaks(ByteArray(0)))
        if (peaks.data.isNotEmpty()) runCatching {
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeUTF(uri)
                out.writeUTF(version)
                out.writeInt(peaks.data.size)
                out.write(peaks.data)
            }
        }
        return peaks
    }

    private fun decode(context: Context, uri: String): AudioPeaks {
        PcmDecoder(context, uri).use { decoder ->
            var bytes = ByteArray(if (decoder.durationUs > 0) (decoder.durationUs / 1000 / AudioPeaks.BIN_MS).toInt() + 16 else 4096)
            var frame = 0L
            var maxBin = 0
            while (true) {
                val chunk = decoder.next() ?: break
                val perBin = chunk.rate * AudioPeaks.BIN_MS / 1000.0
                for (i in 0 until chunk.frames) {
                    val bin = ((frame + i) / perBin).toInt()
                    if (bin >= bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, bin + 1))
                    val v = maxOf(abs(chunk.pcm[2 * i].toInt()), abs(chunk.pcm[2 * i + 1].toInt())) / 128
                    if (v > (bytes[bin].toInt() and 0xFF)) bytes[bin] = minOf(v, 255).toByte()
                    if (bin > maxBin) maxBin = bin
                }
                frame += chunk.frames
            }
            return AudioPeaks(bytes.copyOf(maxBin + 1))
        }
    }
}
