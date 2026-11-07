package app.absentmotion.pc

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.audio.DeskOutput
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskMedia
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.LocalEditorScope
import app.absentmotion.ui.editor.LocalTablet
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.LevelMeter
import app.absentmotion.ui.editor.WideEditorBody
import app.absentmotion.ui.editor.rememberAddLibrary
import app.absentmotion.ui.home.DeskModals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class MeterShotsTest {
    private val audio = Uri.fromFile(File("../testdata/media/test-audio.m4a").canonicalFile).toString()
    private val video = Uri.fromFile(File("../testdata/media/test-video.mp4").canonicalFile).toString()
    private val names = listOf("Music", "Interview clip", "Voice over", "Crowd", "Whoosh", "Drums")

    private fun scene(count: Int): String {
        val layers = (0 until count).joinToString("\n") { i ->
            val gain = listOf("1.000000", "0.800000", "1.200000", "0.500000", "0.000000", "1.000000")[i]
            if (i == 1) """<shape id="${101 + i}" label="${names[i]}" startTime="0" endTime="4999" fillType="media" mediaFillMode="fill" s=".rect" fillVideo="$video" inTime="0" outTime="5000" speed="1.000000"><property name="size" type="vec2" value="960.000000,540.000000" /><gain value="$gain" /></shape>"""
            else """<audio id="${101 + i}" label="${names[i]}" startTime="0" endTime="4999" src="$audio" outTime="5000" mediaFillMode="fill"><gain value="$gain" /></audio>"""
        }
        return """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Meter $count" width="1920" height="1080" totalTime="4999" fps="30" bgcolor="#ff14161f">
  <media uri="$video" filename="test-video.mp4" title="test-video.mp4" type="video/mp4" duration="5000000" width="1280" height="720" />
  <media uri="$audio" filename="test-audio.m4a" title="test-audio.m4a" type="audio/mp4" duration="10000000" />
  <shape id="1" label="Backdrop" startTime="0" endTime="4999" s=".rect"><property name="size" type="vec2" value="1920.000000,1080.000000" /></shape>
$layers
</scene>"""
    }

    private fun ComposeUiTest.shot(dir: File, name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name.png"))
    }

    private fun ComposeUiTest.open(app: DeskAppState, count: Int) {
        val id = app.store.create(Scene.parse(scene(count)))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        runOnIdle { app.editor!!.selectedId = null }
        mainClock.advanceTimeBy(1000)
    }

    private fun ComposeUiTest.play(app: DeskAppState) {
        mainClock.autoAdvance = false
        runOnIdle { app.editor!!.frame = 15; app.editor!!.playing = true }
        repeat(40) { mainClock.advanceTimeByFrame(); Thread.sleep(25) }
        runOnIdle { println("playing ${app.editor!!.playing} frame ${app.editor!!.frame} master ${app.engine.audio?.level}") }
    }

    @Test fun deskShowsTheMeterRightOfTheRailAndTheProjectInTheInspector() {
        System.setProperty("am.data", File("build/meter-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/meter").also { it.deleteRecursively(); it.mkdirs() }
        DeskOutput.capture = { _, _ -> }
        try {
            for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                for (count in listOf(0, 6)) {
                    open(app, count)
                    val meter = onNodeWithContentDescription("Master level").getUnclippedBoundsInRoot()
                    println("${w}x$h $count meter ${meter.left}..${meter.right} x ${meter.top}..${meter.bottom}")
                    assertTrue("${w}x$h: right of the rail", meter.left.value >= 56f && meter.right.value <= 84f)
                    assertTrue("${w}x$h: starts at the top", meter.top.value < 90f)
                    assertTrue("${w}x$h: no taller than the rail buttons", (meter.bottom - meter.top).value <= 368.5f)
                    onAllNodesWithText("−∞").assertCountEquals(1)
                    if (w >= 1280) {
                        onAllNodesWithText("Layers").onFirst().assertExists()
                        onAllNodesWithText("Name").onFirst().assertExists()
                    }
                    onAllNodesWithText("Mute").assertCountEquals(0)
                    shot(shots, "${w}x$h-$count")
                    if (count > 0) {
                        play(app)
                        assertTrue("the master moves while playing", app.engine.audio!!.mix.master > 0f)
                        shot(shots, "${w}x$h-$count-playing")
                        runOnIdle { app.editor!!.playing = false }
                        mainClock.autoAdvance = true
                        mainClock.advanceTimeBy(300)
                    }
                    if (w == 1280 && count > 0) {
                        onAllNodesWithContentDescription("Project settings").onLast().performClick()
                        mainClock.advanceTimeBy(500)
                        assertTrue(DeskModals.open > 0)
                        onAllNodesWithText("Earlier versions").assertCountEquals(2)
                        shot(shots, "${w}x$h-settings")
                        DeskBack.back()
                        mainClock.advanceTimeBy(300)
                    }
                    runBlocking { app.closeEditor() }
                    mainClock.advanceTimeBy(300)
                }
            }
        } finally {
            DeskOutput.capture = null
        }
    }

    private fun stereo(): String {
        val file = File("build/meter-media/stereo-l-loud.m4a").absoluteFile
        if (!file.exists()) {
            file.parentFile.mkdirs()
            val p = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*440*t)|0.12*sin(2*PI*660*t):s=48000:d=8", file.path).start()
            check(p.waitFor() == 0)
        }
        return Uri.fromFile(file).toString()
    }

    private fun pair(stereo: String, portrait: Boolean = false) = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Stereo and mono" width="${if (portrait) 1080 else 1920}" height="${if (portrait) 1920 else 1080}" totalTime="4999" fps="30" bgcolor="#ff14161f">
  <media uri="$stereo" filename="stereo-l-loud.m4a" title="stereo-l-loud.m4a" type="audio/mp4" duration="8000000" />
  <media uri="$audio" filename="test-audio.m4a" title="test-audio.m4a" type="audio/mp4" duration="10000000" />
  <shape id="1" label="Backdrop" startTime="0" endTime="4999" s=".rect"><property name="size" type="vec2" value="1920.000000,1080.000000" /></shape>
  <audio id="101" label="Stereo music" startTime="0" endTime="4999" src="$stereo" outTime="5000" mediaFillMode="fill"><gain value="1.000000" /></audio>
  <audio id="102" label="Mono voice" startTime="0" endTime="4999" src="$audio" outTime="5000" mediaFillMode="fill"><gain value="0.600000" /></audio>
</scene>"""

    @Test fun tabletShowsTheMeterTopLeftInLandscapeOnly() {
        System.setProperty("am.data", File("build/meter-tablet-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/meters").also { it.deleteRecursively(); it.mkdirs() }
        val sound = stereo()
        DeskOutput.capture = { _, _ -> }
        try {
            for ((label, size, portraitProject) in listOf(
                Triple("landscape-16x9", 1280 to 800, false), Triple("landscape-9x16", 1280 to 800, true), Triple("portrait-16x9", 800 to 1280, false),
                Triple("small-landscape-16x9", 960 to 600, false),
            )) {
                val landscape = size.first > size.second
                val app = DeskAppState()
                val state = runBlocking { app.openNow(app.store.create(Scene.parse(pair(sound, portraitProject))))!! }
                runSkikoComposeUiTest(size = Size(size.first.toFloat(), size.second.toFloat())) {
                    setContent {
                        CompositionLocalProvider(LocalWide provides true, LocalTablet provides true, LocalEditorScope provides androidx.compose.runtime.rememberCoroutineScope()) {
                            WideEditorBody(state, {}, rememberAddLibrary(false))
                        }
                    }
                    mainClock.advanceTimeBy(1500)
                    fun check(stage: String) {
                        if (!landscape) { onAllNodesWithContentDescription("Master level").assertCountEquals(0); return }
                        val meter = onNodeWithContentDescription("Master level").getUnclippedBoundsInRoot()
                        println("$label $stage meter ${meter.left}..${meter.right} x ${meter.top}..${meter.bottom}")
                        assertTrue("$label $stage: on the left edge", meter.left.value <= 12.5f)
                        assertEquals("$label $stage: slim", 18f, (meter.right - meter.left).value, 0.5f)
                        assertEquals("$label $stage: under the canvas zoom button", 120f, meter.top.value, 1f)
                    }
                    check("stopped")
                    shot(shots, "tablet-$label-stopped")
                    mainClock.autoAdvance = false
                    runOnIdle { state.frame = 15; state.playing = true; state.engine.audio!!.play(state.scene.frameStart(15).toDouble()) }
                    repeat(40) { mainClock.advanceTimeByFrame(); Thread.sleep(25) }
                    check("playing")
                    shot(shots, "tablet-$label-playing")
                    runOnIdle { state.playing = false; state.engine.audio!!.stop() }
                    mainClock.autoAdvance = true
                    mainClock.advanceTimeBy(500)
                    runOnIdle { state.selectedId = 101 }
                    mainClock.advanceTimeBy(800)
                    check("selected")
                    shot(shots, "tablet-$label-selected")
                }
                runBlocking { app.closeEditor() }
            }
        } finally {
            DeskOutput.capture = null
        }
    }

    @Test fun theBottomLineShowsLeftAndRightApart() {
        System.setProperty("am.data", File("build/meter-line-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/meters").also { it.mkdirs() }
        val app = DeskAppState()
        val state = runBlocking { app.openNow(app.store.create(Scene.parse(pair(stereo()))))!! }
        val mix = state.engine.audio!!.mix
        runSkikoComposeUiTest(size = Size(600f, 40f)) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) { LevelMeter(state, Modifier.align(Alignment.Center).fillMaxWidth().height(12.dp)) }
            }
            mainClock.autoAdvance = false
            fun line(name: String, left: Float, right: Float): IntArray {
                state.playing = false
                mainClock.advanceTimeBy(200)
                mix.master(left, right)
                state.playing = true
                mainClock.advanceTimeBy(200)
                val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(image, "png", shots.resolve("line-$name.png"))
                return IntArray(image.width) { image.getRGB(it, 20) and 0xFFFFFF }
            }
            fun near(a: Int, b: Int) = (0..16 step 8).all { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 2 }
            val same = line("same", 0.5f, 0.5f)
            for (x in 0 until 300) assertTrue("mono-identical sound is symmetric at $x", near(same[299 - x], same[300 + x]))
            assertTrue(same[300 - 250] != 0 && same[300 + 250] != 0)
            assertEquals(0, same[300 - 280])
            val apart = line("left-loud", 0.9f, 0.25f)
            assertTrue("the loud left side reaches far out", apart[300 - 285] != 0)
            assertEquals("the quiet right side stops short", 0, apart[300 + 260])
            assertTrue(apart[300 + 200] != 0)
            val clip = line("right-clips", 0.3f, 1.2f)
            assertEquals("a clip lights only its own half red", 0xFF3B30, clip[590])
            assertEquals(0xFF3B30, clip[310])
            assertTrue(clip[290] != 0xFF3B30)
            state.playing = false
            mainClock.advanceTimeBy(200)
        }
        runBlocking { app.closeEditor() }
    }
}
