package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.absentmotion.library.CommunityIndex
import app.absentmotion.library.CommunityItem
import app.absentmotion.library.CommunityKind
import app.absentmotion.library.CommunitySort
import app.absentmotion.ui.home.CommunityGuidelines
import app.absentmotion.ui.home.CommunityBrowseControls
import app.absentmotion.ui.home.CommunityBrowseState
import app.absentmotion.ui.home.CommunityTab
import app.absentmotion.ui.home.communityGuidelines
import app.absentmotion.ui.home.sessionAllowed
import app.absentmotion.ui.home.sessionIndex
import app.absentmotion.ui.theme.Am
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CommunityBrowseDeskTest {
    private val projects = listOf(
        item("Été Glow", CommunityKind.Bundle, listOf("intro", "neon"), "2026-11-17"),
        item("Apple", CommunityKind.Bundle, listOf("intro"), "2026-11-16"),
        item("Pulse", CommunityKind.Preset, listOf("intro", "neon"), "2026-11-15")
    )

    private fun item(title: String, kind: CommunityKind, tags: List<String>, added: String) =
        CommunityItem(kind, title, title, "Álice", "", tags, added, "", 100, "a".repeat(64))

    @Test fun browsingSearchesBothPagesAndClearsAnEmptyResult() {
        val before = sessionIndex
        sessionIndex = CommunityIndex(projects)
        try {
            runSkikoComposeUiTest(size = Size(1100f, 800f)) {
                sessionAllowed = false
                setContent { CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg)) {} }
                onNodeWithText("Été Glow").assertDoesNotExist()
                onNodeWithText("Connect").performClick()
                onNode(hasSetTextAction()).performTextReplacement("alice intro")
                onNodeWithText("Été Glow").assertExists()
                onNodeWithText("Apple").assertExists()
                onNodeWithText("Tags").performClick()
                onNodeWithText("2").assertExists()
                onNodeWithText("neon").performClick()
                onNodeWithContentDescription("Close").performClick()
                onNodeWithText("Apple").assertDoesNotExist()
                onNodeWithText("Presets").performClick()
                mainClock.advanceTimeBy(1000)
                onNodeWithText("Pulse").assertExists()
                onNodeWithContentDescription("Remove neon filter").assertExists()
                onNodeWithText("Tags").performClick()
                onAllNodesWithText("1").assertCountEquals(2)
                onNodeWithContentDescription("Close").performClick()
                onNode(hasSetTextAction()).assertTextContains("alice intro")
                onNode(hasSetTextAction()).performTextReplacement("missing")
                onAllNodesWithText("No items match")[1].assertIsDisplayed()
                onAllNodesWithText("Clear search and filters")[1].performClick()
                onNodeWithText("Pulse").assertExists()
            }
        } finally {
            sessionIndex = before
        }
    }

    @Test fun sortAndTagsCycleWithoutMovingThePagesWhileTyping() {
        val state = CommunityBrowseState()
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            setContent { CommunityBrowseControls(state, projects) }
            onNodeWithContentDescription("Sort").performClick()
            onNodeWithText("Newest first").assertIsSelected()
            onNodeWithText("A to Z").performClick()
            assertEquals(CommunitySort.Title, state.sort)
            onNodeWithText("Tags").performClick()
            onAllNodesWithText("intro").onLast().performClick()
            assertEquals(setOf("intro"), state.include)
            onAllNodesWithText("intro").onLast().performClick()
            assertEquals(setOf("intro"), state.exclude)
            assertTrue(state.include.isEmpty())
            onAllNodesWithText("intro").onLast().performClick()
            assertTrue(state.exclude.isEmpty())
            onNodeWithText("neon").performClick()
            onNodeWithContentDescription("Close").performClick()
            val before = onNodeWithText("Tags").fetchSemanticsNode().boundsInRoot
            onNode(hasSetTextAction()).performTextReplacement("alice")
            assertEquals(before, onNodeWithText("Tags").fetchSemanticsNode().boundsInRoot)
            onNodeWithContentDescription("Remove neon filter").performClick()
            assertTrue(state.include.isEmpty())
            assertEquals("alice", state.query)
        }
    }

    @Test fun communityFitsSmallAndLargeWindowsWithLargeText() {
        val before = sessionIndex
        sessionIndex = CommunityIndex(projects)
        val shots = File("build/ui-shots/community").also { it.mkdirs() }
        try {
            for ((name, size) in listOf("phone-large-text" to Size(390f, 844f), "tablet-large-text" to Size(900f, 1100f), "desktop" to Size(1280f, 800f))) {
                runSkikoComposeUiTest(size = size) {
                    sessionAllowed = false
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, if (name == "desktop") 1f else 1.7f)) {
                            CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg)) {}
                        }
                    }
                    onNodeWithText("Connect").performClick()
                    onNodeWithText("Tags").assertIsDisplayed()
                    onNodeWithContentDescription("Sort").assertIsDisplayed()
                    onNode(hasSetTextAction()).performTextReplacement("")
                    onNodeWithText("Projects").performClick()
                    mainClock.advanceTimeBy(1000)
                    onNodeWithText("Été Glow").assertIsDisplayed()
                    ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
                }
            }
        } finally {
            sessionIndex = before
        }
    }

    @Test fun guidelinesFallBackToEnglish() {
        assertEquals("Community Guidelines", communityGuidelines(android.content.Desk.context, "missing-ZZ").title)
    }
    @Test fun guidelinesFitASmallWindowWithLargeText() {
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.7f)) {
                    CommunityGuidelines {}
                }
            }
            onNodeWithText("Community Guidelines").assertIsDisplayed()
            onNodeWithContentDescription("Close").assertIsDisplayed()
            val shots = File("build/ui-shots/community").also { it.mkdirs() }
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("guidelines-large-text.png"))
        }
    }

}
