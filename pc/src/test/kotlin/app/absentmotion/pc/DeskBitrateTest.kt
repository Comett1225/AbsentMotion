package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.Judge
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.ExportDebug
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportSize
import app.absentmotion.export.exportVideo
import app.absentmotion.export.soleVideo
import app.absentmotion.export.videoBitrate
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import javax.imageio.ImageIO
import java.io.File
import java.io.RandomAccessFile

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class DeskBitrateTest {
    private val dir = File("build/bitrate-test").absoluteFile

    private fun run(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        assertEquals(0, process.waitFor())
    }

    private fun scene(video: File, ms: Int): Scene {
        val uri = video.toURI().toString().replaceFirst("file:/", "file:///")
        return Scene.parse("""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Bitrate" width="1280" height="720" exportWidth="1280" exportHeight="720" precompose="dynamicResolution" bgcolor="#ff000000" totalTime="$ms" fps="30" am="app.absentmotion" amplatform="android" retime="freeze" retimeAdaptFPS="false">
  <media uri="$uri" filename="${video.name}" title="${video.name}" type="video/mp4" size="${video.length()}" orientation="0" duration="${(ms + 1) * 1000L}" fps="30.00" infoUpdated="1760000000000" width="1280" height="720" />
  <shape id="9001" label="Video" startTime="0" endTime="$ms" fillType="media" mediaFillMode="fill" s=".rect" fillVideo="$uri" inTime="0" outTime="${ms + 1}" speed="1.000000">
    <transform>
      <location value="640.000000,360.000000,0.000000" />
    </transform>
    <property name="size" type="vec2" value="1280.000000,720.000000" />
  </shape>
</scene>""")
    }

    private fun videoBitsPerSecond(file: File): Double {
        val process = ProcessBuilder(Judge.ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=bit_rate,duration:format=duration", "-of", "json", file.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val json = JSONObject(process.inputStream.bufferedReader().readText()).also { process.waitFor() }
        json.getJSONArray("streams").getJSONObject(0).optString("bit_rate").toDoubleOrNull()?.let { return it }
        return file.length() * 8.0 / json.getJSONObject("format").getString("duration").toDouble()
    }

    @Test fun onlyAProjectThatIsOneVideoOffersSameAsSource() {
        val video = File("../testdata/media/test-video.mp4").canonicalFile
        assertEquals(video.toURI().toString().replaceFirst("file:/", "file:///"), soleVideo(scene(video, 5999)))
        val repo = File("..").canonicalFile
        val xml = repo.resolve("testdata/scenes/media.xml").readText()
        assertEquals("testdata/media/test-video.mp4", soleVideo(Scene.parse(xml)))
        val video8004 = Regex("<shape id=\"8004\".*?</shape>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.value
        assertNull(soleVideo(Scene.parse(xml.replace(video8004, video8004 + video8004.replace("id=\"8004\"", "id=\"8006\"")))))
        assertNull(soleVideo(Scene.parse(xml.replace(video8004, video8004.replace("endTime=\"4999\"", "endTime=\"1999\"")))))
        assertEquals(3_000_000, videoBitrate(1280, 720, ExportSettings(720, 30, bitrate = 3_000_000)))
    }

    @Test fun exportsStayCloseToTheChosenBitrate() = runBlocking {
        dir.deleteRecursively()
        dir.mkdirs()
        val noise = dir.resolve("noise.mp4")
        run(Judge.ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=6,noise=alls=40:allf=t", "-c:v", "libx264", "-crf", "14", "-preset", "ultrafast", "-pix_fmt", "yuv420p", noise.path)
        val scene = scene(noise, 5999)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val lines = ArrayList<String>()
        val off = ArrayList<String>()
        suspend fun check(name: String, s: ExportSettings, software: Boolean = false) {
            val out = dir.resolve("$name.${s.container.ext}")
            ExportDebug.refuseHardware = software
            try {
                RandomAccessFile(out, "rw").use { exportVideo(engine, scene, scene.toXml(), s, it.fd, null) {} }
            } finally {
                ExportDebug.refuseHardware = false
            }
            val (w, h) = exportSize(scene, s.shortSide, even = true)
            val chosen = videoBitrate(w, h, s)
            val real = videoBitsPerSecond(out)
            val ratio = real / chosen
            lines += "BITRATE $name: chosen ${chosen / 1000} kbps real ${(real / 1000).toInt()} kbps ratio ${"%.3f".format(ratio)}"
            if (ratio !in 0.6..1.15) off += "$name ${"%.3f".format(ratio)}"
            out.delete()
        }
        try {
            for (rate in listOf(1_000_000, 2_000_000, 4_000_000)) check("H264-exact-${rate / 1000}k", ExportSettings(720, 30, Codec.H264, bitrate = rate))
            for (quality in listOf(0f, 0.5f, 0.8f)) check("H264-quality-$quality", ExportSettings(720, 30, Codec.H264, quality = quality))
            check("Hevc-exact-2000k", ExportSettings(720, 30, Codec.Hevc, bitrate = 2_000_000))
            check("Hevc-quality-0.5", ExportSettings(720, 30, Codec.Hevc, quality = 0.5f))
            check("H264-software-exact-2000k", ExportSettings(720, 30, Codec.H264, bitrate = 2_000_000), software = true)
            check("H264-software-quality-0.5", ExportSettings(720, 30, Codec.H264, quality = 0.5f), software = true)
            check("Vp9-quality-0.5", ExportSettings(720, 30, Codec.Vp9, quality = 0.5f, container = Container.WebM))
        } finally {
            lines.forEach(::println)
            File("build/bitrate-ratios.txt").writeText(lines.joinToString("\n"))
            dir.deleteRecursively()
            engine.close()
        }
        assertTrue("real bitrate more than 15% over or 40% under the chosen one: $off", off.isEmpty())
    }

    @Test fun theVideoSettingsOfferSameAsSourceForOneVideo() {
        System.setProperty("am.data", File("build/bitrate-ui-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val scene = scene(File("../testdata/media/test-video.mp4").canonicalFile, 4999)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val shots = File("build/ui-shots/export").also { it.mkdirs() }
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { app.absentmotion.ui.export.ExportScreen(scene, 0, engine, app.absentmotion.desk.DeskAppState().settings, false) {} }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Same as source").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("2.6 Mbps").assertExists()
                Thread.sleep(1500)
                waitForIdle()
                onNodeWithText("Same as source").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("0.2 Mbps").fetchSemanticsNodes().isNotEmpty() }
                ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("same-as-source.png"))
                onNodeWithText("Custom").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("2.6 Mbps").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            engine.close()
        }
    }
}
