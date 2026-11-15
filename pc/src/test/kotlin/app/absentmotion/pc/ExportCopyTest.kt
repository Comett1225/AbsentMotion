package app.absentmotion.pc

import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import app.absentmotion.export.ExportKind
import app.absentmotion.ui.export.DeskExportPreview
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ExportCopyTest {
    @Test fun rightClickingTheExportedFileCopiesIt() {
        val file = File("build/export-copy/frame.png").absoluteFile.also { it.parentFile.mkdirs() }
        ImageIO.write(BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB), "png", file)
        runSkikoComposeUiTest(size = Size(600f, 400f)) {
            setContent { DeskExportPreview(ExportKind.Frame, listOf(Uri.fromFile(file))) }
            waitForIdle()
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(center); rightClick() }
            waitForIdle()
            onNodeWithText("Copy").performClick()
            waitForIdle()
            onAllNodesWithText("Copy").assertCountEquals(0)
        }
        assumeFalse(GraphicsEnvironment.isHeadless())
        val copied = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.javaFileListFlavor) as List<*>
        assertEquals(listOf(file), copied)
    }
}
