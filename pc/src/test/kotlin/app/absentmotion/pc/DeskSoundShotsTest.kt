package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.audio.DeskOutput
import app.absentmotion.audio.Waveforms
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.Strings
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskSoundShotsTest {
    @Test fun soundsInThePhoneUi() {
        val data = File("build/desk-sound-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val home = File("build/desk-sound-home").absoluteFile.also { it.deleteRecursively() }
        val music = File(home, "Music/Tones").also { it.mkdirs() }
        fun make(name: String, expr: String, vararg tags: String) {
            val p = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=$expr:s=44100:d=4") + tags + File(music, name).path).start()
            check(p.waitFor() == 0)
        }
        make("tone-b.m4a", "0.3*sin(2*PI*330*t)*sin(PI*t)", "-metadata", "title=Tone B", "-metadata", "artist=Tester")
        make("sweep.flac", "0.2*sin(2*PI*(200+300*t)*t)")
        val repo = File("..").canonicalFile
        val oldHome = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
        app.absentmotion.desk.DeskLibrary.link(File(home, "Music"))
        DeskOutput.capture = { _, _ -> }
        try {
            runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
                val app = DeskAppState()
                val shots = File("build/desk-shots").also { it.mkdirs() }
                fun shot(name: String) { waitForIdle(); ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve(name)) }
                setContent { DeskApp(app) }
                val xml = File(repo, "testdata/scenes/media.xml").readText().replace("\"testdata/media/test-audio.m4a\"", "\"" + File(music, "tone-b.m4a").toURI().toString().replace("file:/", "file:///") + "\"")
                    .replace("\"testdata/media/", "\"" + File(repo, "testdata/media").toURI().toString().replace("file:/", "file:///") + "/")
                val id = app.store.create(Scene.parse(xml))
                runOnIdle { app.projectsChanged++ }
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                val sound = editor.scene.layers.first { it.kind == "audio" }
                runOnIdle { editor.selectedId = sound.id }
                waitUntil(timeoutMillis = 20_000) { Waveforms.peaks(sound.node["src"]!!) != null }
                mainClock.advanceTimeBy(1000)
                shot("audio-01-waveform.png")
                assertTrue(Waveforms.peaks(sound.node["src"]!!)!!.data.size >= 395)
                runOnIdle { editor.selectedId = null; editor.addOpen = true }
                mainClock.advanceTimeBy(1000)
                onNodeWithText(Strings.get(R.string.add_tab_audio)).performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Tone B").fetchSemanticsNodes().isNotEmpty() }
                mainClock.advanceTimeBy(1000)
                shot("audio-02-device-sounds.png")
                onAllNodesWithText("Tone B").onFirst().performClick()
                mainClock.advanceTimeBy(1500)
                shot("audio-03-preview-playing.png")
                onAllNodesWithText("Tone B").onFirst().performClick()
                mainClock.advanceTimeBy(1000)
                shot("audio-04-preview-stopped.png")
            }
        } finally {
            DeskOutput.capture = null
            System.setProperty("user.home", oldHome)
        }
    }
}
