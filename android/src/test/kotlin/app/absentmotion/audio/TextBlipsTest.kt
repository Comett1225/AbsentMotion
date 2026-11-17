package app.absentmotion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import app.absentmotion.model.storedMediaUris
import kotlin.math.abs
import kotlin.math.roundToInt

class TextBlipsTest {
    private val rate = TextBlips.RATE

    private fun FloatArray.peak(from: Int, to: Int) = (from until minOf(to, size)).maxOfOrNull { abs(this[it]) } ?: 0f

    @Test
    fun blipsSoundAtTheirTimesAndNowhereElse() {
        val out = TextBlips.render(listOf(0f, 0.5f), 0, 1f, 0f, 0f, 1)
        assertTrue(out.peak(0, rate / 50) > 0.1f)
        assertEquals(0f, out.peak(rate / 10, rate * 4 / 10))
        assertTrue(out.peak(rate / 2, rate / 2 + rate / 50) > 0.1f)
        assertTrue(out.all { it in -1f..1f })
    }

    @Test
    fun everyVoiceMakesASound() {
        for (voice in 0..7) assertTrue("voice $voice", TextBlips.render(listOf(0f), voice, 1f, 0f, 0f, 1).peak(0, rate / 10) > 0.05f)
    }

    @Test
    fun wavHasAHeaderAndTwoBytesPerSample() {
        val times = listOf(0.99f, 1.5f, 2.99f)
        val bytes = TextBlips.wav(times, 6, 0.8f, 0f, 2f, 3)
        val all = TextBlips.render(times, 6, 0.8f, 0f, 2f, 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(44 + all.size * 2, bytes.size)
        assertEquals(36 + all.size * 2, buffer.getInt(4))
        assertEquals(all.size * 2, buffer.getInt(40))
        for (i in all.indices) assertEquals(i.toString(), (all[i] * 32767f).roundToInt().toShort(), buffer.getShort(44 + 2 * i))
    }

    @Test
    fun blocksSoundTheSameAsOneRender() {
        val times = listOf(0f, 0.0113f, 0.3f, 0.5f)
        for (voice in 0..7) {
            val all = TextBlips.render(times, voice, 1f, 2f, 3f, 5)
            val joined = FloatArray(all.size)
            var from = 0
            while (from < all.size) {
                val part = TextBlips.render(times, voice, 1f, 2f, 3f, 5, from, minOf(333, all.size - from))
                part.copyInto(joined, from)
                from += part.size
            }
            assertArrayEquals("voice $voice", all, joined, 0f)
        }
    }

    private fun FloatArray.checksum(): Long = fold(17L) { h, f -> h * 31 + f.toRawBits() }

    @Test
    fun theOldVoicesSoundExactlyAsBefore() {
        val times = listOf(0f, 0.07f, 0.31f, 0.5f, 1.25f)
        val before = listOf(7011883229358669668L, -5729683122040377360L, 1631680559914804991L, 1541440842994419618L, -2676808228334166029L, -4874968313432440649L, -4973213163577713239L, 4482065435928281268L)
        for (voice in 0..7) assertEquals("voice $voice", before[voice], TextBlips.render(times, voice, 0.8f, 1.5f, 2f, 7).checksum())
    }

    @Test
    fun skeletonIsAShortLowBlipOfItsOwn() {
        val out = TextBlips.render(listOf(0f), TextBlips.SKELETON, 1f, 0f, 0f, 1)
        assertTrue(out.peak(0, rate * 3 / 100) > 0.2f)
        assertEquals(0f, out.peak(rate * 6 / 100, out.size))
        val rising = (1 until rate * 4 / 100).count { out[it - 1] <= 0f && out[it] > 0f }
        assertTrue("$rising rising crossings in 40 ms", rising in 7..11)
        for (voice in 0..7) assertTrue("voice $voice", !TextBlips.render(listOf(0f), voice, 1f, 0f, 0f, 1).contentEquals(out))
    }

    private val sound = FloatArray(rate / 10) { 0.5f * kotlin.math.sin(2 * Math.PI * 300 * it / rate).toFloat() }

    @Test
    fun aCustomSoundPlaysAtEveryBlip() {
        val out = TextBlips.render(listOf(0f, 0.5f), TextBlips.CUSTOM, 1f, 0f, 0f, 1, sound = sound)
        for (i in sound.indices) {
            assertEquals(sound[i], out[i], 1e-6f)
            assertEquals(sound[i], out[rate / 2 + i], 1e-6f)
        }
        assertEquals(0f, out.peak(sound.size + 1, rate / 2))
    }

    @Test
    fun aCustomSoundFollowsPitchVolumeAndKeepsItsWholeLength() {
        val up = TextBlips.render(listOf(0f), TextBlips.CUSTOM, 0.5f, 12f, 0f, 1, sound = sound)
        for (i in 0 until sound.size / 2 - 1) assertEquals(sound[2 * i] * 0.5f, up[i], 1e-5f)
        assertEquals(0f, up.peak(sound.size / 2 + 1, up.size))
        val long = FloatArray(rate) { 0.3f }
        val down = TextBlips.render(listOf(0f), TextBlips.CUSTOM, 1f, -12f, 0f, 1, sound = long)
        assertEquals(2 * rate, down.size)
        assertEquals(0.3f, down[2 * rate - 3], 1e-6f)
    }

    @Test
    fun customBlocksSoundTheSameAsOneRender() {
        val times = listOf(0f, 0.0113f, 0.3f, 0.5f)
        val all = TextBlips.render(times, TextBlips.CUSTOM, 1f, 2f, 3f, 5, sound = sound)
        val bytes = TextBlips.wav(times, TextBlips.CUSTOM, 1f, 2f, 3f, 5, sound)
        assertEquals(44 + all.size * 2, bytes.size)
        val joined = FloatArray(all.size)
        var from = 0
        while (from < all.size) {
            val part = TextBlips.render(times, TextBlips.CUSTOM, 1f, 2f, 3f, 5, from, minOf(333, all.size - from), sound)
            part.copyInto(joined, from)
            from += part.size
        }
        assertArrayEquals(all, joined, 0f)
    }

    @Test
    fun soundsAreResampledToTheBlipRate() {
        val half = FloatArray(100) { it / 100f }
        val out = TextBlips.resampled(half, half.size, rate / 2)
        assertEquals(200, out.size)
        assertEquals(half[10], out[20], 1e-6f)
        assertEquals((half[10] + half[11]) / 2, out[21], 1e-6f)
        assertArrayEquals(half, TextBlips.resampled(half, half.size, rate), 0f)
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun aCustomBlipSoundTravelsInAProjectBundle() {
        val files = tmp.newFolder()
        val blip = java.io.File(files, "media/p1/blip.wav").apply { parentFile.mkdirs(); writeBytes(TextBlips.wav(listOf(0f), 7, 1f, 0f, 0f, 1)) }
        val uri = blip.toURI().toString()
        val scene = app.absentmotion.model.Scene.parse(
            """<scene width="100" height="100" fps="30" totalTime="2000"><media uri="$uri" filename="blip.wav" title="blip.wav" type="audio/wav" duration="300000"/>""" +
                """<text id="1" startTime="0" endTime="2000"><content>Hi</content><effect id="absent.textblips" hidden="false" src="$uri"><property name="voice" type="float" value="9.000000"/></effect></text></scene>""",
        )
        assertEquals(setOf(uri), scene.node.storedMediaUris())
        val packed = java.io.ByteArrayOutputStream().also { out ->
            kotlinx.coroutines.runBlocking {
                app.absentmotion.export.writeProjectPackage(scene, scene.toXml(), out, app.absentmotion.library.MediaAccess.forPhone(files), { java.io.File(java.net.URI(it)).inputStream() }, {})
            }
        }.toByteArray()
        val other = tmp.newFolder()
        val store = app.absentmotion.library.ProjectStore(other)
        val report = app.absentmotion.library.Importer(store, access = app.absentmotion.library.MediaAccess.forPhone(other)).import { packed.inputStream() }.single()
        val loaded = store.load(report.id)!!
        val src = loaded.layers.single().node.children.single { it.name == "effect" }["src"]!!
        assertTrue(src != uri && src.startsWith(other.toURI().toString()))
        assertArrayEquals(blip.readBytes(), java.io.File(java.net.URI(src)).readBytes())
        assertEquals("blip.wav", loaded.media(src)?.filename)
    }
}
