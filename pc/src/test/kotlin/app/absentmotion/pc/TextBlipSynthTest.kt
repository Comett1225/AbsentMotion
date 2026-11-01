package app.absentmotion.pc

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.media.TextBlipSynth
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TextBlipSynthTest {
    @Test fun followsNativeTimingAndRendersInBlocks() {
        val layer = Layer(XmlNode.parse("""<text id="1" startTime="0" endTime="1999"><content>Hello, world!</content><effect id="absent.textblips"><property name="speed" value="5"/><property name="variation" value="0"/></effect></text>"""))
        val clip = TextBlipSynth.blips(layer)!!
        val native = DesktopNative.blipTimes("Hello, world!", 5f, .25f, 1).filter { it < 1.999f }
        assertEquals(native, clip.times)
        val full = TextBlipSynth.render(clip.times, clip.voice, clip.volume, clip.pitch, clip.variation, clip.seed)
        val blockSize = 8192
        val blocks = FloatArray(full.size)
        for (from in full.indices step blockSize) {
            val block = TextBlipSynth.render(clip.times, clip.voice, clip.volume, clip.pitch, clip.variation, clip.seed, from, minOf(blockSize, full.size - from))
            block.copyInto(blocks, from)
        }
        assertArrayEquals(full, blocks, 0f)
        clip.times.forEach { time ->
            val start = (time * TextBlipSynth.RATE).toInt()
            val onset = (start until minOf(full.size, start + 441)).firstOrNull { abs(full[it]) > .01f }
            assertNotNull(onset)
            assertTrue((onset!! - start).toDouble() / TextBlipSynth.RATE < .01)
        }
    }
}
