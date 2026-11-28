package app.absentmotion.pc

import android.content.Desk
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.ai.CaptionModel
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

class DeskCaptionSourceTest {
    init { DesktopNative.hashCode() }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aSourceIsPickedInTheEditorAndOnlyClipsWithSoundCount() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val shots = File("build/caption-source").absoluteFile.also { it.mkdirs() }
        val precise = CaptionModel.file(Desk.context, true)
        val hadPrecise = precise.exists()
        if (!hadPrecise) { precise.parentFile.mkdirs(); RandomAccessFile(precise, "rw").use { it.setLength(CaptionModel.bytes(true)) } }
        try {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse(
                """<scene width="400" height="400" fps="30" totalTime="9000">""" +
                    """<audio id="1" label="Voice A" src="a.m4a" startTime="0" endTime="3000"/>""" +
                    """<audio id="2" label="Voice B" src="b.m4a" startTime="3000" endTime="6000"/>""" +
                    """<text id="3" label="Title" startTime="0" endTime="9000"><content>Hi</content></text></scene>""",
            ))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            fun settle() { mainClock.advanceTimeBy(500); waitForIdle() }
            fun click(text: String) { onAllNodesWithText(text).filter(hasClickAction()).onFirst().performClick(); settle() }
            fun shown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            waitUntil(timeoutMillis = 10_000) { editor.missingMedia.isNotEmpty() }
            click("OK")
            editor.captionsOpen = true
            settle()
            assertTrue(shown("Whole project"))
            if (!CaptionModel.installed(Desk.context, false)) {
                assertTrue(shown("Standard · 264 MB"))
                assertTrue(onAllNodesWithText("Higher precision").fetchSemanticsNodes().isNotEmpty())
                assertTrue(!shown("874 MB"))
            }
            click("Select different source")
            val pick = editor.sourcePick
            assertNotNull(pick)
            assertEquals(setOf(1L, 2L), pick!!.usable)
            assertTrue(!shown("Whole project"))
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("picking.png"))
            editor.selectedId = 3L
            settle()
            assertNull(editor.selectedId)
            assertNotNull(editor.sourcePick)
            editor.selectedId = 2L
            settle()
            assertNull(editor.sourcePick)
            assertTrue(shown("Voice B"))
            click("Select different source")
            click("Cancel")
            assertNull(editor.sourcePick)
            assertTrue(shown("Voice B"))
            click("Select different source")
            MultiSelect.ids = setOf(1L, 2L, 3L)
            MultiSelect.active = true
            settle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("picking-several.png"))
            click("Done")
            assertNull(editor.sourcePick)
            assertTrue(shown("Voice A, Voice B"))
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("sheet-after.png"))
        } finally {
            if (!hadPrecise) precise.delete()
        }
    }
}
