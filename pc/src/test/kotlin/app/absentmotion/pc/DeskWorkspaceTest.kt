package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskLibrary
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.editor.DeskPreviewBounds
import app.absentmotion.ui.theme.HoverTargets
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskWorkspaceTest {
    private fun ComposeUiTest.shot(dir: File, name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name.png"))
    }

    private fun ComposeUiTest.rail(name: String) {
        onAllNodesWithText(name, substring = false).filter(hasClickAction()).onFirst().performClick()
        mainClock.advanceTimeBy(250)
    }

    private fun ComposeUiTest.open(app: DeskAppState, scene: String, rewrite: (String) -> String = { it }) {
        val id = app.store.create(Scene.parse(rewrite(File("../testdata/scenes/$scene.xml").readText())))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
    }

    private fun ComposeUiTest.drag(from: Offset, to: Offset) {
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(from); press(); moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2), 150); moveTo(to, 250); release() }
        mainClock.advanceTimeBy(200)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.centre(): Offset {
        val r = getUnclippedBoundsInRoot()
        return Offset((r.left.value + r.right.value) / 2, (r.top.value + r.bottom.value) / 2)
    }

    @Test fun workspace() {
        val data = File("build/workspace-test-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val media = File("../testdata/media").canonicalFile
        DeskLibrary.link(media)
        val shots = File("build/ui-shots/editor").also { it.mkdirs() }
        for ((width, height) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                open(app, "styles")
                val editor = app.editor!!
                val wide = width >= 1100
                shot(shots, "${width}x${height}-project")
                if (wide) {
                    onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.One); keyUp(Key.CtrlLeft) }
                    mainClock.advanceTimeBy(200)
                    onAllNodesWithText("Media", substring = false).filter(hasClickAction()).onFirst().assertExists()
                    shot(shots, "${width}x${height}-collapsed")
                    rail("Shape")
                    onAllNodesWithText("Circle", substring = false).filter(hasClickAction()).onFirst().assertExists()
                    rail("Shape")
                    onAllNodesWithText("Circle", substring = false).filter(hasClickAction()).assertCountEquals(0)
                    rail("Media")
                }
                runOnIdle { editor.selectedId = editor.scene.layers.last().id }
                mainClock.advanceTimeBy(500)
                shot(shots, "${width}x${height}-selected")
                runOnIdle { editor.panel = EditorPanel.Effects }
                mainClock.advanceTimeBy(500)
                shot(shots, "${width}x${height}-panel")
                runOnIdle { editor.panel = EditorPanel.Tools; editor.effectBrowserOpen = true }
                mainClock.advanceTimeBy(500)
                shot(shots, "${width}x${height}-effects")
                runOnIdle { editor.effectBrowserOpen = false; editor.fullColor = FullColor(0xff336699.toInt()) { _, _ -> } }
                mainClock.advanceTimeBy(500)
                shot(shots, "${width}x${height}-colour")
                runOnIdle { editor.fullColor = null; editor.presetsOpen = true }
                mainClock.advanceTimeBy(500)
                shot(shots, "${width}x${height}-presets")
                runOnIdle { editor.presetsOpen = false; editor.selectedId = null }
                mainClock.advanceTimeBy(300)
                for (section in listOf("Audio", "Text", "Objects", "Template", "Draw", "Media")) {
                    rail(section)
                    shot(shots, "${width}x${height}-" + section.lowercase())
                }
                rail("Shape")
                shot(shots, "${width}x${height}-shapes")
                runOnIdle { editor.selectedId = null }
                onNodeWithContentDescription("Project settings").performClick()
                mainClock.advanceTimeBy(300)
                shot(shots, "${width}x${height}-settings")
                DeskBack.back()
                mainClock.advanceTimeBy(300)
                if (width == 1280) workspaceActions(app, shots)
                runBlocking { app.closeEditor() }
            }
        }
    }

    private fun ComposeUiTest.workspaceActions(app: DeskAppState, shots: File) {
        val editor = app.editor!!
        val before = editor.scene.layers.size
        val circle = onAllNodesWithText("Circle", substring = false).filter(hasClickAction()).onFirst()
        circle.performMouseInput { moveTo(center) }
        check(HoverTargets.count > 0)
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(5f, 5f)) }
        mainClock.advanceTimeBy(300)
        check(HoverTargets.count == 0)
        circle.performClick()
        runOnIdle { check(editor.scene.layers.size == before + 1); editor.undo(); check(editor.scene.layers.size == before) }
        circle.performMouseInput { rightClick() }
        mainClock.advanceTimeBy(200)
        shot(shots, "1280x800-context")
        runOnIdle { check(editor.scene.layers.size == before) }
        onNodeWithText("Add", substring = false).performClick()
        runOnIdle { check(editor.scene.layers.size == before + 1); editor.undo(); check(editor.scene.layers.size == before) }
        val start = circle.centre()
        val preview = DeskPreviewBounds.bounds
        runOnIdle { DeskDrop.frameAt = { null } }
        drag(start, preview.center)
        runOnIdle { check(editor.scene.layers.size == before + 1); editor.undo(); check(editor.scene.layers.size == before) }
        drag(start, Offset(start.x + 40f, start.y + 120f))
        runOnIdle { check(editor.scene.layers.size == before); check(DeskDrop.item == null) }
        runOnIdle { DeskDrop.frameAt = { 10 }; editor.frame = 0 }
        drag(start, Offset(preview.center.x, preview.bottom + 100f))
        runOnIdle {
            check(editor.frame == 10)
            check(editor.scene.layers.size == before + 1)
            editor.undo()
            check(editor.scene.layers.size == before)
            DeskDrop.frameAt = { null }
        }
        runOnIdle { editor.selectedId = null }
        mainClock.advanceTimeBy(300)
        onNodeWithText("1:1", substring = false).performClick()
        runOnIdle { check(editor.scene.width == editor.scene.height); editor.undo() }
        mainClock.advanceTimeBy(300)
        val layers = editor.scene.layers.size
        onAllNodesWithContentDescription("Hide layer").onFirst().performClick()
        runOnIdle { check(editor.scene.layers.size == layers); check(editor.scene.layers.any { it.hidden }); editor.undo() }
        runOnIdle { editor.selectedId = editor.scene.layers.last().id }
        mainClock.advanceTimeBy(300)
        shot(shots, "1280x800-selected-again")
        for (panel in listOf(EditorPanel.MoveTransform, EditorPanel.ColorFill, EditorPanel.EditShape, EditorPanel.BorderShadow, EditorPanel.Blending, EditorPanel.Crop, EditorPanel.Speed)) {
            runOnIdle { editor.panel = panel }
            mainClock.advanceTimeBy(400)
            shot(shots, "1280x800-panel-" + panel.name.lowercase())
        }
        runOnIdle { editor.panel = EditorPanel.Tools; editor.selectedId = null }
        runBlocking { app.closeEditor() }
        val repo = File("..").canonicalFile
        val xml = File("../testdata/scenes/media.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        val mediaId = app.store.create(Scene.parse(xml))
        runBlocking { app.openNow(mediaId) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(800)
        shot(shots, "1280x800-media-scene")
        runOnIdle { app.editor!!.selectedId = app.editor!!.scene.layers.first().id }
        mainClock.advanceTimeBy(300)
        shot(shots, "1280x800-media-selected")
        runOnIdle { app.editor!!.let { it.selectedId = it.scene.layers.first { layer -> layer.kind == "audio" }.id; it.volumeOpen = true } }
        mainClock.advanceTimeBy(400)
        shot(shots, "1280x800-volume")
        runOnIdle { app.editor!!.volumeOpen = false; app.editor!!.selectedId = null }
        onAllNodesWithText("test-image", substring = true).filter(hasClickAction()).onFirst().performMouseInput { rightClick() }
        mainClock.advanceTimeBy(300)
        onNodeWithText("Show in file manager", substring = false).assertExists()
        shot(shots, "1280x800-media-context")
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(700f, 300f)); click() }
        mainClock.advanceTimeBy(300)
        runOnIdle { app.editor!!.let { e -> MultiSelect.ids = e.scene.layers.take(2).map { it.id }.toSet(); MultiSelect.active = true } }
        mainClock.advanceTimeBy(400)
        shot(shots, "1280x800-multi")
        runOnIdle { MultiSelect.clear() }
        runBlocking { app.closeEditor() }
        val textId = app.store.create(Scene.parse(File("../testdata/scenes/text.xml").readText()))
        runBlocking { app.openNow(textId) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(800)
        runOnIdle { app.editor!!.let { it.selectedId = it.scene.layers.first().id; it.panel = EditorPanel.EditText } }
        mainClock.advanceTimeBy(500)
        shot(shots, "1280x800-text-editor")
        runBlocking { app.closeEditor() }
    }

    @Test fun highDensityAndNarrow() {
        System.setProperty("am.data", File("build/workspace-test-data").absolutePath)
        val shots = File("build/ui-shots/editor").also { it.mkdirs() }
        for ((width, height, scale) in listOf(Triple(800, 700, 1), Triple(1024, 700, 2), Triple(1280, 800, 2), Triple(1728, 1080, 2), Triple(2560, 1440, 2))) {
            runSkikoComposeUiTest(size = Size((width * scale).toFloat(), (height * scale).toFloat()), density = Density(scale.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                open(app, "styles")
                val name = "${width}x${height}-density$scale"
                shot(shots, name)
                runOnIdle { app.editor!!.selectedId = app.editor!!.scene.layers.last().id }
                mainClock.advanceTimeBy(500)
                shot(shots, "$name-selected")
                if (width < 1100) {
                    rail("Shape")
                    shot(shots, "$name-library")
                }
                runBlocking { app.closeEditor() }
            }
        }
    }
}
