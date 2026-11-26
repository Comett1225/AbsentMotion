package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DeskNoiseModelTest {
    private fun model(values: Map<String, FloatArray> = emptyMap()): File {
        val tensors = mutableListOf("erb" to intArrayOf(32))
        fun conv(name: String, w: IntArray, out: Int, pointwise: Boolean) {
            tensors += "$name.w" to w
            if (pointwise) tensors += "$name.pw" to intArrayOf(out, out, 1, 1)
            tensors += "$name.scale" to intArrayOf(out)
            tensors += "$name.bias" to intArrayOf(out)
        }
        conv("enc.erb_conv0", intArrayOf(64, 1, 3, 3), 64, false)
        for (n in 1..3) conv("enc.erb_conv$n", intArrayOf(64, 1, 1, 3), 64, true)
        conv("enc.df_conv0", intArrayOf(64, 1, 3, 3), 64, true)
        conv("enc.df_conv1", intArrayOf(64, 1, 1, 3), 64, true)
        for (n in 0..3) conv("erb_dec.conv${n}p", intArrayOf(64, 1, 1, 1), 64, false)
        for (n in 1..3) conv("erb_dec.convt$n", intArrayOf(64, 1, 1, 3), 64, true)
        conv("erb_dec.conv0_out", intArrayOf(1, 64, 1, 3), 1, false)
        conv("df_dec.df_convp", intArrayOf(10, 32, 5, 1), 10, true)
        tensors += "enc.df_fc_emb.0.weight" to intArrayOf(32, 96, 16)
        for (part in listOf("enc", "erb_dec")) {
            tensors += "$part.emb_gru.linear_in.0.weight" to intArrayOf(16, 32, 16)
            tensors += "$part.emb_gru.linear_out.0.weight" to intArrayOf(16, 16, 32)
        }
        tensors += "df_dec.df_gru.linear_in.0.weight" to intArrayOf(8, 64, 32)
        tensors += "df_dec.df_skip.weight" to intArrayOf(16, 32, 16)
        tensors += "df_dec.df_out.0.weight" to intArrayOf(16, 16, 60)
        for ((gru, layers) in listOf("enc.emb_gru.gru" to 1, "erb_dec.emb_gru.gru" to 2, "df_dec.df_gru.gru" to 2)) {
            for (l in 0 until layers) {
                tensors += "$gru.weight_ih_l$l" to intArrayOf(768, 256)
                tensors += "$gru.weight_hh_l$l" to intArrayOf(768, 256)
                tensors += "$gru.bias_ih_l$l" to intArrayOf(768)
                tensors += "$gru.bias_hh_l$l" to intArrayOf(768)
            }
        }
        val bytes = ByteArrayOutputStream()
        fun put(size: Int, fill: ByteBuffer.() -> Unit) = bytes.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array())
        put(12) { put("AMDF".toByteArray()).putInt(1).putInt(tensors.size) }
        for ((name, dims) in tensors) {
            val count = dims.fold(1) { a, b -> a * b }
            val data = values[name] ?: if (name == "erb") FloatArray(32) { if (it < 31) 15f else 16f } else FloatArray(count)
            put(4 + name.length + 4 * dims.size + 4 * count) {
                putShort(name.length.toShort()).put(name.toByteArray()).put(0).put(dims.size.toByte())
                dims.forEach { putInt(it) }
                data.forEach { putFloat(it) }
            }
        }
        return File.createTempFile("noise-model", ".amdf").apply { deleteOnExit(); writeBytes(bytes.toByteArray()) }
    }

    private fun run(file: File, input: FloatArray): FloatArray {
        val engine = NoiseReduction.engine(file)
        val output = FloatArray(input.size)
        try {
            val blocks = input.size / 480
            for (b in 0 until blocks + engine.delay) {
                val block = if (b < blocks) input.copyOfRange(b * 480, b * 480 + 480) else FloatArray(480)
                val result = engine.process(block)
                if (b >= engine.delay) result.copyInto(output, (b - engine.delay) * 480)
            }
        } finally { engine.close() }
        return output
    }

    private fun tone(hz: Double, n: Int) = FloatArray(n) { (0.3 * sin(2 * PI * hz * it / 48_000)).toFloat() }

    @Test fun anOpenFilterGivesBackTheSoundInTime() {
        val open = model(mapOf("erb_dec.conv0_out.bias" to floatArrayOf(30f), "df_dec.df_convp.bias" to FloatArray(10) { if (it == 4) 1f else 0f }))
        val input = FloatArray(48_000) { (tone(440.0, 48_000)[it] + tone(9_000.0, 48_000)[it] * 0.5f) * (it % 9_600) / 9_600f }
        val output = run(open, input)
        assertEquals(0f, input.indices.maxOf { abs(output[it] - input[it]) }, 1e-4f)
    }

    @Test fun anEmptyFilterRemovesTheLowBandAndHalvesTheHighBand() {
        val low = tone(1_000.0, 48_000)
        val high = tone(10_000.0, 48_000)
        val output = run(model(), FloatArray(48_000) { low[it] + high[it] })
        val settled = 4_800 until 43_200
        assertTrue(settled.maxOf { abs(output[it] - 0.5f * high[it]) } < 1e-3f)
    }

    @Test fun theCleanedClipStaysInTimeWithTheOriginal() = runBlocking {
        val context = Context()
        val uri = Uri.fromFile(File("../testdata/media/test-audio.m4a").absoluteFile).toString()
        val open = model(mapOf("erb_dec.conv0_out.bias" to floatArrayOf(30f), "df_dec.df_convp.bias" to FloatArray(10) { if (it == 4) 1f else 0f }))
        val cleaned = NoiseReduction.run(context, uri, NoiseSource.Voice, 1.0, open) { _, _ -> }
        val original = NoiseReduction.run(context, uri, NoiseSource.Voice, 0.0, open) { _, _ -> }
        try {
            fun samples(file: File) = ByteBuffer.wrap(file.readBytes(), 44, file.length().toInt() - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                .let { b -> FloatArray(b.remaining()) { b.get(it) / 32768f } }
            val a = samples(cleaned.file)
            val b = samples(original.file)
            assertEquals(b.size, a.size)
            val power = sqrt(b.sumOf { it.toDouble() * it } / b.size)
            val error = sqrt(a.indices.sumOf { (a[it] - b[it]).toDouble().let { d -> d * d } } / a.size)
            assertTrue(power > 0.01)
            assertTrue(error < power * 0.05)
        } finally {
            cleaned.file.delete()
            original.file.delete()
        }
    }

    @Test fun aDamagedModelIsRefused() {
        val file = model()
        val cut = File.createTempFile("noise-cut", ".amdf").apply { deleteOnExit(); writeBytes(file.readBytes().copyOf(50_000)) }
        assertTrue(runCatching { NoiseReduction.engine(cut) }.exceptionOrNull() is IllegalStateException)
    }
}
