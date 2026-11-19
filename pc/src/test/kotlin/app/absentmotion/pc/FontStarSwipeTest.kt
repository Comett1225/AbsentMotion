package app.absentmotion.pc

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.FontBrowser
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.PressIndication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class FontStarSwipeTest {
    private val root = File("build/font-star-data").also { it.deleteRecursively(); it.mkdirs() }
    init { System.setProperty("am.data", root.absolutePath) }
    private val shots = File("/private/tmp/claude-501/-Users-jd/44da8514-9de8-4c0b-acc3-89282ac7865b/scratchpad/fontstar").also { it.mkdirs() }
    private val star = hasContentDescription("Favorite") and isToggleable()

    private fun ComposeUiTest.browser(library: FontLibrary, direction: LayoutDirection = LayoutDirection.Ltr) = setContent {
        CompositionLocalProvider(LocalWide provides false, LocalLayoutDirection provides direction, LocalIndication provides PressIndication(Am.PressEditor)) {
            Box(Modifier.fillMaxSize().background(Am.HomeBg)) { FontBrowser(library, {}, {}, { emptyList() }, emptyList()) }
        }
    }

    private fun ComposeUiTest.settle() { waitForIdle(); mainClock.advanceTimeBy(1000); waitForIdle() }

    private fun ComposeUiTest.shot(name: String) {
        settle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }

    private fun ImageBitmap.has(colour: Color): Boolean {
        val pixels = toPixelMap()
        for (x in 0 until width) for (y in 0 until height) {
            val p = pixels[x, y]
            val k = p.red / colour.red
            if (k in .75f..1.02f && abs(p.green - k * colour.green) < .03f && abs(p.blue - k * colour.blue) < .03f) return true
        }
        return false
    }

    private val ComposeUiTest.listOffset get() = onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun theStarTogglesAFavouriteAndFillsInTheFavouriteColour() {
        val library = FontLibrary(Context())
        for ((theme, palette) in listOf("dark" to Palette.AbsentMotion, "light" to Palette.Daylight)) {
            Am.palette = palette
            try {
                runSkikoComposeUiTest(size = Size(360f, 740f)) {
                    browser(library)
                    settle()
                    val first = onAllNodes(star)[0]
                    if (first.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On) { first.performClick(); settle() }
                    first.assertIsOff()
                    val count = library.favourites.size
                    assertFalse(first.captureToImage().has(Am.Favorite))
                    first.performClick()
                    mainClock.advanceTimeBy(60)
                    first.assertIsOn()
                    settle()
                    assertEquals(count + 1, library.favourites.size)
                    assertTrue(first.captureToImage().has(Am.Favorite))
                    shot("phone-$theme-star-filled")
                    first.performClick()
                    settle()
                    first.assertIsOff()
                    assertEquals(count, library.favourites.size)
                }
            } finally { Am.palette = Palette.AbsentMotion }
        }
    }

    @Test fun aRightSwipeAnywhereOpensTheDrawerAndAVerticalDragStillScrolls() {
        val library = FontLibrary(Context())
        runSkikoComposeUiTest(size = Size(360f, 740f)) {
            browser(library)
            settle()
            onNodeWithText("Sorting").assertDoesNotExist()
            val start = listOffset
            onNode(hasScrollToIndexAction()).performTouchInput { swipe(Offset(centerX - 10f, bottom - 80f), Offset(centerX + 30f, top + 80f), 300) }
            settle()
            assertTrue("list scrolled", listOffset > start)
            onNodeWithText("Sorting").assertDoesNotExist()
            onNode(hasScrollToIndexAction()).performTouchInput { swipe(Offset(centerX - 60f, centerY), Offset(centerX + 120f, centerY + 10f), 200) }
            settle()
            onNodeWithText("Sorting").assertExists()
            shot("phone-drawer-opened-by-swipe")
            onNodeWithText("Sorting").performTouchInput { swipe(Offset(right - 20f, centerY), Offset(left - 150f, centerY), 200) }
            settle()
            onNodeWithText("Sorting").assertDoesNotExist()
            onAllNodes(star)[0].performClick()
            settle()
            onNodeWithText("Sorting").assertDoesNotExist()
        }
    }

    @Test fun rightToLeftLanguagesOpenTheDrawerWithALeftSwipe() {
        val library = FontLibrary(Context())
        runSkikoComposeUiTest(size = Size(360f, 740f)) {
            browser(library, LayoutDirection.Rtl)
            settle()
            onNode(hasScrollToIndexAction()).performTouchInput { swipe(Offset(centerX - 60f, centerY), Offset(centerX + 120f, centerY), 200) }
            settle()
            onNodeWithText("Sorting").assertDoesNotExist()
            onNode(hasScrollToIndexAction()).performTouchInput { swipe(Offset(centerX + 60f, centerY), Offset(centerX - 120f, centerY), 200) }
            settle()
            onNodeWithText("Sorting").assertExists()
        }
    }
}
