package app.absentmotion.pc

import android.content.Context
import android.content.Desk
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.audio.TextBlips
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.ui.editor.pickBlipSound
import kotlinx.coroutines.runBlocking
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class DeskTextBlipsTest {
    init { DesktopNative.hashCode() }

    private val dir = File("build/text-blips-test").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }

    private fun layer(voice: Int, src: File?) = Layer(XmlNode.parse(
        """<text id="5" startTime="0" endTime="3000"><content>Hello there</content><effect id="absent.textblips" hidden="false"${src?.let { " src=\"${it.toURI()}\"" } ?: ""}>""" +
            """<property name="voice" type="float" value="$voice.000000"/><property name="variation" type="float" value="0.000000"/></effect></text>""",
    ))

    private fun samples(uri: String): FloatArray {
        val bytes = File(URI(uri)).readBytes()
        val shorts = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32767f }
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf(app.absentmotion.Judge.ffmpeg, "-nostdin", "-y", "-v", "error") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { out }
    }

    @Test fun fullWidthStopsPauseWithoutASpace() {
        val times = NativeEngine.blipTimes("こんにちは。元気ですか！？終", 10f, 1f, 1)
        assertEquals(11, times.size)
        assertEquals(1.2f, times[5] - times[4], 1e-3f)
        assertEquals(1.3f, times[10] - times[9], 1e-3f)
        assertEquals(0.1f, times[1] - times[0], 1e-3f)
    }

    @Test fun aPickedSoundBecomesTheBlipVoice() {
        val before = TextBlips.dir to TextBlips.context
        TextBlips.dir = File(dir, "cache")
        TextBlips.context = Context()
        try {
            val mine = File(dir, "mine.wav").apply { writeBytes(TextBlips.wav(listOf(0f), 7, 1f, 0f, 0f, 1)) }
            val times = NativeEngine.blipTimes("Hello there", 30f, 0.25f, 1).toList()
            val custom = samples(TextBlips.clip(layer(TextBlips.CUSTOM, mine), null)!!.uri)
            val expected = TextBlips.render(times, 7, 0.6f, 0f, 0f, 1)
            assertEquals(expected.size, custom.size)
            assertTrue(expected.indices.all { abs(expected[it] - custom[it]) < 2e-3f })
            val classic = samples(TextBlips.clip(layer(0, mine), null)!!.uri)
            assertTrue(classic.indices.any { abs(classic[it] - custom[it]) > 0.05f })
            assertNull(TextBlips.clip(layer(TextBlips.CUSTOM, null), null))
            assertNull(TextBlips.clip(layer(TextBlips.CUSTOM, File(dir, "gone.wav")), null))
            for ((format, codec) in listOf("flac" to listOf("-c:a", "flac"), "ogg" to listOf("-c:a", "vorbis", "-strict", "-2", "-ac", "2"), "m4a" to listOf("-c:a", "aac"))) {
                val other = File(dir, "mine.$format")
                ffmpeg("-i", mine.path, "-ar", "48000", *codec.toTypedArray(), other.path)
                val heard = samples(TextBlips.clip(layer(TextBlips.CUSTOM, other), null)!!.uri)
                val at = (times[1] * TextBlips.RATE).toInt()
                assertTrue(format, (at until at + TextBlips.RATE / 40).maxOf { abs(heard[it]) } > 0.1f)
            }
        } finally {
            TextBlips.dir = before.first
            TextBlips.context = before.second
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun choosingCustomSoundPicksAFileAndKeepsItInTheProject() {
        val saved = listOf("am.data", "am.pick").associateWith { System.getProperty(it) }
        System.setProperty("am.data", File(dir, "data").apply { mkdirs() }.path)
        val first = File(dir, "first.wav").apply { writeBytes(TextBlips.wav(listOf(0f), 7, 1f, 0f, 0f, 1)) }
        val second = File(dir, "second.wav").apply { writeBytes(TextBlips.wav(listOf(0f), 3, 1f, 0f, 0f, 1)) }
        System.setProperty("am.pick", first.path)
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                val id = app.store.create(Scene.parse(
                    """<scene width="400" height="400" fps="30" totalTime="3000"><text id="5" startTime="0" endTime="3000"><content>Hello</content>""" +
                        """<effect id="absent.textblips" hidden="false"/><effect id="absent.blur" hidden="false"/></text></scene>""",
                ))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                fun settle() { mainClock.advanceTimeBy(500); waitForIdle() }
                fun blips() = editor.scene.layer(5)!!.effects[0]
                fun voice() = blips().children.single { it.name == "property" && it["name"] == "voice" }["value"]!!.toFloat().toInt()
                fun picked() = blips()["src"]?.let { editor.scene.media(it)?.filename }
                fun choose(from: String, to: String) {
                    onAllNodesWithText(from).filter(hasClickAction()).onFirst().performClick()
                    settle()
                    onAllNodesWithText(to).filter(hasClickAction()).onLast().performClick()
                    settle()
                }
                runOnIdle { editor.selectedId = 5; editor.panel = EditorPanel.Effects }
                settle()
                runOnIdle { editor.effectIndex = 0 }
                settle()
                choose("Classic", "Custom Sound")
                waitUntil(timeoutMillis = 30_000) { picked() != null }
                assertEquals("first.wav", picked())
                assertEquals(TextBlips.CUSTOM, voice())
                assertNotNull(TextBlips.clip(editor.scene.layer(5)!!, null))
                choose("Custom Sound", "Skeleton")
                assertEquals(TextBlips.SKELETON, voice())
                assertEquals("first.wav", picked())
                System.setProperty("am.pick", second.path)
                choose("Skeleton", "Custom Sound")
                assertEquals(TextBlips.CUSTOM, voice())
                assertEquals("first.wav", picked())
                choose("Custom Sound", "Custom Sound")
                waitUntil(timeoutMillis = 30_000) { picked() == "second.wav" }
                assertEquals(TextBlips.CUSTOM, voice())
                val before = editor.scene.node
                runOnIdle { runBlocking { editor.pickBlipSound(Desk.context, Uri.fromFile(first), 5, 1) } }
                assertEquals(before, editor.scene.node)
                runBlocking { app.closeEditor() }
            }
        } finally {
            saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }
}
