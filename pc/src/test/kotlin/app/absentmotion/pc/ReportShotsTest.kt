package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.ui.home.ReportDialog
import app.absentmotion.ui.home.scrubReport
import app.absentmotion.ui.theme.Am
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ReportShotsTest {
    @Test fun reportDialog() {
        val home = System.getProperty("user.home")
        val out = scrubReport("Failed at $home/Movies/clip.mp4")
        assertFalse(out.contains(home))
        assertTrue(out.contains("~/Movies"))
        val shots = File("build/ui-shots/report").also { it.mkdirs() }
        for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg))
                    ReportDialog("Export Failed\nThe encoder produced no video") {}
                }
                waitForIdle()
                mainClock.advanceTimeBy(500)
                onNodeWithText("GitHub issue").assertExists()
                onNodeWithText("Discord").assertExists()
                onNodeWithText("Bug report form").assertExists()
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("report-${w}x$h.png"))
            }
        }
    }
}
