package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import app.absentmotion.editor.EditorPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.tutorial.tutorialTarget

val LocalWide = compositionLocalOf { false }

fun isTablet(smallestWidthDp: Int): Boolean = smallestWidthDp >= 600

fun wideEditor(smallestWidthDp: Int, widthDp: Int): Boolean = isTablet(smallestWidthDp) && widthDp >= 840

val PhoneWidth = 384.dp
val PhoneHeight = 832.dp

val PanelHeight = 200.dp

private val SideWidth = PhoneWidth

@Composable
internal fun WideEditorBody(state: EditorState, onExit: () -> Unit, addLibrary: AddLibrary) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding().displayCutoutPadding()) {
        val timeline = (maxHeight - 88.dp - 260.dp).coerceIn(140.dp, 320.dp)
        val textEdit = state.panel == EditorPanel.EditText && state.selected?.kind == "text"
        val textUi = remember { TextEditUi() }
        val side = textEdit || (state.selectedId != null && !state.addOpen && !MultiSelect.active)
        val reserve = if (side) SideWidth else 0.dp
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(state, onExit, Modifier.fillMaxWidth().height(42.dp).enterFade())
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Preview(state, Modifier.fillMaxSize().tutorialTarget("preview"), reserveEnd = reserve)
                Box(Modifier.fillMaxSize().padding(end = reserve)) {
                    Presence(state.previewPanelOpen) { PreviewModePanel(state, Modifier.align(Alignment.CenterEnd).presence(slideX = 0.4f)) }
                    AnimateButton(state, Modifier.align(Alignment.BottomEnd))
                    if (state.settings.transitionInPreview) TransitionButton(state, Modifier.align(Alignment.BottomStart).padding(12.dp).tutorialTarget("transition"))
                    HistoryNotes(state, Modifier.align(Alignment.BottomCenter))
                }
                if (side) SidePanels(state, addLibrary, textEdit, textUi, Modifier.align(Alignment.CenterEnd))
            }
            Box(Modifier.fillMaxWidth().height(46.dp).background(Am.TimelineBg)) {
                PlayBar(state, Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxSize().tutorialTarget("playbar"))
                if (!state.settings.transitionInPreview) TransitionButton(state, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).tutorialTarget("transition"), compact = true)
                LevelMeter(state, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.1.dp))
            }
            Box(Modifier.fillMaxWidth().height(timeline).background(Am.TimelineBg).tutorialTarget("timeline")) {
                Timeline(state, Modifier.fillMaxSize())
                if (!side) LowerPanels(state, addLibrary, transitionHere = false)
            }
        }
        if (textEdit && textUi.browser) PhoneScreen { Box(Modifier.fillMaxSize().enterFade(rise = 12.dp)) { TextFontBrowser(state, textUi) } }
    }
}

@Composable
private fun SidePanels(state: EditorState, addLibrary: AddLibrary, textEdit: Boolean, textUi: TextEditUi, modifier: Modifier) {
    Box(modifier.width(SideWidth).fillMaxHeight().background(Am.Panel).pointerInput(Unit) {}) {
        if (textEdit) TextEditor(state, textUi, embedded = true)
        else LowerPanels(state, addLibrary, top = true)
    }
}

@Composable
fun PhoneScreen(fillWidth: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!LocalWide.current) {
        content()
        return
    }
    if (fillWidth > 0.dp) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxHeight().widthIn(max = fillWidth).fillMaxWidth()) { content() }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) {}.systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.sizeIn(maxWidth = PhoneWidth * 1.2f, maxHeight = PhoneHeight * 1.2f), contentAlignment = Alignment.Center) {
            val fit = minOf(maxWidth / PhoneWidth, maxHeight / PhoneHeight)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * fit, density.fontScale), LocalWide provides false) {
                Box(Modifier.requiredSize(PhoneWidth, PhoneHeight).clip(RoundedCornerShape(12.dp))) { content() }
            }
        }
    }
}
