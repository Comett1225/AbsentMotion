package app.absentmotion.pc

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
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopNative
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DeskMissingMediaTest {
    init { DesktopNative.hashCode() }

    @OptIn(ExperimentalTestApi::class)
    @Test fun openingAProjectWarnsAboutDeletedClipsAndMarksThem() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val dir = File("build/missing-media").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }
        val kept = dir.resolve("kept.png").also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it) }.toURI().toString()
        val gone = dir.resolve("holiday.mp4").toURI().toString()
        val app = DeskAppState()
        setContent { DeskApp(app) }
        val id = app.store.create(Scene.parse(
            """<scene width="400" height="400" fps="30" totalTime="3000">""" +
                """<media uri="$gone" filename="holiday.mp4" type="video/mp4"/><media uri="$kept" filename="kept.png" type="image/png"/>""" +
                """<shape id="1" label="Holiday" s=".rect" fillType="media" fillVideo="$gone" startTime="0" endTime="3000"/>""" +
                """<shape id="2" label="Kept" s=".rect" fillType="media" fillImage="$kept" startTime="0" endTime="3000"/></scene>""",
        ))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        val editor = app.editor!!
        waitUntil(timeoutMillis = 10_000) { editor.missingMedia.isNotEmpty() }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(setOf(gone), editor.missingMedia)
        assertTrue(onAllNodesWithText("These files were moved or deleted:\nholiday.mp4").fetchSemanticsNodes().isNotEmpty())
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("warning.png"))
        onAllNodesWithText("OK").filter(hasClickAction()).onFirst().performClick()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertFalse(onAllNodesWithText("holiday.mp4", substring = true).fetchSemanticsNodes().isNotEmpty())
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("timeline.png"))
    }
}
