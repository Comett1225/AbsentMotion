package app.absentmotion.pc

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.audio.DeskOutput
import app.absentmotion.audio.gain
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.LocalEditorScope
import app.absentmotion.ui.editor.LocalTablet
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.WideEditorBody
import app.absentmotion.ui.editor.rememberAddLibrary
import app.absentmotion.ui.home.DeskModals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class MixerShotsTest {
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
<scene title="Mixer $count" width="1920" height="1080" totalTime="4999" fps="30" bgcolor="#ff14161f">
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

    @Test fun inspectorMixerAtEverySize() {
        System.setProperty("am.data", File("build/mixer-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/mixer").also { it.deleteRecursively(); it.mkdirs() }
        DeskOutput.capture = { _, _ -> }
        try {
            for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                for (count in listOf(0, 1, 6)) {
                    open(app, count)
                    if (count == 0) onNodeWithText("No sound in this project").assertExists()
                    else onAllNodesWithText("Master").assertCountEquals(1)
                    shot(shots, "${w}x$h-$count")
                    if (count > 0) {
                        play(app)
                        shot(shots, "${w}x$h-$count-playing")
                        runOnIdle { app.editor!!.playing = false }
                        mainClock.autoAdvance = true
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

    @Test fun tabletShowsTheMixerOnlyWhereTheCanvasKeepsItsSize() {
        System.setProperty("am.data", File("build/mixer-tablet-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/mixer").also { it.mkdirs() }
        for ((label, size, portraitProject) in listOf(
            Triple("landscape-16x9", 1280 to 800, false), Triple("portrait-16x9", 800 to 1280, false), Triple("portrait-9x16", 800 to 1280, true),
        )) {
            val app = DeskAppState()
            val xml = scene(6).let { if (portraitProject) it.replace("width=\"1920\" height=\"1080\" totalTime", "width=\"1080\" height=\"1920\" totalTime") else it }
            val state = runBlocking { app.openNow(app.store.create(Scene.parse(xml)))!! }
            runSkikoComposeUiTest(size = Size(size.first.toFloat(), size.second.toFloat())) {
                setContent {
                    CompositionLocalProvider(LocalWide provides true, LocalTablet provides true, LocalEditorScope provides androidx.compose.runtime.rememberCoroutineScope()) {
                        WideEditorBody(state, {}, rememberAddLibrary(false))
                    }
                }
                mainClock.advanceTimeBy(1500)
                shot(shots, "tablet-$label")
                val shown = onAllNodesWithText("Master").fetchSemanticsNodes().isNotEmpty()
                assertEquals(label, label != "portrait-16x9", shown)
                runOnIdle { state.selectedId = 101 }
                mainClock.advanceTimeBy(800)
                onAllNodesWithText("Master").assertCountEquals(0)
                shot(shots, "tablet-$label-selected")
            }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun faderMuteSoloAndGearWork() {
        System.setProperty("am.data", File("build/mixer-use-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/mixer").also { it.mkdirs() }
        DeskOutput.capture = { _, _ -> }
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                open(app, 6)
                val editor = app.editor!!
                fun gainOf(name: String) = editor.scene.layers.first { it.label == name }.gain

                val fader = onNodeWithContentDescription("Volume, Drums")
                val box = fader.getUnclippedBoundsInRoot()
                val travel = (box.bottom - box.top).value - 12f
                fader.performMouseInput {
                    moveTo(Offset(width / 2f, height / 2f))
                    press()
                    moveTo(Offset(width / 2f, height / 2f + travel / 6f), 120)
                    moveTo(Offset(width / 2f, height / 2f + travel / 3f), 120)
                    release()
                }
                mainClock.advanceTimeBy(300)
                assertEquals("dragging down a third of the travel takes 50 points", 0.5f, gainOf("Drums"), 0.03f)
                shot(shots, "use-1-fader-dragged")
                runOnIdle { editor.undo() }
                mainClock.advanceTimeBy(300)
                assertEquals("one drag is one undo step", 1f, gainOf("Drums"), 0f)

                runOnIdle { editor.redo() }
                fader.performMouseInput { doubleClick(Offset(width / 2f, height / 2f)) }
                mainClock.advanceTimeBy(500)
                assertEquals("double-click resets to 100 %", 1f, gainOf("Drums"), 0f)

                onAllNodesWithContentDescription("Mute").onFirst().performClick()
                mainClock.advanceTimeBy(300)
                assertEquals(0f, gainOf("Drums"), 0f)
                shot(shots, "use-2-muted")
                onAllNodesWithContentDescription("Mute").onFirst().performClick()
                mainClock.advanceTimeBy(300)
                assertEquals("unmute brings the old volume back", 1f, gainOf("Drums"), 0f)

                onAllNodesWithContentDescription("Solo")[1].performClick()
                mainClock.advanceTimeBy(300)
                assertArrayEquals(longArrayOf(105), app.engine.audio!!.mix.solo)
                shot(shots, "use-3-solo")
                runOnIdle { editor.selectedId = 105 }
                mainClock.advanceTimeBy(300)
                assertEquals("solo ends with the mixer", 0, app.engine.audio!!.mix.solo.size)
                runOnIdle { editor.selectedId = null }
                mainClock.advanceTimeBy(500)

                val strips = onNodeWithContentDescription("Volume, Drums").getUnclippedBoundsInRoot()
                onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset((strips.left.value + 30f), strips.top.value + 10f)); scroll(4f) }
                mainClock.advanceTimeBy(300)
                val moved = onNodeWithContentDescription("Volume, Drums").getUnclippedBoundsInRoot()
                assertTrue("the wheel scrolls the strips sideways", strips.left.value - moved.left.value > 50f)
                shot(shots, "use-4-wheel")

                onAllNodesWithContentDescription("Project settings").onLast().performClick()
                mainClock.advanceTimeBy(500)
                assertTrue(DeskModals.open > 0)
                onAllNodesWithText("Name").onFirst().assertExists()
                onNodeWithText("Earlier versions").assertExists()
                shot(shots, "use-5-settings")
                DeskBack.back()
                mainClock.advanceTimeBy(300)
                runBlocking { app.closeEditor() }
            }
        } finally {
            DeskOutput.capture = null
        }
    }
}
