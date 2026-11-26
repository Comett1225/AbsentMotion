package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskWindow
import app.absentmotion.desk.Strings
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.copySelected
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.Timeline
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskPasteStyleTest {
    private val shots = File("build/ui-shots/paste-style").also { it.mkdirs() }

    @After fun clear() { MultiSelect.clear(); DeskWindow.chrome = DeskWindow.Chrome() }

    private fun scene() = Scene.parse("""
        <scene title="Styles" width="640" height="360" fps="30" totalTime="3000" bgcolor="#ff1b1d27">
            <shape id="1" label="Source" startTime="0" endTime="3000" fillType="color" s=".rect" blending="screen">
                <transform><location value="120,180,0"/><opacity value="0.5"/></transform><fillColor value="#ffff3030"/>
                <property name="size" type="vec2" value="60,60"/>
                <border direction="outside" id="1"><color value="#ffffffff"/><size value="8"/></border>
                <effect id="absent.glow" hidden="false"><property name="radius" type="float" value="40"/></effect>
            </shape>
            <shape id="2" label="Target" startTime="0" endTime="3000" fillType="color" s=".rect">
                <transform><location value="320,180,0"/></transform><fillColor value="#ff3060ff"/><property name="size" type="vec2" value="60,60"/>
            </shape>
            <shape id="3" label="Third" startTime="0" endTime="3000" fillType="color" s=".rect">
                <transform><location value="520,180,0"/></transform><fillColor value="#ff30ff60"/><property name="size" type="vec2" value="60,60"/>
            </shape>
        </scene>
    """.trimIndent())

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(300)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    @Test fun theEditMenuPastesEachPartOntoTheChosenLayers() {
        System.setProperty("am.data", File("build/paste-style-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
            DeskWindow.chrome = DeskWindow.Chrome(end = 138f)
            setContent { DeskApp(app) }
            runBlocking { app.openNow(app.store.create(scene())) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            mainClock.advanceTimeBy(800)
            val editor = app.editor!!
            val pasteStyle = Strings.get(R.string.menu_paste_style)
            fun inMenu(label: String): SemanticsNodeInteraction {
                val nodes = onAllNodesWithText(label, substring = false)
                return nodes[nodes.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.left in 1f..860f }.minBy { it.value.boundsInRoot.left }.index]
            }
            fun openPasteStyle() {
                Thread.sleep(350)
                onAllNodesWithContentDescription(Strings.get(R.string.desk_menu)).onFirst().performClick()
                mainClock.advanceTimeBy(300)
                onAllNodesWithText(Strings.get(R.string.history_edit), substring = false).onFirst().performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(300)
                inMenu(pasteStyle).performClick()
                mainClock.advanceTimeBy(300)
            }
            runOnIdle { editor.selectedId = 1; editor.copySelected(); editor.selectedId = 2 }
            openPasteStyle()
            shot("edit-menu-paste-style")
            for (part in listOf(R.string.structure_paste_fill_color, R.string.structure_paste_border_shadow, R.string.structure_paste_blending, R.string.effects_paste_effects, R.string.structure_paste_motions))
                onAllNodesWithText(Strings.get(part), substring = false).onFirst().assertExists()
            onAllNodesWithText(Strings.get(R.string.effects_paste_effects), substring = false).onFirst().performClick()
            mainClock.advanceTimeBy(500)
            runOnIdle {
                assertEquals(listOf("absent.glow"), editor.scene.layer(2)!!.effects.map { it["id"] })
                assertEquals("#ff3060ff", editor.scene.layer(2)!!.node.child("fillColor")!!["value"])
                MultiSelect.ids = setOf(2L, 3L)
                MultiSelect.active = true
            }
            openPasteStyle()
            shot("edit-menu-paste-style-multi")
            val all = onAllNodesWithText(Strings.get(R.string.structure_paste_style), substring = false)
            all[all.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.left in 300f..860f }.single().index].performClick()
            mainClock.advanceTimeBy(500)
            runOnIdle {
                for (id in listOf(2L, 3L)) {
                    val layer = editor.scene.layer(id)!!
                    assertEquals("#ffff3030", layer.node.child("fillColor")!!["value"])
                    assertEquals("screen", layer.node["blending"])
                    assertTrue(layer.node.child("border") != null)
                    assertEquals(listOf("absent.glow"), layer.effects.map { it["id"] })
                }
            }
            runBlocking { app.closeEditor() }
        }
    }

    @Test fun theTimelineMenuOpensThePartsList() {
        System.setProperty("am.data", File("build/paste-style-timeline").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        val state = runBlocking { app.openNow(app.store.create(scene()))!! }
        runSkikoComposeUiTest(size = Size(1280f, 420f)) {
            setContent {
                CompositionLocalProvider(LocalWide provides true) {
                    Box(Modifier.fillMaxSize().background(Am.TimelineBg)) { Timeline(state, Modifier.fillMaxSize()) }
                }
            }
            runOnIdle { state.timelineZoom = 100f; state.selectedId = 1; state.copySelected(); state.selectedId = null }
            waitForIdle()
            val rows = state.scene.layers.asReversed().map { it.id }
            var at: Offset? = null
            for (y in 30..200 step 4) {
                onRoot().performMouseInput { moveTo(Offset(300f, y.toFloat())); press(); release() }
                waitForIdle()
                if (state.selectedId == rows[1]) { at = Offset(300f, y.toFloat()); break }
            }
            val row = requireNotNull(at) { "no clip row for ${rows[1]}" }
            onRoot().performMouseInput { moveTo(row); press(MouseButton.Secondary); release(MouseButton.Secondary) }
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("timeline-menu.png"))
            onNodeWithText(Strings.get(R.string.menu_paste_style)).performClick()
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("timeline-menu-parts.png"))
            onNodeWithText(Strings.get(R.string.structure_paste_border_shadow)).performClick()
            waitForIdle()
            assertTrue(state.scene.layer(rows[1])!!.node.child("border") != null)
            assertEquals("#ff3060ff", state.scene.layer(2)!!.node.child("fillColor")!!["value"])
            runOnIdle { DeskBack.back() }
        }
        app.engine.close()
    }
}
