package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class BundleThumbnailPickerTest {
    @Test fun aComputerChoosesTheCommunityThumbnailInTheEditorStylePicker() {
        System.setProperty("am.data", File("build/thumb-picker-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val shots = File("build/ui-shots/thumb-picker").also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            mainClock.advanceTimeBy(1000)
            app.exportingFromList = Scene.parse("""<scene width="1080" height="1920" fps="30" totalTime="2999" bgcolor="#ff202020"><shape id="1" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location><kf t="0" v="200,960,0"/><kf t="1" v="880,960,0"/></location></transform><property name="size" type="vec2" value="300,300"/><fillColor value="#ffff0000"/></shape></scene>""")
            repeat(10) { mainClock.advanceTimeBy(200); Thread.sleep(30) }
            onAllNodesWithText("Project Bundle").filter(hasClickAction()).onFirst().performClick()
            repeat(5) { mainClock.advanceTimeBy(200); Thread.sleep(30) }
            onAllNodesWithText("Community bundle").filter(hasClickAction()).onFirst().performClick()
            repeat(5) { mainClock.advanceTimeBy(200); Thread.sleep(30) }
            assertTrue(onAllNodesWithText("Choose thumbnail").fetchSemanticsNodes().isNotEmpty())
            onAllNodesWithText("Choose thumbnail").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            repeat(20) { mainClock.advanceTimeBy(200); Thread.sleep(50) }
            waitForIdle()
            onAllNodesWithText("Confirm thumbnail").onFirst().assertExists()
            onAllNodesWithText("Slide the timeline", substring = true).onFirst().assertExists()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("picker.png"))
        }
    }
}
