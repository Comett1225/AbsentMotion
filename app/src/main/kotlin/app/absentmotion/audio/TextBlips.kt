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
import java.io.File
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
    private const val KEEP = 40
    @Volatile var dir: File? = null

    fun clip(layer: Layer, track: LayerTrack?): AudioClip? {
        if (layer.kind != "text" || layer.hidden) return null
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
        if (!file.isFile) {
            folder.mkdirs()
            folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            val temp = File(folder, file.name + ".part")
            temp.writeBytes(wav(render(times, voice, volume, pitch, variation, seed)))
            if (!temp.renameTo(file)) return null
        }
        val plain = XmlNode("text", mapOf("id" to "${layer.id or (1L shl 62)}", "startTime" to "${layer.startTime}", "endTime" to "${layer.endTime}"))
        return AudioClip(Layer(plain), Uri.fromFile(file).toString(), track, (times.last() + 0.3) * 1000.0)
    }

    fun render(times: List<Float>, voice: Int, volume: Float, pitch: Float, variation: Float, seed: Int): FloatArray {
        val out = FloatArray(((times.lastOrNull() ?: 0f) * RATE).toInt() + RATE * 3 / 10)
        val random = Random(seed * 7919 + voice)
        for (at in times) {
            val shift = 2f.pow((pitch + (random.nextFloat() * 2 - 1) * variation) / 12f)
            blip(out, (at * RATE).toInt(), voice, shift, volume, random)
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    private fun blip(out: FloatArray, start: Int, voice: Int, shift: Float, volume: Float, random: Random) {
        val (hz, seconds) = when (voice) {
            1 -> 620f to 0.06f
            2 -> 150f to 0.075f
            3 -> 1100f to 0.035f
            4 -> 300f to 0.06f
            5 -> 1500f to 0.04f
            6 -> 140f to 0.03f
            7 -> 440f to 0.05f
            else -> 520f to 0.045f
        }
        val n = (seconds * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val at = start + i
            if (at !in out.indices) break
            val t = i.toFloat() / RATE
            val left = 1f - i.toFloat() / n
            val envelope = minOf(1f, t / 0.002f) * left * left
            val f = hz * shift * when (voice) {
                5 -> 1f + 0.5f * (1f - left)
                7 -> if (t < 0.016f) 1.5f else 1f
                else -> 1f
            }
            phase += f / RATE
            val p = (phase % 1.0).toFloat()
            val s = when (voice) {
                1 -> (sin(2 * PI * phase) + 0.25 * sin(4 * PI * phase)).toFloat() * 0.8f
                2 -> (if (p < 0.25f) 1f else -1f) * 0.45f + sin(PI * phase).toFloat() * 0.3f
                3 -> (1f - 4f * kotlin.math.abs(p - 0.5f)) * 0.9f
                4 -> (if (p < 0.5f) 1f else -1f) * (if (sin(2 * PI * 55 * t) >= 0) 0.5f else 0.15f)
                5 -> sin(2 * PI * phase).toFloat() * 0.75f
                6 -> (random.nextFloat() * 2 - 1) * exp(-t / 0.004f) + sin(2 * PI * phase).toFloat() * 0.5f
                7 -> if (p < 0.125f) 0.5f else -0.07f
                else -> if (p < 0.5f) 0.4f else -0.4f
            }
            out[at] += s * envelope * volume
        }
    }

    fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in samples) b.putShort((s * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        return b.array()
    }
}
