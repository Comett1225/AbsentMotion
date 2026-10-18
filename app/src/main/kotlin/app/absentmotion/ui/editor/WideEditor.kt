package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterFade

val LocalWide = compositionLocalOf { false }

val WideMinWidth = 900.dp

val PhoneWidth = 384.dp
val PhoneHeight = 832.dp

val PanelHeight = 200.dp

private val SideWidth = 420.dp

@Composable
internal fun WideEditorBody(state: EditorState, onExit: () -> Unit, addLibrary: AddLibrary) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Am.TopBar)) {
        val timeline = (maxHeight - 88.dp - 260.dp).coerceIn(140.dp, 320.dp)
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(state, onExit, Modifier.fillMaxWidth().height(42.dp).enterFade())
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Preview(state, Modifier.fillMaxSize())
                    if (state.previewPanelOpen) PreviewModePanel(state, Modifier.align(Alignment.CenterEnd).enterFade())
                }
                Box(Modifier.width(SideWidth).fillMaxHeight().background(Am.TimelineBg)) { LowerPanels(state, addLibrary, top = true) }
            }
            Box(Modifier.fillMaxWidth().height(46.dp).background(Am.TimelineBg)) {
                PlayBar(state, Modifier.align(Alignment.Center).width(560.dp).fillMaxHeight())
                LevelMeter(state, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.1.dp))
            }
            Timeline(state, Modifier.fillMaxWidth().height(timeline).background(Am.TimelineBg))
        }
    }
}

@Composable
fun PhoneScreen(content: @Composable () -> Unit) {
    if (!LocalWide.current) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) {}.padding(24.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.sizeIn(maxWidth = PhoneWidth * 1.2f, maxHeight = PhoneHeight * 1.2f), contentAlignment = Alignment.Center) {
            val fit = minOf(maxWidth / PhoneWidth, maxHeight / PhoneHeight)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * fit, density.fontScale), LocalWide provides false) {
                Box(Modifier.requiredSize(PhoneWidth, PhoneHeight).clip(RoundedCornerShape(12.dp))) { content() }
            }
        }
    }
}
