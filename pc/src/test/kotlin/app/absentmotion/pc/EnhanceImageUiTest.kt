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
import app.absentmotion.ui.editor.effects.CompareImages
import app.absentmotion.ui.editor.effects.EnhanceCompare
import app.absentmotion.ui.home.EnhanceImageScreen
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class EnhanceImageUiTest {
    private val shots = File(System.getenv("ENH_SHOTS") ?: "build/ui-shots/enhance").also { it.mkdirs() }

    @Test fun compareAndToolRender() {
        val input = File.createTempFile("enhance-ui-", ".png")
        val previousData = System.getProperty("am.data")
        val data = File("build/enhance-ui-data").absoluteFile.also { it.mkdirs() }
        System.setProperty("am.data", data.path)
        try {
            val image = BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, (x * 255 / image.width shl 16) or (y * 255 / image.height shl 8) or 80)
            ImageIO.write(image, "png", input)
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { Box(Modifier.fillMaxSize().background(Color.DarkGray).padding(32.dp), Alignment.Center) {
                    val uri = android.net.Uri.fromFile(input).toString()
                    EnhanceCompare(CompareImages(uri, uri))
                } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Before").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithText("Before").assertExists()
                onNodeWithText("After").assertExists()
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("compare.png"))
            }
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { EnhanceImageScreen {} }
                waitForIdle()
                onNodeWithText("Enhance Image").assertExists()
                ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("enhance-image.png"))
            }
        } finally {
            input.delete()
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
            data.deleteRecursively()
        }
    }
}
