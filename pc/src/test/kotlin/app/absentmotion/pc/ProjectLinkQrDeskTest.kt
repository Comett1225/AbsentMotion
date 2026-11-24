package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.ui.home.ProjectLinkDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ProjectLinkQrDeskTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun scannedLinksStartTickedAndChangeTheImportCount() {
        val files = listOf("one.absproj", "two.xml", "three.zip")
        val size = 180
        val picture = BufferedImage(size * files.size, size, BufferedImage.TYPE_INT_RGB)
        for ((index, file) in files.withIndex()) {
            val code = QRCodeWriter().encode("https://example.org/$file", BarcodeFormat.QR_CODE, size, size)
            for (y in 0 until size) for (x in 0 until size) {
                picture.setRGB(index * size + x, y, if (code[x, y]) 0 else 0xffffff)
            }
        }
        val png = temp.newFile("qr.png")
        ImageIO.write(picture, "png", png)
        val previous = System.setProperty("am.pick", png.absolutePath)
        try {
            runSkikoComposeUiTest(size = Size(800f, 480f)) {
                setContent { ProjectLinkDialog({}) { _, _ -> } }
                onNodeWithText("QR from a picture").performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithText("Found 3 QR codes").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Import 3").assertExists()
                for ((index, file) in files.withIndex()) {
                    val row = onNodeWithText(file)
                    row.performScrollTo().assertIsOn()
                    row.performClick().assertIsOff()
                    onNodeWithText("Import ${files.lastIndex - index}").assertExists()
                }
                onNodeWithText("Import 0").assertIsNotEnabled()
            }
        } finally {
            if (previous == null) System.clearProperty("am.pick") else System.setProperty("am.pick", previous)
        }
    }
}
