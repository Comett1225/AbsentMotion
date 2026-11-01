package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.model.Scene
import app.absentmotion.pc.media.AudioMixer
import app.absentmotion.pc.media.Exporter
import app.absentmotion.pc.media.TextBlipSynth
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.WorkspaceContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class ExportSoundTest {
    private fun scene() = Scene.parse("""<scene width="160" height="90" fps="30" totalTime="1999" bgcolor="#ff222222">
        <media uri="testdata/media/test-image.jpg" type="image/jpeg"/>
        <media uri="testdata/media/test-audio.m4a" type="audio/mp4"/>
        <shape id="1" startTime="0" endTime="1999" s=".rect" fillType="media" fillImage="testdata/media/test-image.jpg"><transform><location value="80,45,0"/></transform><property name="size" type="vec2" value="160,90"/></shape>
        <text id="2" startTime="0" endTime="1000" size="20" font="googlefonts?name=Roboto&amp;weight=400"><transform><location value="80,45,0"/></transform><content>Hello!</content><effect id="absent.textblips"><property name="speed" value="5"/><property name="variation" value="0"/></effect></text>
        <audio id="3" startTime="1200" endTime="1799" src="testdata/media/test-audio.m4a" inTime="500" outTime="3000"><gain value="0.8"/></audio>
        </scene>""")

    private fun command(vararg args: String): ByteArray {
        val process = ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val result = process.inputStream.readBytes()
        assertEquals(0, process.waitFor())
        return result
    }

    @Test fun exportsAudioAndChecksOnsets() {
        val folder = File("build/export-sound-test").also { it.deleteRecursively(); it.mkdirs() }
        val scene = scene()
        val file = folder.resolve("sound.mp4")
        val result = Exporter(File("../app/src/main/assets"), File("..")).export(scene, null, Exporter.Options(Exporter.Format.MP4, 160, 90, 30, 500_000, file))
        assertEquals(60, result.frames)
        val probe = JSONObject(command(DesktopPlatform.executable("ffprobe"), "-v", "error", "-show_streams", "-of", "json", file.absolutePath).toString(Charsets.UTF_8)).getJSONArray("streams")
        assertTrue((0 until probe.length()).any { probe.getJSONObject(it).optString("codec_name") == "aac" })
        val raw = command(DesktopPlatform.executable("ffmpeg"), "-v", "error", "-i", file.absolutePath, "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "f32le", "-")
        val samples = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
        val blips = TextBlipSynth.blips(scene.layer(2)!!)!!
        blips.times.forEach { time ->
            val start = (time * 48000).toInt()
            val onset = (start until minOf(samples.size, start + 480)).firstOrNull { abs(samples[it]) > .008f }
            assertNotNull("Missing onset at $time", onset)
            assertTrue(onset!! - start < 480)
        }
        val early = samples.sliceArray(1050 * 48 until 1180 * 48).maxOf { abs(it) }
        val active = samples.sliceArray(1300 * 48 until 1700 * 48).maxOf { abs(it) }
        val late = samples.sliceArray(1850 * 48 until 1950 * 48).maxOf { abs(it) }
        assertTrue("Audio layer should be audible", active > .01f)
        assertTrue("Audio should stop with the clip", early < .01f && late < .01f)
    }

    @Test fun exportsGifAndPngSequence() {
        val folder = File("build/export-formats-test").also { it.deleteRecursively(); it.mkdirs() }
        val exporter = Exporter(File("../app/src/main/assets"), File(".."))
        val scene = scene()
        for ((format, name) in listOf(Exporter.Format.GIF to "movie.gif", Exporter.Format.PNG to "frames")) {
            val file = folder.resolve(name)
            val result = exporter.export(scene, null, Exporter.Options(format, 160, 90, 10, 500_000, file, startMs = 500, endMs = 1499))
            assertEquals(10, result.frames)
            if (format == Exporter.Format.PNG) {
                assertEquals(10, file.listFiles()!!.size)
                assertEquals(160, ImageIO.read(file.resolve("frame_00001.png")).width)
            } else assertTrue(file.length() > 0)
        }
    }

    @Test fun mixerRespectsTrimAndLoop() {
        val source = Scene.parse("""<scene fps="30" totalTime="1999"><audio id="1" startTime="300" endTime="1699" src="testdata/media/test-audio.m4a" inTime="200" outTime="500" loop="true"><gain value="0.5"/></audio></scene>""")
        AudioMixer(source, null, File("..")).use { mixer ->
            val samples = mixer.mix(0, 48000 * 2)
            assertEquals(0f, samples.sliceArray(0 until 280 * 48 * 2).maxOf { abs(it) }, 0f)
            assertTrue(samples.sliceArray(400 * 48 * 2 until 500 * 48 * 2).maxOf { abs(it) } > .005f)
            for (i in 400 * 48 until 500 * 48) assertEquals(samples[i * 2], samples[(i + 300 * 48) * 2], .001f)
            assertEquals(0f, samples.sliceArray(1750 * 48 * 2 until samples.size).maxOf { abs(it) }, 0f)
        }
    }

    @Test fun videoAudioAndKeyframedGain() {
        val video = File("build/video-audio-test.mp4")
        command(DesktopPlatform.executable("ffmpeg"), "-v", "error", "-i", "../testdata/media/test-video.mp4", "-i", "../testdata/media/test-audio.m4a", "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-shortest", "-y", video.absolutePath)
        fun scene(gain: String) = Scene.parse("""<scene fps="30" totalTime="1999"><shape id="1" startTime="0" endTime="1999" s=".rect" fillType="media" fillVideo="build/video-audio-test.mp4" inTime="0" outTime="5000"><gain>$gain</gain></shape></scene>""")
        val fixed = scene("<kf t=\"0\" v=\"1\"/><kf t=\"1\" v=\"1\"/>")
        val faded = scene("<kf t=\"0\" v=\"0\"/><kf t=\"1\" v=\"1\"/>")
        AudioMixer(fixed, File("."), File("..")).use { full ->
            AudioMixer(faded, File("."), File("..")).use { fade ->
                val a = full.mix(24000, 4096)
                val b = fade.mix(24000, 4096)
                assertTrue(a.maxOf { abs(it) } > .001f)
                for (i in a.indices step 128) assertEquals(a[i] * ((24000 + i / 2) / 48000f / 1.999f), b[i], .003f)
            }
        }
    }

    @Test fun cancelledExportLeavesNoOutput() {
        val folder = File("build/export-cancel-test").also { it.deleteRecursively(); it.mkdirs() }
        val exporter = Exporter(File("../app/src/main/assets"), File(".."))
        val output = folder.resolve("cancel.gif")
        exporter.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { exporter.export(scene(), null, Exporter.Options(Exporter.Format.GIF, 160, 90, 30, 500_000, output)) }
        assertFalse(output.exists())
    }

    @Test fun rendererUsesInstalledFont() {
        val folder = File("build/font-render-test").also { it.deleteRecursively(); it.mkdirs() }.absoluteFile
        val font = folder.resolve("Library/Application Support/AbsentMotion/fonts/imported/Test.ttf")
        font.parentFile.mkdirs()
        File("../app/src/main/assets/fonts/Roboto/Roboto-Bold.ttf").copyTo(font)
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", folder.absolutePath)
            fun scene(fontName: String) = Scene.parse("""<scene width="320" height="100" fps="30" totalTime="999" bgcolor="#ffffffff"><text id="1" startTime="0" endTime="999" size="56" font="$fontName" fillType="color"><transform><location value="160,50,0"/></transform><fillColor value="#ff000000"/><content>Bold text</content></text></scene>""")
            app.absentmotion.pc.engine.DesktopEngine(File("../app/src/main/assets"), File("..")).use { engine ->
                engine.setScene(scene("imported?name=Test.ttf"))
                val imported = engine.render(0, 320, 100).getRGB(0, 0, 320, 100, null, 0, 320)
                engine.setScene(scene("googlefonts?name=Roboto&amp;weight=400"))
                val regular = engine.render(0, 320, 100).getRGB(0, 0, 320, 100, null, 0, 320)
                assertFalse(imported.contentEquals(regular))
            }
        } finally { System.setProperty("user.home", previous) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun showsExportDialog() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState().apply { open(scene(), File("../project.xml")) }
        setContent { WorkspaceContent(null, {}, null, mutableListOf(), project = state) }
        waitUntil(timeoutMillis = 30000) { onAllNodesWithTag("rendered-${state.scene.hashCode()}-${state.selected}").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Export", substring = false).performClick()
        onNodeWithTag("export-dialog").assertExists()
        onNodeWithTag("export-format").performClick()
        onNodeWithText("GIF", substring = false).performClick()
        onNodeWithTag("export-output").performTextClearance()
        onNodeWithTag("export-output").performTextInput("/tmp/movie.gif")
        val shots = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("export-dialog.png"))
        val output = File("build/dialog-test.gif").absoluteFile
        output.delete()
        onNodeWithTag("export-output").performTextClearance()
        onNodeWithTag("export-output").performTextInput(output.absolutePath)
        onNodeWithTag("start-export").performClick()
        waitUntil(timeoutMillis = 30000) { onAllNodesWithTag("export-message").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("export-message").assertTextContains("Export verified", substring = true)
        assertTrue(output.isFile)
    }
}
