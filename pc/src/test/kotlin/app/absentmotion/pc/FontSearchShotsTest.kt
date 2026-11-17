package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.fonts.GoogleFonts
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.FontBrowser
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.theme.Am
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class FontSearchShotsTest {
    init { System.setProperty("am.data", File("build/font-search-data").absolutePath) }
    private val shots = File("/private/tmp/claude-501/-Users-jd/44da8514-9de8-4c0b-acc3-89282ac7865b/scratchpad/r518/font-shots").also { it.mkdirs() }
    private val list = GoogleFonts.parse(")]}'\n" + """{"familyMetadataList":[
        {"family":"Josefin Slab","category":"Serif","fonts":["300","400","400i","700"]},
        {"family":"Patua Slab","category":"Serif","fonts":["400"]},
        {"family":"Roboto Slab","category":"Serif","fonts":["400"]},
        {"family":"Rockwell Slab","category":"Serif","fonts":["400"]},
        {"family":"Lobster","category":"Display","fonts":["400"]}]}""")

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }

    @Test fun searchShowsSourceChipsAndGoogleSectionBelowADivider() {
        val library = FontLibrary(Context())
        for ((name, w, h, wide) in listOf(Quad("phone", 360, 740, false), Quad("desktop", 1280, 800, true))) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            setContent {
                CompositionLocalProvider(LocalWide provides wide) {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg)) { FontBrowser(library, {}, {}, { list }, listOf(FontName("googlefonts", "Rockwell Slab"))) }
                }
            }
            onNodeWithText("All Fonts").assertExists()
            onAllNodesWithText("Imported").assertCountEquals(0)
            onNodeWithContentDescription("Search").performClick()
            onNode(hasSetTextAction()).performTextInput("slab")
            
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Josefin Slab").fetchSemanticsNodes().isNotEmpty() }
            for (chip in listOf("All", "Imported", "App", "Device")) onNodeWithText(chip).assertExists()
            assertTrue(onAllNodesWithText("Google Fonts").fetchSemanticsNodes().size == 2)
            onNodeWithText("Patua Slab").assertExists()
            onAllNodesWithText("Roboto Slab").assertCountEquals(0)
            onNodeWithText("Rockwell Slab Regular").assertExists()
            onAllNodesWithText("Rockwell Slab").assertCountEquals(0)
            shot("$name-all")
            onAllNodesWithText("Google Fonts")[0].performClick()
            waitForIdle()
            onNodeWithText("Josefin Slab").assertExists()
            onAllNodesWithText("Google Fonts").assertCountEquals(1)
            onNodeWithText("Rockwell Slab Regular").assertDoesNotExist()
            shot("$name-google")
            onNodeWithText("Device").performClick()
            waitForIdle()
            onNodeWithText("Josefin Slab").assertDoesNotExist()
            onNodeWithText("Rockwell Slab Regular").assertExists()
            shot("$name-device")
        }
    }

    @Test fun deviceFontsOfTheDesktopCanBeStoredAndFoundAgain() {
        val library = FontLibrary(Context())
        val device = library.deviceFonts()
        org.junit.Assume.assumeTrue(device.isNotEmpty())
        for (font in device.take(20)) {
            assertTrue(font.xml, library.localFile(font.xml) != null)
            assertTrue(font.xml, FontName.parse(font.xml) == font)
        }
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Boolean)
}
