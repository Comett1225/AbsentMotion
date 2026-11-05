package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.library.SavedSizes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class SavedSizesShotsTest {
    @Test fun newProjectStrip() {
        val data = File("build/sizes-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val shots = File("build/ui-shots/sizes").also { it.mkdirs() }
        for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                app.settings.savedSizes = SavedSizes.with(SavedSizes.with(emptyList(), "Wide ad", 1920, 300), "Banner", 1080, 550)
                fun shot(name: String) { waitForIdle(); ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name-$w.png")) }
                setContent { DeskApp(app) }
                onAllNodesWithText("New project", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(1500)
                shot("a-before")
                onAllNodesWithText("Custom", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(1500)
                shot("b-custom")
                onAllNodesWithText("Banner", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(500)
                shot("c-picked")
                onAllNodesWithText("Banner", substring = false).onFirst().performMouseInput { rightClick() }
                mainClock.advanceTimeBy(800)
                shot("d-menu")
                onAllNodesWithText("Rename", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(800)
                shot("e-rename")
                onAllNodesWithText("Done", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(500)
                onAllNodesWithText("+ Save size", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(800)
                shot("f-save")
                onAllNodesWithText("Done", substring = false).onFirst().performClick()
                mainClock.advanceTimeBy(500)
                assertEquals(2, app.settings.savedSizes.size)
                assertEquals("1080 × 550", app.settings.savedSizes.first().name)
            }
        }
    }
}
