package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.home.FfampOfferCard
import app.absentmotion.ui.home.ffampSpeeds
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class FfampOfferTest {
    @Test fun theOfferShowsThisDevicesSpeedsAndEachButtonAnswers() {
        val shots = File("build/ui-shots/ffamp").also { it.mkdirs() }
        for ((name, desktop) in listOf("mac" to true, "android" to false)) {
            runSkikoComposeUiTest(size = if (desktop) Size(1280f, 800f) else Size(412f, 915f)) {
                var answer = ""
                setContent {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2B2B2B)).padding(16.dp), Alignment.Center) {
                        FfampOfferCard(ffampSpeeds(desktop, "mac os x"), !desktop, { answer = "use" }, { answer = "keep" })
                    }
                }
                waitForIdle()
                onNodeWithText("Try FFamp?").assertExists()
                onNodeWithText(if (desktop) "Exporting MP4" else "Timeline thumbnails").assertExists()
                onNodeWithText(if (desktop) "up to 7× faster" else "up to 25× faster").assertExists()
                assertEquals(!desktop, onAllNodesWithText("It also opens AVI, WMV and WMA files.").fetchSemanticsNodes().isNotEmpty())
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("offer-$name.png"))
                onNodeWithText("Use FFamp").performClick()
                assertEquals("use", answer)
                onNodeWithText("Keep the standard engine").performClick()
                assertEquals("keep", answer)
            }
        }
        assertEquals(listOf(5.0, 2.5, 1.3, 3.8), ffampSpeeds(true, "windows 11").map { it.times })
        assertEquals(listOf(29.0, 13.0), ffampSpeeds(true, "linux").map { it.times })
        assertEquals(emptyList<Any>(), ffampSpeeds(true, "freebsd"))
    }
}
