package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.ui.WorkspaceContent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class WorkspaceReviewTest {
    private fun scene(): Scene {
        val source = Scene.parse(File("../testdata/scenes/media.xml").readText())
        return Scene(source.node.copy(children = source.node.children.filter { it.name != "audio" && it["fillVideo"] == null && it["type"]?.startsWith("video") != true && it["type"]?.startsWith("audio") != true }))
    }

    @Test fun workspacePictures() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState()
        val shots = File("build/ui-shots").also { it.mkdirs() }
        fun shot(name: String) { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve(name)) }
        fun rendered() { waitUntil(timeoutMillis = 30000) { onAllNodesWithTag("rendered-${state.scene.hashCode()}-${state.selected}").fetchSemanticsNodes().isNotEmpty() } }
        setContent { WorkspaceContent(null, {}, null, mutableListOf(), project = state) }
        rendered()
        shot("01-empty.png")
        runOnIdle { state.open(scene(), File("../project.xml")) }
        rendered()
        shot("02-project.png")
        onNodeWithTag("header-8001").performScrollTo().performClick()
        rendered()
        val before = onNodeWithTag("viewer-image").captureToImage().toAwtImage().let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }
        onNodeWithTag("location-0").performTextClearance()
        onNodeWithTag("location-0").performTextInput("480")
        rendered()
        val after = onNodeWithTag("viewer-image").captureToImage().toAwtImage().let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }
        assertFalse(before.contentEquals(after))
        onNodeWithText("Effects", substring = false).performClick()
        onNodeWithText("Brightness & Contrast", substring = false).performClick()
        runOnIdle { assertEquals(1, state.layer!!.effects.size) }
        onNodeWithTag("effect-brightness").performScrollTo().performTextClearance()
        onNodeWithTag("effect-brightness").performTextInput("0.5")
        onNodeWithTag("key-brightness").performScrollTo().performClick()
        rendered()
        onNode(hasText("Media") and hasClickAction()).performClick()
        onNodeWithTag("add-effect").performScrollTo()
        shot("03-layer-selected.png")
        onNodeWithTag("header-8006").performScrollTo().performClick()
        rendered()
        shot("04-text-selected.png")
        onNodeWithText("Effects", substring = false).performClick()
        waitForIdle()
        shot("05-effects-library.png")
        onNodeWithText("+", substring = false).performClick()
        onNodeWithText("+", substring = false).performClick()
        shot("06-timeline-zoomed.png")
        onNodeWithText("Settings", substring = false).performClick()
        onNodeWithText("Project Settings", substring = false).assertExists()
        shot("07-settings-dialog.png")
    }

    @Test fun shortcutPicture() = runSkikoComposeUiTest(size = Size(1728f, 936f)) {
        val state = ProjectState().apply { open(scene(), File("../project.xml")) }
        setContent { WorkspaceContent(null, {}, null, mutableListOf(), project = state, initialShortcuts = true) }
        waitUntil(timeoutMillis = 30000) { onAllNodesWithTag("rendered-${state.scene.hashCode()}-${state.selected}").fetchSemanticsNodes().isNotEmpty() }
        val shots = File("build/ui-shots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("08-shortcuts.png"))
    }
}
