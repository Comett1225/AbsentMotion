package app.absentmotion.pc

import android.content.Desk
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskAppState
import app.absentmotion.ui.home.ThemeGroups
import app.absentmotion.ui.home.themeJson
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.ThemeFile
import app.absentmotion.ui.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskThemeMakerTest {
    private fun builtIn(id: String) = Palette.BuiltIn.first { it.first == id }.second

    @Test fun everyColorIsListedOnceAndAThemeFileKeepsEveryColor() {
        val keys = ThemeGroups.flatMap { g -> g.colors.map { it.first } }
        assertEquals(Palette.Names.sorted(), keys.sorted())
        for ((_, palette) in Palette.BuiltIn) assertEquals(palette, ThemeFile.parse(themeJson("Mocha", palette.toMap())).palette)
        assertEquals("Mocha", ThemeFile.parse(themeJson(" Mocha ", builtIn("Dark").toMap())).name)
    }

    @Test fun selectionColorsFollowTheAccentUnlessAThemeSetsThem() {
        val plain = ThemeFile.parse("{\"Accent\": \"#FF0000\"}").palette
        assertEquals(null, plain.SelectionBorder)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF0000), plain.toMap()["SelectionBorder"])
        val own = ThemeFile.parse("{\"Accent\": \"#FF0000\", \"SelectionBorder\": \"#00FF00\", \"SelectionHandle\": \"#0000FF\"}").palette
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00FF00), own.SelectionBorder)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0000FF), own.SelectionHandle)
        assertEquals(own, ThemeFile.parse(themeJson("Own", own.toMap())).palette)
    }

    @Test fun aThemeIsMadeSavedAndClosingWithoutSavingPutsTheOldLookBack() {
        val dir = File("build/theme-maker").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val settings = DeskAppState().settings
        settings.theme = "Daylight"
        val shots = File("build/theme-maker-shots").absoluteFile.also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(900f, 1100f)) {
            setContent { Column { app.absentmotion.ui.home.EditorSettings(settings) } }
            waitForIdle()
            fun open() {
                onAllNodesWithText(settings.themeLabel.let { if (settings.theme == "Daylight") "Daylight" else "Custom (Mocha)" }).onFirst().performClick()
                waitForIdle()
                onAllNodesWithText("Create theme…").onFirst().performClick()
                waitForIdle()
            }
            open()
            assertTrue(onAllNodesWithText("Accent").fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText("The main color: selections, sliders and main buttons").fetchSemanticsNodes().isNotEmpty())
            onAllNodesWithText("Dark").onLast().performClick()
            waitForIdle()
            assertEquals(builtIn("Dark"), Am.palette)
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("Mocha")
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("maker.png"))
            onAllNodesWithText("Save theme").onFirst().performClick()
            waitForIdle()
            assertEquals(Themes.CUSTOM, settings.theme)
            val saved = Themes.custom(Desk.context)!!
            assertEquals("Mocha", saved.name)
            assertEquals(builtIn("Dark"), saved.palette)
            assertEquals(builtIn("Dark"), Am.palette)
            open()
            onAllNodesWithText("Light").onLast().performClick()
            waitForIdle()
            assertEquals(builtIn("AbsentMotion"), Am.palette)
            onAllNodesWithContentDescription("Close").onLast().performClick()
            waitForIdle()
            assertEquals(builtIn("Dark"), Am.palette)
            assertEquals(Themes.CUSTOM, settings.theme)
        }
    }

    @Test fun aThemeFileCanBeLoadedIntoThePageAndEdited() {
        val dir = File("build/theme-maker-load").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val file = dir.resolve("mocha.json").apply { writeText("{\"name\": \"Catppuccin Mocha\", \"Accent\": \"#CBA6F7\", \"TopBar\": \"#1E1E2E\"}") }
        val settings = DeskAppState().settings
        settings.theme = "Daylight"
        System.setProperty("am.pick", file.path)
        try {
            runSkikoComposeUiTest(size = Size(900f, 1100f)) {
                setContent { Column { app.absentmotion.ui.home.EditorSettings(settings) } }
                waitForIdle()
                onAllNodesWithText("Daylight").onFirst().performClick()
                waitForIdle()
                onAllNodesWithText("Create theme…").onFirst().performClick()
                waitForIdle()
                onAllNodesWithText("Import theme file…").onLast().performClick()
                waitForIdle()
                assertEquals(androidx.compose.ui.graphics.Color(0xFFCBA6F7), Am.palette.Accent)
                assertEquals(androidx.compose.ui.graphics.Color(0xFF1E1E2E), Am.palette.TopBar)
                assertTrue(onAllNodesWithText("Catppuccin Mocha").fetchSemanticsNodes().isNotEmpty())
                assertEquals("Daylight", settings.theme)
                onAllNodesWithText("Save theme").onFirst().performClick()
                waitForIdle()
                assertEquals(Themes.CUSTOM, settings.theme)
                assertEquals("Catppuccin Mocha", Themes.custom(Desk.context)!!.name)
            }
        } finally {
            System.clearProperty("am.pick")
        }
    }
}
