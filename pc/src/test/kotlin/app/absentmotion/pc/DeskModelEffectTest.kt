package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskModelEffectTest {
    private val out = File("build/model-effect").canonicalFile

    private fun cube(): String {
        val file = File(out, "cube.obj")
        file.writeText(buildString {
            for (x in listOf(-1, 1)) for (y in listOf(-1, 1)) for (z in listOf(-1, 1)) append("v $x $y $z\n")
            listOf("1 2 4 3", "5 7 8 6", "1 5 6 2", "3 4 8 7", "1 3 7 5", "2 6 8 4").forEach { append("f $it\n") }
        })
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun scene(uri: String, effect: String) = Scene.parse("""<scene title="m" width="400" height="400" fps="30" totalTime="999" bgcolor="#ff000000">
<media uri="$uri" filename="cube.obj" title="cube.obj" type="model/obj" />
<model id="1" startTime="0" endTime="999" src="$uri"><transform><location value="200,200,0"/><tilt value="90,30"/></transform>
<property name="size" type="float" value="120"/><property name="ambient" type="float" value="1"/>$effect</model></scene>""")

    private fun bright(pixels: ByteArray): Int = (0 until 400 * 400).count { i -> (0..2).any { (pixels[i * 4 + it].toInt() and 255) > 128 } }

    @Test fun effectsOnAModelTurnedEdgeOnKeepTheWholeModel() = runBlocking {
        out.deleteRecursively()
        out.mkdirs()
        System.setProperty("am.data", out.resolve("data").path)
        val uri = cube()
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            fun render(effect: String): Int = runBlocking {
                val s = scene(uri, effect)
                bright(checkNotNull(engine.exportSession(s.toXml()) { still(s.frameStart(5), 400, 400) }))
            }
            val plain = render("")
            assertTrue("the cube is drawn: $plain", plain > 20_000)
            for ((effect, share) in listOf("pixelate" to 0.8, "vignette" to 0.3)) {
                val lit = render("""<effect id="absent.$effect"></effect>""")
                assertTrue("$effect keeps the cube: $lit of $plain bright pixels", lit >= plain * share)
            }
        } finally {
            engine.close()
        }
    }
}
