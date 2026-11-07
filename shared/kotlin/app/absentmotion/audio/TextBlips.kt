package app.absentmotion.audio

import android.net.Uri
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Layer
import app.absentmotion.model.LayerTrack
import app.absentmotion.model.XmlNode
import app.absentmotion.model.content
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.valueAt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal object TextBlips {
    const val RATE = 44100
    private const val KEEP = 200
    private const val BLOCK = RATE
    private const val GRACE_MS = 3_600_000L
    private val lock = Any()
    @Volatile var dir: File? = null
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val pending = LinkedHashMap<Long, () -> Unit>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "text-blips").apply { isDaemon = true } }

    private fun drain() {
        val jobs = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        if (jobs.isEmpty()) return
        jobs.forEach { it() }
        listeners.forEach { it() }
    }

    fun clip(layer: Layer, track: LayerTrack?, wait: Boolean = true): AudioClip? {
        if (layer.kind != "text" || layer.hidden || layer.element(GainRef)?.mutes == true) return null
        val effect = layer.effects.lastOrNull { it["id"] == "absent.textblips" && it["hidden"] != "true" } ?: return null
        val folder = dir ?: return null
        fun value(name: String, fallback: Float) = effect.effectProperty(name)?.valueAt(0f)?.finiteFloat() ?: fallback
        val volume = value("volume", 0.6f).coerceIn(0f, 1f)
        if (volume <= 0f) return null
        val length = (layer.endTime - layer.startTime) / 1000f
        val delay = value("delay", 0f)
        val times = runCatching {
            NativeEngine.blipTimes(layer.content, value("speed", 30f), value("pause", 0.25f), value("every", 1f).roundToInt())
        }.getOrNull()?.map { it + delay }?.filter { it >= 0f && it < length } ?: return null
        if (times.isEmpty()) return null
        val voice = value("voice", 0f).roundToInt()
        val pitch = value("pitch", 0f)
        val variation = value("variation", 1f).coerceAtLeast(0f)
        val seed = value("seed", 1f).roundToInt()
        val key = "$voice|$volume|$pitch|$variation|$seed|${times.joinToString(",")}"
        val file = File(folder, "blips-${Integer.toHexString(key.hashCode())}-${key.length}.wav")
        fun build() = synchronized(lock) {
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            else runCatching {
                folder.mkdirs()
                sweep(folder)
                val temp = File.createTempFile("blips", ".part", folder)
                try {
                    temp.outputStream().buffered().use { write(it, times, voice, volume, pitch, variation, seed) }
                    temp.renameTo(file)
                } finally {
                    temp.delete()
                }
            }
            file.isFile
        }
        if (!file.isFile && !wait) {
            synchronized(pending) { pending[layer.id] = { build() } }
            worker.execute(::drain)
            return null
        }
        if (!build()) return null
        val plain = XmlNode("text", mapOf("id" to "${layer.id or (1L shl 62)}", "startTime" to "${layer.startTime}", "endTime" to "${layer.endTime}"), listOfNotNull(layer.node.child("gain")))
        return AudioClip(Layer(plain), Uri.fromFile(file).toString(), track, (times.last() + 0.3) * 1000.0)
    }

    private fun sweep(folder: File) {
        val stale = System.currentTimeMillis() - GRACE_MS
        folder.listFiles { f -> f.extension == "wav" || f.extension == "part" }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { if (it.lastModified() < stale) it.delete() }
    }

    fun samples(times: List<Float>): Int = ((times.lastOrNull() ?: 0f) * RATE).toInt() + RATE * 3 / 10

    fun render(
        times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int,
        from: Int = 0, count: Int = samples(times) - from,
    ): FloatArray {
        val (hz, seconds) = spec(voice)
        val n = (seconds * RATE).toInt()
        val out = FloatArray(count)
        times.forEachIndexed { index, at ->
            val start = (at * RATE).toInt() - from
            if (start < count && start + n > 0) {
                val random = Random((seed * 31L + voice) * 1_000_003L + index)
                val shift = 2f.pow((pitch + (random.nextFloat() * 2 - 1) * variation) / 12f)
                blip(out, start, voice, hz * shift, n, volume, random)
            }
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    private fun spec(voice: Int): Pair<Float, Float> = when (voice) {
        1 -> 620f to 0.06f
        2 -> 150f to 0.075f
        3 -> 1100f to 0.035f
        4 -> 300f to 0.06f
        5 -> 1500f to 0.04f
        6 -> 140f to 0.03f
        7 -> 440f to 0.05f
        else -> 520f to 0.045f
    }

    private fun blip(out: FloatArray, start: Int, voice: Int, hz: Float, n: Int, volume: Float, random: Random) {
        var phase = 0.0
        for (i in 0 until n) {
            val at = start + i
            if (at >= out.size) break
            val t = i.toFloat() / RATE
            val left = 1f - i.toFloat() / n
            val envelope = minOf(1f, t / 0.002f) * left * left
            val f = hz * when (voice) {
                5 -> 1f + 0.5f * (1f - left)
                7 -> if (t < 0.016f) 1.5f else 1f
                else -> 1f
            }
            phase += f / RATE
            val p = (phase % 1.0).toFloat()
            val noise = if (voice == 6) random.nextFloat() * 2 - 1 else 0f
            val s = when (voice) {
                1 -> (sin(2 * PI * phase) + 0.25 * sin(4 * PI * phase)).toFloat() * 0.8f
                2 -> (if (p < 0.25f) 1f else -1f) * 0.45f + sin(PI * phase).toFloat() * 0.3f
                3 -> (1f - 4f * kotlin.math.abs(p - 0.5f)) * 0.9f
                4 -> (if (p < 0.5f) 1f else -1f) * (if (sin(2 * PI * 55 * t) >= 0) 0.5f else 0.15f)
                5 -> sin(2 * PI * phase).toFloat() * 0.75f
                6 -> noise * exp(-t / 0.004f) + sin(2 * PI * phase).toFloat() * 0.5f
                7 -> if (p < 0.125f) 0.5f else -0.07f
                else -> if (p < 0.5f) 0.4f else -0.4f
            }
            if (at >= 0) out[at] += s * envelope * volume
        }
    }

    private fun write(out: OutputStream, times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int) {
        val total = samples(times)
        val data = total * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data)
        out.write(header.array())
        val bytes = ByteBuffer.allocate(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (from in 0 until total step BLOCK) {
            val block = render(times, voice, volume, pitch, variation, seed, from, minOf(BLOCK, total - from))
            bytes.clear()
            for (s in block) bytes.putShort((s * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            out.write(bytes.array(), 0, block.size * 2)
        }
    }

    fun wav(times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int): ByteArray =
        ByteArrayOutputStream().also { write(it, times, voice, volume, pitch, variation, seed) }.toByteArray()
}
