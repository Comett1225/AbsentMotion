package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.ui.home.CustomizeToolsSheet
import app.absentmotion.ui.home.DefaultToolOrder
import app.absentmotion.ui.home.projectTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ToolDragTest {
    @Test fun customizeToolsReordersHidesAndKeepsAProjectListOfItsOwn() {
        System.setProperty("am.data", File("build/tool-drag-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        val settings = app.settings
        fun reset() {
            settings.toolOrder = DefaultToolOrder; settings.toolsHidden = emptySet()
            settings.projectToolOrder = DefaultToolOrder; settings.projectToolsHidden = emptySet()
        }
        try {
            reset()
            runSkikoComposeUiTest(size = Size(700f, 1400f)) {
                setContent { CustomizeToolsSheet(settings) {} }
                waitForIdle()
                mainClock.autoAdvance = false
                fun middle(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { it.positionInRoot + androidx.compose.ui.geometry.Offset(it.size.width / 2f, it.size.height / 2f) }
                val start = middle("grip-compress")
                val end = middle("grip-send")
                val anchor = onNodeWithText(Strings.get(R.string.tools_customize_page))
                val origin = anchor.fetchSemanticsNode().boundsInRoot.topLeft
                anchor.performTouchInput { down(start - origin) }
                mainClock.advanceTimeBy(100)
                for (step in 1..16) {
                    anchor.performTouchInput { moveTo(start + (end - start) * (step / 16f) - origin) }
                    mainClock.advanceTimeBy(32)
                }
                anchor.performTouchInput { up() }
                mainClock.advanceTimeBy(1000)
                assertEquals(DefaultToolOrder.drop(1) + DefaultToolOrder.first(), settings.toolOrder)
                mainClock.autoAdvance = true
                onNodeWithText(Strings.get(R.string.tools_trim)).performClick()
                waitForIdle()
                assertEquals(setOf("trim"), settings.toolsHidden)
                onNodeWithText(Strings.get(R.string.tools_not_shown)).fetchSemanticsNode()
                onNodeWithText(Strings.get(R.string.tools_customize_project)).performClick()
                waitForIdle()
                onNodeWithText(Strings.get(R.string.tools_gif)).performClick()
                waitForIdle()
                assertEquals(setOf("gif"), settings.projectToolsHidden)
                assertEquals(DefaultToolOrder, settings.projectToolOrder)
                assertFalse("gif" in projectTools(settings, importer = true))
                assertFalse("importer" in projectTools(settings, importer = false))
                assertEquals(setOf("trim"), settings.toolsHidden)
            }
        } finally {
            reset()
            app.engine.close()
        }
    }
}
