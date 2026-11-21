package app.absentmotion.pc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.ui.home.DefaultToolOrder
import app.absentmotion.ui.home.ToolsTab
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ToolDragTest {
    @Test fun holdingAToolAndDraggingItPastTheLastMovesItToTheBottom() {
        System.setProperty("am.data", File("build/tool-drag-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val app = DeskAppState()
        try {
            app.settings.toolOrder = DefaultToolOrder
            runSkikoComposeUiTest(size = Size(500f, 1400f)) {
                setContent { ToolsTab(Modifier.fillMaxSize(), app.store, app.settings, arranging = true) {} }
                waitForIdle()
                mainClock.autoAdvance = false
                val start = onNodeWithText(Strings.get(R.string.tools_compress)).fetchSemanticsNode().boundsInRoot.center
                val end = onNodeWithText(Strings.get(R.string.tools_enhance_image)).fetchSemanticsNode().boundsInRoot.center
                onRoot().performTouchInput { down(start) }
                mainClock.advanceTimeBy(800)
                for (step in 1..16) {
                    onRoot().performTouchInput { moveTo(start + (end - start) * (step / 16f)) }
                    mainClock.advanceTimeBy(32)
                }
                onRoot().performTouchInput { up() }
                mainClock.advanceTimeBy(1000)
                assertEquals(DefaultToolOrder.drop(1) + DefaultToolOrder.first(), app.settings.toolOrder)
            }
        } finally {
            app.settings.toolOrder = DefaultToolOrder
            app.engine.close()
        }
    }
}
