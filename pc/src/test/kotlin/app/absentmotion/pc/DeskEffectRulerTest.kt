package app.absentmotion.pc

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.editor.panels.EffectRuler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskEffectRulerTest {
    @Test fun wheelScrollsThePanelUntilThePointerRestsOnTheRuler() = runSkikoComposeUiTest(size = Size(400f, 300f)) {
        var value by mutableFloatStateOf(50f)
        val scroll = ScrollState(0)
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                EffectRuler(value, 1f, 0f, 100f, true, {}, { value = it }, {}, Modifier.testTag("ruler").fillMaxWidth().height(40.dp))
                Spacer(Modifier.height(2000.dp))
            }
        }
        onNodeWithTag("ruler").performMouseInput { moveTo(center); scroll(1f) }
        runOnIdle {
            assertEquals(50f, value)
            assertTrue("panel scroll ${scroll.value}", scroll.value > 0)
        }
        onNodeWithTag("ruler").performMouseInput { moveTo(center); advanceEventTime(600); scroll(1f) }
        runOnIdle { assertNotEquals(50f, value) }
    }
}
