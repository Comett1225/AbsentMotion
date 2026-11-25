package app.absentmotion.pc

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.ui.home.DefaultHiddenTools
import app.absentmotion.ui.home.ToolsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ToolsAddMoreTest {
    @Test fun aNewToolsPageShowsTheMainToolsAndAddsTheRestFromAddMoreTools() {
        System.setProperty("am.data", File("build/tools-add-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        val settings = app.settings
        assertEquals(DefaultHiddenTools, settings.toolsHidden)
        assertTrue(settings.projectToolsHidden.isEmpty())
        val shots = File("build/tools-add").also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(420f, 1400f)) {
            setContent { ToolsTab(Modifier, app.store, settings) {} }
            waitForIdle()
            for (shown in listOf(R.string.tools_compress, R.string.tools_trim, R.string.tools_downloader)) onNodeWithText(Strings.get(shown)).assertExists()
            onNodeWithText(Strings.get(R.string.tools_gif)).assertDoesNotExist()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("page.png"))
            onNodeWithText(Strings.get(R.string.tools_add_more)).performClick()
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("sheet.png"))
            onAllNodesWithText(Strings.get(R.string.community_add)).onFirst().performClick()
            waitForIdle()
        }
        assertFalse("gif" in settings.toolsHidden)
        assertEquals(DefaultHiddenTools - "gif", settings.toolsHidden)
    }
}
