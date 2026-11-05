package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.ui.home.DeskHelpReader
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskHelpShotsTest {
    private fun shot(page: String, w: Float, h: Float, name: String) {
        runSkikoComposeUiTest(size = Size(w, h)) {
            setContent { DeskHelpReader(page, {}, {}) }
            Thread.sleep(1500)
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val out = File("build/ui-shots/help").also { it.mkdirs() }
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out.resolve(name))
        }
    }

    @org.junit.Test fun helpWindowContent() {
        shot("effects/blur.html", 720f, 820f, "blur-720.png")
        shot("audio.html", 720f, 820f, "audio-720.png")
        shot("effects/blur.html", 1100f, 900f, "blur-1100.png")
        shot("audio.html", 1100f, 900f, "audio-1100.png")
    }
}
