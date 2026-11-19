package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.absentmotion.desk.DeskKeys
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.TimelineMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PlayheadEndTest {
    @Test fun playbackStopsWithThePlayheadOnTheClipsEnd() {
        System.setProperty("am.data", File("build/playhead-end-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse("""<scene width="640" height="360" fps="30" totalTime="999" bgcolor="#ff202020"><shape id="7" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="320,180,0"/></transform><property name="size" type="vec2" value="200,200"/><fillColor value="#ffff0000"/></shape></scene>"""))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(1000)
            val editor = app.editor!!
            val last = editor.scene.lastFrame
            val end = TimelineMath.timecode(last + 1, 30)
            fun press(key: Key) = runOnIdle { DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyDown)); DeskKeys.handle(app, KeyEvent(key, KeyEventType.KeyUp)) }
            runOnIdle { editor.playing = true }
            repeat(30) { mainClock.advanceTimeBy(100); Thread.sleep(20) }
            waitForIdle()
            runOnIdle {
                assertFalse(editor.playing)
                assertTrue(editor.atEnd)
                assertEquals(last, editor.frame)
                assertEquals(last + 1, editor.playheadFrame)
            }
            assertTrue("$end shown", onAllNodesWithText(end, substring = true).fetchSemanticsNodes().isNotEmpty())
            press(Key.DirectionLeft)
            waitForIdle()
            runOnIdle { assertFalse(editor.atEnd); assertEquals(last, editor.frame) }
            press(Key.DirectionRight)
            waitForIdle()
            runOnIdle { assertTrue(editor.atEnd); assertEquals(last, editor.frame) }
            press(Key.MoveHome)
            waitForIdle()
            runOnIdle { assertFalse(editor.atEnd); assertEquals(0, editor.frame) }
            press(Key.MoveEnd)
            waitForIdle()
            runOnIdle { assertTrue(editor.atEnd); assertEquals(last + 1, editor.playheadFrame) }
            runOnIdle { editor.edit { it.withTitle("edited") } }
            waitForIdle()
            runOnIdle { assertTrue(editor.atEnd); assertEquals(last + 1, editor.playheadFrame) }
            runBlocking { app.closeEditor() }
        }
    }
}
