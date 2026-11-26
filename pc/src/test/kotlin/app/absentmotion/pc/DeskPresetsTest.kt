package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.editor.EditorPanel
import app.absentmotion.effects.PresetFavouriteStore
import app.absentmotion.library.PresetStore
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskPresetsTest {
    private val shots = File("build/ui-shots/presets").also { it.mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.settle(ms: Long = 500) { mainClock.advanceTimeBy(ms); waitForIdle() }

    @Test fun effectPresetsFavouritesAndSavedPresetsWork() {
        System.setProperty("am.data", File("build/presets-desk-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
            setContent { DeskApp(app) }
            val scene = Scene.parse("""
                <scene title="Presets" width="640" height="360" fps="30" totalTime="3000" bgcolor="#ff1b1d27">
                    <text id="1" label="Words" startTime="0" endTime="3000" fillType="color" size="40" font="googlefonts?name=Roboto&amp;weight=700" wrapWidth="600" align="center">
                        <transform><location value="320,120,0"/></transform><fillColor value="#ffffffff"/><content>Hello there</content>
                    </text>
                    <shape id="2" label="Box" startTime="0" endTime="3000" fillType="color" s=".rect">
                        <transform><location value="320,260,0"/></transform><fillColor value="#ff3060ff"/><property name="size" type="vec2" value="60,60"/>
                        <border direction="outside" id="1"><color value="#ffffffff"/><size value="8"/></border>
                        <effect id="absent.glow" hidden="false"><property name="radius" type="float" value="40"/></effect>
                    </shape>
                </scene>
            """.trimIndent())
            runBlocking { app.openNow(app.store.create(scene)); app.engine.awaitEffects() }
            waitUntil(timeoutMillis = 30_000) { app.engine.effects.value.definitions.isNotEmpty() && app.engine.frame != null }
            settle(800)
            val editor = app.editor!!
            fun search(name: String) {
                runOnIdle { editor.selectedId = 1; editor.effectBrowserOpen = true }
                settle()
                onAllNodes(hasSetTextAction()).onLast().performClick()
                onAllNodes(hasSetTextAction()).onLast().performTextClearance()
                onAllNodes(hasSetTextAction()).onLast().performTextInput(name)
                settle(800)
                onAllNodes(hasText(name) and hasClickAction() and !hasSetTextAction()).onFirst().performMouseInput { rightClick() }
                settle(800)
            }

            search("Text Progress")
            shot("browser-detail-presets")
            onAllNodesWithText("Typewriter", substring = false).onFirst().performClick()
            settle(800)
            runOnIdle {
                val added = editor.scene.layer(1)!!.effects.single()
                assertEquals("absent.textprogress", added["id"])
                assertTrue(added.toXml(), added.children.any { p -> p.children.any { it.name == "kf" } })
                editor.frame = 12
            }
            settle(1500)
            shot("text-progress-typewriter-preview")

            search("Text Shake")
            onAllNodesWithContentDescription(Strings.get(R.string.effectbrowser_favorite_preset)).onFirst().performClick()
            settle()
            runOnIdle { assertTrue(PresetFavouriteStore.get(app.context).keys.toString(), "absent.textshake#nervous" in PresetFavouriteStore.get(app.context).keys) }
            runOnIdle { editor.effectBrowserOpen = false }
            settle()
            runOnIdle { editor.selectedId = 1; editor.effectBrowserOpen = true }
            settle()
            onAllNodesWithText(Strings.get(R.string.effectbrowser_favorite), substring = false).onFirst().performClick()
            settle()
            shot("browser-favourite-preset")
            onAllNodes(hasText("Nervous") and hasClickAction() and !hasSetTextAction()).onFirst().performClick()
            settle(800)
            runOnIdle {
                val shake = editor.scene.layer(1)!!.effects.last()
                assertEquals("absent.textshake", shake["id"])
                val nervous = app.engine.effects.value.find("absent.textshake")!!.presets.first { it.name == "Nervous" }
                for ((name, value) in nervous.values) assertEquals(name, value, shake.children.first { it["name"] == name }["value"])
            }

            runOnIdle { editor.effectBrowserOpen = false; editor.selectedId = 1; editor.effectIndex = 1; editor.panel = EditorPanel.Effects }
            settle(800)
            shot("effect-panel")
            if (onAllNodesWithText(Strings.get(R.string.presets), substring = false).fetchSemanticsNodes().isNotEmpty()) {
                onAllNodesWithText(Strings.get(R.string.presets), substring = false).onLast().performClick()
                settle()
                shot("effect-panel-presets")
                onAllNodesWithText("Wavy", substring = false).onFirst().performClick()
                settle()
                runOnIdle {
                    val shake = editor.scene.layer(1)!!.effects.last()
                    val wavy = app.engine.effects.value.find("absent.textshake")!!.presets.first { it.name == "Wavy" }
                    for ((name, value) in wavy.values) assertEquals(name, value, shake.children.first { it["name"] == name }["value"])
                }
            } else println("NO PRESETS BUTTON IN THE EFFECT PANEL")

            runOnIdle { editor.panel = EditorPanel.Tools; editor.selectedId = 2 }
            settle()
            onNodeWithContentDescription(Strings.get(R.string.play_copy_paste)).performClick()
            settle()
            onNodeWithText(Strings.get(R.string.menu_save_preset)).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(Strings.get(R.string.preset_stored)).fetchSemanticsNodes().isNotEmpty() }
            shot("preset-saved")
            onAllNodesWithText(Strings.get(R.string.action_close), substring = false).onLast().performClick()
            settle()
            val saved = PresetStore(app.context).list()
            assertEquals(1, saved.size)
            runOnIdle { editor.selectedId = 1; editor.panel = EditorPanel.Tools }
            settle()
            onAllNodesWithText(Strings.get(R.string.presets), substring = false).onLast().performClick()
            settle(800)
            shot("preset-screen-open")
            onAllNodesWithText(saved.single().title, substring = false).onLast().performClick()
            settle(1500)
            shot("preset-screen")
            onAllNodesWithText(Strings.get(R.string.apply), substring = false).onFirst().performClick()
            settle(800)
            runOnIdle {
                val words = editor.scene.layer(1)!!
                assertEquals(listOf("absent.glow"), words.effects.map { it["id"] })
                assertTrue(words.node.child("border") != null)
                assertTrue(!editor.presetsOpen)
            }
            shot("preset-applied")
            runBlocking { app.closeEditor() }
        }
    }
}
