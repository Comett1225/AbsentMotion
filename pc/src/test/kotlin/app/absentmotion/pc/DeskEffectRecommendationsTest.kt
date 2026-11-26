package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class DeskEffectRecommendationsTest {
    @get:Rule val data = TemporaryFolder()

    @Test fun topCardsFollowTheSelectionAndUseTheNormalAddAndUndoFlow() {
        val previousData = System.getProperty("am.data")
        System.setProperty("am.data", data.root.absolutePath)
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
                val photo = File("../testdata/media/test-image.jpg").absoluteFile.toURI()
                val scene = Scene.parse("""
                    <scene title="Recommendations" width="320" height="240" fps="30" totalTime="1000">
                        <shape id="1" startTime="0" endTime="1000" s=".rect" fillType="media" fillImage="$photo">
                            <transform><location value="160,120,0"/></transform><property name="size" type="vec2" value="160,120"/>
                        </shape>
                        <text id="2" startTime="0" endTime="1000">Title</text>
                    </scene>
                """.trimIndent())
                setContent { DeskApp(app) }
                runBlocking { app.openNow(app.store.create(scene)) }
                try {
                    waitUntil(timeoutMillis = 30_000) { app.engine.effects.value.definitions.isNotEmpty() && app.engine.frame != null }
                    val editor = app.editor!!
                    runOnIdle { editor.selectedId = 1; editor.effectBrowserOpen = true }
                    mainClock.advanceTimeBy(500)
                    val label = Strings.get(R.string.desk_recommended)
                    onNodeWithText(label).assertIsDisplayed()
                    for (effect in listOf("Exposure / Gamma", "Sharpen", "Vignette")) onNodeWithText(effect).assertIsDisplayed()
                    onNodeWithText("Vignette").performClick()
                    mainClock.advanceTimeBy(500)
                    runOnIdle {
                        assertEquals("absent.vignette", editor.selected!!.effects.single()["id"])
                        editor.undo()
                        assertEquals(emptyList<Any>(), editor.scene.layer(1)!!.effects)
                        editor.selectedId = 2
                        editor.effectBrowserOpen = true
                    }
                    mainClock.advanceTimeBy(500)
                    onNodeWithText(label).assertIsDisplayed()
                    for (effect in listOf("Glow", "Text Transform", "Pulse Opacity")) onNodeWithText(effect).assertIsDisplayed()
                } finally {
                    runBlocking { app.closeEditor() }
                }
            }
        } finally {
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
        }
    }

    @Test fun aGreenScreenPictureGetsChromaKeyFirst() {
        val previousData = System.getProperty("am.data")
        System.setProperty("am.data", data.root.absolutePath)
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                app.settings.apply { onboarding = ONBOARDING_DONE; tutorialAsk = 0 }
                val file = data.newFile("dancer.png")
                val image = java.awt.image.BufferedImage(160, 120, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 120) for (x in 0 until 160) image.setRGB(x, y, if (x in 60..100 && y >= 30) 0xffc89070.toInt() else 0xff00b140.toInt())
                javax.imageio.ImageIO.write(image, "png", file)
                val scene = Scene.parse("""
                    <scene title="Green screen" width="320" height="240" fps="30" totalTime="1000">
                        <shape id="1" startTime="0" endTime="1000" s=".rect" fillType="media" fillImage="${file.toURI()}">
                            <transform><location value="160,120,0"/></transform><property name="size" type="vec2" value="160,120"/>
                        </shape>
                    </scene>
                """.trimIndent())
                setContent { DeskApp(app) }
                runBlocking { app.openNow(app.store.create(scene)) }
                try {
                    waitUntil(timeoutMillis = 30_000) { app.engine.effects.value.definitions.isNotEmpty() && app.engine.frame != null }
                    runOnIdle { app.editor!!.selectedId = 1; app.editor!!.effectBrowserOpen = true }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Chroma Key").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Chroma Key").assertIsDisplayed()
                } finally {
                    runBlocking { app.closeEditor() }
                }
            }
        } finally {
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
        }
    }
}
