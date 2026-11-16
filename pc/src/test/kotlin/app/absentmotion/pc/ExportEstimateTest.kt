package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.export.Codec
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.ExportTimes
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ExportEstimateTest {
    private val sceneXml = """<scene width="1080" height="1920" fps="30" totalTime="3000" bgcolor="#ff202020"><shape id="1" startTime="0" endTime="3000" fillType="color" s=".rect"><transform><location value="540,960,0"/></transform><property name="size" type="vec2" value="400,400"/><fillColor value="#ffff0000"/></shape></scene>"""

    @Test fun aComputerWithNoExportsYetShowsCalculatingThenATime() {
        System.setProperty("am.data", File("build/estimate-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            assertTrue(ExportTimes(app.context).history().isEmpty())
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            app.exportingFromList = Scene.parse(sceneXml)
            mainClock.advanceTimeBy(100)
            waitForIdle()
            assertEquals(0, onAllNodesWithText("Unknown", useUnmergedTree = true).fetchSemanticsNodes().size)
            if (DeskEncoders.video(Codec.H264).firstOrNull()?.let(DeskEncoders::hardware) != false)
                assertTrue(onAllNodesWithText("Calculating", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            repeat(60) { mainClock.advanceTimeBy(200); Thread.sleep(100) }
            waitForIdle()
            assertEquals(0, onAllNodesWithText("Unknown", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("Calculating", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun theRoughEstimateGrowsWithFramesAndSize() {
        System.setProperty("am.data", File("build/estimate-rough-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val times = ExportTimes(DeskAppState().context)
        val s = Scene.parse(sceneXml)
        val small = times.roughMs(ExportSettings(540, 30), s)
        val big = times.roughMs(ExportSettings(1080, 60), s)
        assertTrue("$small < $big", small in 300..big - 1)
    }
}
