package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.model.PropRef
import app.absentmotion.media.ModelFactsCache
import app.absentmotion.model.Scene
import app.absentmotion.model.materialTint
import app.absentmotion.model.modelKey
import app.absentmotion.model.posed
import app.absentmotion.model.withMaterialPicture
import app.absentmotion.model.withMaterialTint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskModelPanelsTest {
    private val out = File("build/model-panels").canonicalFile

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun texturesAndPosePanelsShowTheModelsMaterialsAndBones() {
        out.deleteRecursively()
        val rig = File(out, "media/model-1/rig.gltf").apply { parentFile.mkdirs(); writeText(riggedGltf(9)) }
        val box = File(out, "media/model-2/Model.obj").apply {
            parentFile.mkdirs()
            writeText("mtllib Model.mtl\nv -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nusemtl Part1Mtl\nf 1/1 2/2 3/3\nf 1/1 3/3 4/4\n")
            File(parentFile, "Model.mtl").writeText("newmtl Part1Mtl\nKd 1 1 1\nmap_Kd Model_tex.png\n")
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.RED; fillRect(0, 0, 8, 8); dispose() } }
            ImageIO.write(image, "png", File(parentFile, "texture-red.png"))
        }
        fun uri(f: File) = f.toURI().toString().replaceFirst("file:/", "file:///")
        System.setProperty("am.data", File(out, "data").path)
        runSkikoComposeUiTest(size = Size(1600f, 1000f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(Scene.parse("""<scene title="models" width="1080" height="1080" fps="30" totalTime="3000" bgcolor="#ff202020">
<model id="1" label="Rig" startTime="0" endTime="3000" src="${uri(rig)}" pose="skin"><transform><location value="380,540,0"/></transform><property name="size" type="float" value="300"/></model>
<model id="2" label="Box" startTime="0" endTime="3000" src="${uri(box)}" pose="skin"><transform><location value="760,540,0"/></transform><property name="size" type="float" value="200"/></model>
</scene>"""))
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            val editor = app.editor!!
            val keys = editor.scene.layers.map { it.node.modelKey()!! }
            waitUntil(timeoutMillis = 30_000) { keys.all { it in ModelFactsCache.known.value } }

            runOnIdle { editor.selectedId = 1; editor.panel = EditorPanel.Tools }
            mainClock.advanceTimeBy(800)
            shot("rig-tools")
            assertTrue(has("Textures") && has("Pose"))
            runOnIdle { editor.panel = EditorPanel.Pose }
            mainClock.advanceTimeBy(800)
            waitUntil(timeoutMillis = 30_000) { mainClock.advanceTimeBy(100); editor.layerPoints?.points?.size == 2 }
            assertTrue(has("Root") && has("Tip") && has("Turn X") && has("Turn Y") && has("Turn Z"))
            assertEquals(listOf(0 to 1), editor.layerPoints!!.lines)
            onAllNodesWithText("Tip").onFirst().performClick()
            runOnIdle { assertEquals(1, editor.layerPoints!!.selected) }
            runOnIdle { editor.toggleKeyframe() }
            runOnIdle {
                assertEquals(1, editor.scene.layer(1)!!.keyframes(PropRef.shape("bone.1.z")).size)
                assertEquals(1, Scene.parse(editor.scene.toXml()).layer(1)!!.keyframes(PropRef.shape("bone.1.z")).size)
            }
            runOnIdle { editor.editSelected { it.withProp("bone.1.z", "float", "60") } }
            mainClock.advanceTimeBy(800)
            waitUntil(timeoutMillis = 30_000) { mainClock.advanceTimeBy(100); editor.layerPoints?.points?.get(0)?.let { true } == true }
            shot("rig-pose")
            onAllNodesWithText("Reset pose").onFirst().performClick()
            runOnIdle { assertFalse(editor.scene.layer(1)!!.posed) }
            runOnIdle { editor.undo() }
            runOnIdle { assertTrue(editor.scene.layer(1)!!.posed) }

            runOnIdle { editor.selectedId = 2; editor.panel = EditorPanel.Tools }
            mainClock.advanceTimeBy(800)
            assertTrue(has("Textures"))
            assertFalse(has("Pose"))
            runOnIdle { editor.panel = EditorPanel.Textures }
            mainClock.advanceTimeBy(800)
            shot("box-textures-missing")
            assertTrue(has("1 material") && has("1 picture missing") && has("Missing: Model_tex.png") && has("Add missing pictures (1)") && has("Part1Mtl"))
            runOnIdle { editor.editSelected { it.withMaterialPicture(0, "texture-red.png").withMaterialTint(0, 0xff8080ff.toInt()) } }
            val replaced = runOnIdle { editor.scene.layer(2)!!.node.modelKey()!! }
            waitUntil(timeoutMillis = 30_000) { replaced in ModelFactsCache.known.value }
            mainClock.advanceTimeBy(800)
            shot("box-textures-replaced")
            assertTrue(has("All pictures found") && has("red.png") && has("Add missing pictures"))
            runOnIdle { editor.undo() }
            runOnIdle { assertEquals(-1, editor.scene.layer(2)!!.materialTint(0)) }
            mainClock.advanceTimeBy(800)
            assertTrue(has("1 picture missing"))
            runOnIdle { editor.redo() }
            runOnIdle { assertEquals(0xff8080ff.toInt(), editor.scene.layer(2)!!.materialTint(0)) }
            runBlocking { app.closeEditor() }
        }
    }
}
