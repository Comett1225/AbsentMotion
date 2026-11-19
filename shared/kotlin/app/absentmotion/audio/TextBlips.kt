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
    const val SKELETON = 8
    const val CUSTOM = 9
    private const val MOST_SECONDS = 2
    private const val KEEP = 200
    private const val BLOCK = RATE
    private const val GRACE_MS = 3_600_000L
    private val lock = Any()
    @Volatile var dir: File? = null
    @Volatile var context: android.content.Context? = null
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
        val custom = if (voice == CUSTOM) (effect["src"] ?: return null) else null
        val pitch = value("pitch", 0f)
        val variation = value("variation", 1f).coerceAtLeast(0f)
        val seed = value("seed", 1f).roundToInt()
        val key = "$voice|$volume|$pitch|$variation|$seed|${times.joinToString(",")}" + (custom?.let { "|$it" } ?: "")
        val file = File(folder, "blips-${Integer.toHexString(key.hashCode())}-${key.length}.wav")
        fun build(): Boolean {
            if (file.isFile) { file.setLastModified(System.currentTimeMillis()); return true }
            return synchronized(lock) {
                if (!file.isFile) runCatching {
                    val sound = custom?.let { uri -> context?.let { soundOf(it, uri) } ?: return@runCatching }
                    folder.mkdirs()
                    sweep(folder)
                    val temp = File.createTempFile("blips", ".part", folder)
                    try {
                        temp.outputStream().buffered().use { write(it, times, voice, volume, pitch, variation, seed, sound) }
                        temp.renameTo(file)
                    } finally {
                        temp.delete()
                    }
                }
                file.isFile
            }
        }
        if (!file.isFile && !wait) {
            synchronized(pending) { pending[layer.id] = { build() } }
            worker.execute(::drain)
            return null
        }
        if (!build()) return null
        val plain = XmlNode("text", mapOf("id" to "${layer.id or (1L shl 62)}", "startTime" to "${layer.startTime}", "endTime" to "${layer.endTime}"), listOfNotNull(layer.node.child("gain")))
        val durationMs = if (custom == null) (times.last() + 0.3) * 1000.0 else (file.length() - 44) / 2.0 / RATE * 1000.0
        return AudioClip(Layer(plain), Uri.fromFile(file).toString(), track, durationMs)
    }

    private fun sweep(folder: File) {
        val stale = System.currentTimeMillis() - GRACE_MS
        folder.listFiles { f -> f.extension == "wav" || f.extension == "part" }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { if (it.lastModified() < stale) it.delete() }
    }

    fun samples(times: List<Float>, tail: Int = RATE * 3 / 10): Int = ((times.lastOrNull() ?: 0f) * RATE).toInt() + tail

    fun tail(sound: FloatArray?, pitch: Float, variation: Float): Int =
        if (sound == null) RATE * 3 / 10 else maxOf(RATE * 3 / 10, kotlin.math.ceil(sound.size / 2f.pow((pitch - variation) / 12f)).toInt())

    fun render(
        times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int,
        from: Int = 0, length: Int = -1, sound: FloatArray? = null,
    ): FloatArray {
        val count = if (length < 0) samples(times, tail(sound, pitch, variation)) - from else length
        val (hz, seconds) = spec(voice)
        val n = (seconds * RATE).toInt()
        val out = FloatArray(count)
        times.forEachIndexed { index, at ->
            val start = (at * RATE).toInt() - from
            if (sound != null) {
                val random = Random((seed * 31L + voice) * 1_000_003L + index)
                val shift = 2f.pow((pitch + (random.nextFloat() * 2 - 1) * variation) / 12f)
                play(out, start, sound, shift, volume)
            } else if (start < count && start + n > 0) {
                val random = Random((seed * 31L + voice) * 1_000_003L + index)
                val shift = 2f.pow((pitch + (random.nextFloat() * 2 - 1) * variation) / 12f)
                blip(out, start, voice, hz * shift, n, volume, random)
            }
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    private fun spec(voice: Int): Pair<Float, Float> = when (voice) {
        SKELETON -> 230f to 0.052f
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
                SKELETON -> (if (p < 0.3f) 0.5f else -0.21f) + (if (phase * 2 % 1.0 < 0.5) 0.08f else -0.08f)
                else -> if (p < 0.5f) 0.4f else -0.4f
            }
            if (at >= 0) out[at] += s * envelope * volume
        }
    }

    private fun play(out: FloatArray, start: Int, sound: FloatArray, shift: Float, volume: Float) {
        val n = (sound.size / shift).toInt()
        for (i in maxOf(0, -start) until n) {
            val at = start + i
            if (at >= out.size) break
            val p = i * shift
            val j = p.toInt()
            if (j >= sound.size) break
            val next = if (j + 1 < sound.size) sound[j + 1] else 0f
            out[at] += (sound[j] + (next - sound[j]) * (p - j)) * volume
        }
    }

    fun soundOf(context: android.content.Context, uri: String): FloatArray? = runCatching {
        PcmDecoder(context, uri).use { decoder ->
            var rate = 0
            var mono = FloatArray(0)
            var n = 0
            while (true) {
                val chunk = decoder.next() ?: break
                if (rate == 0) rate = chunk.rate
                val take = minOf(chunk.frames, rate * MOST_SECONDS - n)
                if (rate <= 0 || take <= 0) break
                if (mono.size < n + take) mono = mono.copyOf(maxOf(mono.size * 2, n + take))
                for (i in 0 until take) mono[n + i] = (chunk.pcm[2 * i] + chunk.pcm[2 * i + 1]) / 65536f
                n += take
            }
            if (n == 0) null else resampled(mono, n, rate)
        }
    }.getOrNull()

    fun resampled(source: FloatArray, count: Int, rate: Int): FloatArray {
        if (rate == RATE) return source.copyOf(count)
        val step = rate.toDouble() / RATE
        return FloatArray((count * RATE.toLong() / rate).toInt().coerceAtLeast(1)) { i ->
            val p = i * step
            val j = p.toInt().coerceAtMost(count - 1)
            val next = source[minOf(j + 1, count - 1)]
            source[j] + (next - source[j]) * (p - j).toFloat()
        }
    }

    private fun write(out: OutputStream, times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int, sound: FloatArray? = null) {
        val total = samples(times, tail(sound, pitch, variation))
        val data = total * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data)
        out.write(header.array())
        val bytes = ByteBuffer.allocate(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (from in 0 until total step BLOCK) {
            val block = render(times, voice, volume, pitch, variation, seed, from, minOf(BLOCK, total - from), sound)
            bytes.clear()
            for (s in block) bytes.putShort((s * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            out.write(bytes.array(), 0, block.size * 2)
        }
    }

    fun wav(times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int, sound: FloatArray? = null): ByteArray =
        ByteArrayOutputStream().also { write(it, times, voice, volume, pitch, variation, seed, sound) }.toByteArray()
}
