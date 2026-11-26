package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
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
class DeskAlightEffectsTest {
    private val shots = File("build/ui-shots/alight-effects").also { it.mkdirs() }

    private fun ComposeUiTest.settle(ms: Long = 500) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun ComposeUiTest.shot(name: String) {
        settle(400)
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    @Test fun alightNamesAndCategoriesFindTheEffects() {
        System.setProperty("am.data", File("build/alight-effects-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
            setContent { DeskApp(app) }
            val scene = Scene.parse("""
                <scene title="Effects" width="640" height="360" fps="30" totalTime="3000" bgcolor="#ff1b1d27">
                    <shape id="1" label="Box" startTime="0" endTime="3000" fillType="color" s=".rect">
                        <transform><location value="320,180,0"/></transform><fillColor value="#ff3060ff"/><property name="size" type="vec2" value="80,80"/>
                    </shape>
                </scene>
            """.trimIndent())
            runBlocking { app.openNow(app.store.create(scene)); app.engine.awaitEffects() }
            waitUntil(timeoutMillis = 30_000) { app.engine.effects.value.definitions.isNotEmpty() && app.engine.frame != null }
            val editor = app.editor!!
            val catalog = app.engine.effects.value
            assertTrue(catalog.definitions.filter { it.status != "ok" }.map { it.key }.toString(), catalog.definitions.all { it.available })
            runOnIdle { editor.selectedId = 1; editor.effectBrowserOpen = true }
            shot("browser-home")
            onAllNodesWithText(catalog.categories.toMap().getValue("color"), substring = false).onFirst().performClick()
            settle()
            shot("browser-color")
            onAllNodes(hasScrollToIndexAction()).filterToOne(hasAnyDescendant(hasText("Bump Map"))).performScrollToNode(hasText("Light Glow"))
            onAllNodesWithText("Light Glow", substring = false).onFirst().assertExists()
            shot("browser-color-light-glow")
            onNodeWithContentDescription(Strings.get(R.string.topbar_back)).performClick()
            settle()
            onAllNodes(hasSetTextAction()).onLast().performClick()
            onAllNodes(hasSetTextAction()).onLast().performTextInput("Color Tune")
            settle(800)
            shot("browser-search-color-tune")
            onAllNodes(hasText("Color Tune") and hasClickAction() and !hasSetTextAction()).onFirst().performClick()
            settle()
            runOnIdle { assertEquals(listOf("absent.colorwheels"), editor.scene.layer(1)!!.effects.map { it["id"] }) }
            runBlocking { app.closeEditor() }
        }
    }
}
