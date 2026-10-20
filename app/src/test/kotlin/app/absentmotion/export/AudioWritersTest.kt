package app.absentmotion.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWritersTest {
    @Test fun wavHeaderDescribesThePcm() {
        val header = ByteBuffer.wrap(wavHeader(48000, 2, 24, 600)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header.array(), 0, 4))
        assertEquals(636, header.getInt(4))
        assertEquals("WAVEfmt ", String(header.array(), 8, 8))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(48000, header.getInt(24))
        assertEquals(48000 * 6, header.getInt(28))
        assertEquals(6, header.getShort(32).toInt())
        assertEquals(24, header.getShort(34).toInt())
        assertEquals("data", String(header.array(), 36, 4))
        assertEquals(600, header.getInt(40))
    }

    @Test fun samplesAreLittleEndian() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), pcm16(shortArrayOf(0x1234, -1)))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 1, 0, 0x80.toByte(), 0, 0, 0), pcm24(floatArrayOf(1.5f, -1f, 0f)))
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), monoFloats(floatArrayOf(1f, 0f, -0.5f, 0f), 1), 0f)
    }

    @Test fun flacHeaderGetsTotalsAndLastBlockFlag() {
        val info = ByteArray(34).apply {
            this[0] = 0x10; this[2] = 0x10
            this[10] = 0x0A; this[11] = 0xC4.toByte(); this[12] = 0x42; this[13] = 0xF0.toByte()
        }
        val config = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + info + byteArrayOf(1, 0, 0, 4) + ByteArray(4)
        val out = flacStreamHeader(config, 0x1_2345_6789L, 14, 830)
        assertEquals(0, out[4].toInt())
        assertEquals(0x81, out[42].toInt() and 0xFF)
        assertEquals(listOf(0, 0, 14, 0, 3, 0x3E), (12..17).map { out[it].toInt() and 0xFF })
        assertEquals(0xF1, out[8 + 13].toInt() and 0xFF)
        assertEquals(listOf(0x23, 0x45, 0x67, 0x89), (22..25).map { out[it].toInt() and 0xFF })
        assertEquals(0x42, out[8 + 12].toInt())
    }

    @Test fun audioOnlySizesFollowTheFormat() {
        val s = ExportSettings(360, 30, audio = AudioSettings(bitrateKbps = 192, sampleRate = 48000, channels = 1))
        assertEquals(96000.0, audioBytesPerSecond(s.copy(audioFormat = AudioFormat.Wav16)), 0.0)
        assertEquals(144000.0, audioBytesPerSecond(s.copy(audioFormat = AudioFormat.Wav24)), 0.0)
        assertEquals(24000.0, audioBytesPerSecond(s.copy(audioFormat = AudioFormat.Mp3)), 0.0)
        assertEquals(48000, audioRate(s.copy(audioFormat = AudioFormat.Ogg, audio = s.audio.copy(sampleRate = 44100))))
        assertEquals(44100, audioRate(s.copy(audioFormat = AudioFormat.Mp3, audio = s.audio.copy(sampleRate = 44100))))
    }
}
