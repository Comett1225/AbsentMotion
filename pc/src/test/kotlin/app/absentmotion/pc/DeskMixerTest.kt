package app.absentmotion.pc

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.AudioEngine
import app.absentmotion.audio.DeskOutput
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class DeskMixerTest {
    init {
        System.setProperty("am.data", File("build/mixer-audio-data").absolutePath)
        DesktopNative.hashCode()
    }

    @Test fun eachLayerHasItsOwnLevelAndSoloMutesTheOthers() {
        val sound = Uri.fromFile(File("../testdata/media/test-audio.m4a").canonicalFile)
        val scene = Scene.parse("""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Mix" width="1080" height="1920" totalTime="9999" fps="30" bgcolor="#ff000000">
  <audio id="1" label="A" startTime="0" endTime="9999" src="$sound"><gain value="1.000000" /></audio>
  <audio id="2" label="B" startTime="0" endTime="9999" src="$sound"><gain value="0.500000" /></audio>
</scene>""")
        var loudest = 0f
        DeskOutput.capture = { block, frames -> for (i in 0 until 2 * frames) loudest = maxOf(loudest, abs(block[i])) }
        val engine = AudioEngine(Context())
        try {
            engine.setScene(scene)
            Thread.sleep(500)
            fun listen(ms: Long): Triple<Float, Float, Float> {
                var a = 0f
                var b = 0f
                var ratio = 0f
                val until = System.nanoTime() + ms * 1_000_000
                while (System.nanoTime() < until) {
                    val la = engine.mix.level(1)
                    val lb = engine.mix.level(2)
                    if (la > a) { a = la; ratio = if (la > 0f) lb / la else 0f }
                    b = maxOf(b, lb)
                    Thread.sleep(3)
                }
                return Triple(a, b, ratio)
            }
            assertTrue(engine.play(0.0))
            val (a, b, ratio) = listen(1500)
            println("levels: A %.3f, B %.3f, B/A at A's peak %.3f, master %.3f".format(a, b, ratio, engine.level))
            assertTrue("A is heard: $a", a > 0.05f)
            assertTrue("B is heard: $b", b > 0.02f)
            assertEquals("B is at half volume", 0.5f, ratio, 0.05f)

            engine.mix.solo = longArrayOf(2)
            Thread.sleep(300)
            loudest = 0f
            val (soloA, soloB, _) = listen(1500)
            println("solo B: A %.3f, B %.3f, output peak %.3f".format(soloA, soloB, loudest))
            assertEquals(0f, soloA, 0f)
            assertTrue(soloB > 0.02f)
            assertTrue("only B reaches the output", loudest <= soloB * 1.6f + 0.01f)
            engine.mix.solo = LongArray(0)
            engine.stop()
            Thread.sleep(100)
            assertEquals(0f, engine.level, 0f)
        } finally {
            DeskOutput.capture = null
            engine.close()
        }
    }
}
